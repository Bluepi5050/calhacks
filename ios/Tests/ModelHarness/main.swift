// Drives the real ShopModel.swift and AccountModel.swift (copied verbatim, minus their Apple-only imports)
// against the live database: sign up, setup plan, demo bag, a card from the wallet, store from a GPS fix,
// bag lines in order with acks (BAG control lines ignored), the hidden demo walk-out, receipt + notification +
// "PAID <code>" to the bag, trips, the cached account across a relaunch, sign-out, and account deletion.
import Foundation

var failures = 0
func check(_ ok: Bool, _ what: String) { print((ok ? "  ok   " : "  FAIL ") + what); if !ok { failures += 1 } }

@MainActor func settle(_ seconds: Double = 2.5) async { try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9)) }

@MainActor func run() async {
    for k in ["seen_order", "account_cache", "setup_steps", "setup_plan", "onboarded", "trip_floor"] { UserDefaults.standard.removeObject(forKey: k) }
    let m = ShopModel.shared
    let acct = AccountModel.shared
    await settle()
    check(m.payment.isEmpty, "no first-run demo payment (payment '\(m.payment)')")
    check(acct.state == nil && !acct.signedIn, "fresh install: account unknown, nothing cached")
    let seen = (UserDefaults.standard.object(forKey: "seen_order") as? NSNumber)?.int64Value
    check(seen == 0, "first state records seen order id (\(String(describing: seen)))")
    check(m.paid == nil, "no receipt shown on first state")

    let stamp = Int(Date().timeIntervalSince1970)
    let email = "test+h\(stamp)@bagitwith.us", pw = "harness pw \(stamp)"
    do { try await acct.signUp(name: "Harness Shopper", email: email, password: pw) } catch { check(false, "sign up: \(userMessage(error))"); return }
    check(acct.signedIn && acct.setup == [.permissions, .pair, .card], "sign up -> setup plan \(acct.setup)")
    acct.finishStep(.permissions)
    UserDefaults.standard.set(true, forKey: "onboarded")
    check(acct.suggestedBagName == "Harness's bag", "suggested bag name")
    do { try await acct.pairBag(bagId: SmartBagConfig.demoBagId, name: "Demo bag") } catch { check(false, "pair demo bag: \(userMessage(error))") }
    check(m.pairedBags == ["DEMO"], "demo bag paired (\(m.pairedBags))")
    acct.finishStep(.pair)
    guard case let .success(draft) = CardCheck.validate(number: CardCheck.demo.number, expiry: CardCheck.demo.expiry, cvc: CardCheck.demo.cvc, now: (2026, 10)) else { return }
    do { try await acct.addCard(draft, nickname: " Demo ", makeDefault: true) } catch { check(false, "add card: \(userMessage(error))") }
    acct.finishStep(.card)
    check(acct.setup.isEmpty && acct.state?.payment == "Demo Visa •••• 4242", "setup done; wallet pays with \(acct.state?.payment ?? "-")")
    await settle()
    check(m.payment == "Demo Visa •••• 4242", "the shop picks up the wallet's card (\(m.payment))")
    let cached = String(decoding: UserDefaults.standard.data(forKey: "account_cache") ?? Data(), as: UTF8.self)
    check(cached.contains(email) && !cached.contains(pw) && !cached.contains("4242 4242"), "AccountState cached without password or card number")
    check(!(UserDefaults.standard.dictionaryRepresentation().values.contains { "\($0)".contains(pw) }), "password stored nowhere in defaults")

    m.start()
    await settle(1.5)
    check(m.bag == .connected("Demo bag") && m.link.connects == 0, "demo bag counts as connected, no Bluetooth (\(m.bag))")
    let lat = 37.7897, lng = -122.3972
    m.location.onFix(CLLocation(lat: lat + 15 / 111_320.0, lng: lng, acc: 12))
    await settle()
    check(m.inStore?.id == "bayside-grocer", "GPS fix places the phone in Bayside Grocer (\(m.store))")
    for line in ["+1005@K1", "BAG SmartBag-3F2A fw2", "+1005@K1", "+1008@K2", "9999"] { m.link.onLine(line) }
    await settle(3)
    check(m.count == 2 && m.total == 1118, "bag lines applied in order: \(m.count) items \(money(m.total))")
    check(m.link.sent == ["OK +1005 4.69", "OK +1005 4.69", "OK +1008 6.49", "ERR 9999 unknown"], "acks to the bag, BAG control line ignored: \(m.link.sent)")
    check(m.lines.contains { $0.sku == "9999" && !$0.sold }, "unknown SKU listed as not sold here")
    m.dismissLine("9999")
    await settle()
    check(!m.lines.contains { $0.sku == "9999" }, "Remove drops the unsold line")
    m.demoWalkOut()
    await settle(3)
    guard let r = m.paid else { check(false, "receipt shown after walk-out (store \(m.store), error \(m.syncError ?? "-"))"); return }
    check(r.totalCents == 1118 && r.isPaid && r.payment == "Demo Visa •••• 4242", "walk-out charged \(money(r.totalCents)) \(r.status) to \(r.payment)")
    if case let .away(name, _) = m.store { check(name == "Bayside Grocer", "store state is away after leaving (\(m.store))") } else { check(false, "store state is away after leaving (\(m.store))") }
    check(m.link.sent.last == "PAID \(r.code)", "PAID \(r.code) sent to the bag")
    check(Notify.shared.posted.last?.orderId == r.orderId, "local notification posted")
    check((UserDefaults.standard.object(forKey: "seen_order") as? NSNumber)?.int64Value == Int64(r.orderId), "seen order persisted")
    check(m.lines.isEmpty && m.count == 0, "bag empty")
    m.finishReceipt()
    m.foreground()
    await settle()
    check(m.paid == nil, "same receipt is not shown twice")
    check(m.lastReceipt?.orderId == r.orderId, "Last trip keeps the latest receipt after Done")
    print("  status line: \(m.statusLine) · exit pass \(r.exitPass)")

    await acct.loadTrips()
    check(acct.trips?.first?.orderId == r.orderId, "Trips lists the walk-out first (\(acct.trips?.count ?? 0))")

    // Relaunch: a new model reads the cache and is signed in straight away (no network needed to show tabs).
    let relaunched = AccountModel(api: SpacetimeClient(tokens: MemoryTokenStore()), shop: nil)
    check(relaunched.signedIn && relaunched.state?.email == email && relaunched.setup.isEmpty, "relaunch: signed in from the cache, setup done")

    do { try await acct.signOut() } catch { check(false, "sign out: \(userMessage(error))") }
    await settle()
    check(!acct.signedIn && UserDefaults.standard.data(forKey: "account_cache") == nil && acct.trips == nil, "sign out clears the cache and trips")
    check(!m.started && m.payment.isEmpty && !m.location.running, "sign out stops the shop; no card on this phone (\(m.payment))")
    check(m.visibleLastReceipt == nil, "the next person on this phone doesn't see the last trip")
    let after = AccountModel(api: SpacetimeClient(tokens: MemoryTokenStore()), shop: nil)
    check(after.state == nil, "relaunch after sign-out: signed out")

    do { try await acct.signIn(email: email, password: "wrong wrong") ; check(false, "wrong password rejected") } catch {
        check(userMessage(error) == "Email or password is wrong.", "wrong password -> \(userMessage(error))")
    }
    do { try await acct.signIn(email: email, password: pw) } catch { check(false, "log in: \(userMessage(error))") }
    check(acct.signedIn && acct.setup.isEmpty, "log in with a bag and a card: no setup steps (\(acct.setup))")
    do { try await acct.deleteAccount(password: pw) } catch { check(false, "delete: \(userMessage(error))") }
    check(!acct.signedIn, "account deleted")
}

@main struct Harness {
    static func main() async {
        await run()
        print(failures == 0 ? "SHOPMODEL-OK" : "FAILED \(failures)")
        exit(failures == 0 ? 0 : 1)
    }
}
