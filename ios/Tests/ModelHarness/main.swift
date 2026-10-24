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
    check(r.totalCents == 1118 && r.isPaid && r.payment == "Visa •••• 4242", "walk-out charged \(money(r.totalCents)) \(r.status) to \(r.payment)")
    if case let .away(name, _) = m.store { check(name == "Bayside Grocer", "store state is away after leaving (\(m.store))") } else { check(false, "store state is away after leaving (\(m.store))") }
    check(m.link.sent.last == "PAID \(r.code)", "PAID \(r.code) sent to the bag")
    check(Notify.shared.posted.last?.id == "trip-\(r.orderId)" && Notify.shared.posted.last?.title == "Paid $11.18 at Bayside Grocer", "receipt notification posted (\(Notify.shared.posted.last?.title ?? "-"))")
    check(Notify.shared.posted.filter { $0.id == "store-bayside-grocer" }.count == 1, "store arrival notified once for the visit")
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
    check(UserDefaults.standard.string(forKey: "visit_store") == nil, "visit forgotten after the walk-out")

    // Second trip: no card, a real bag, woken by a region crossing (background exit check).
    do {
        try await acct.removeCard(acct.state!.cards[0].id)
        try await acct.unpairBag(acct.state!.bags[0].id)
        try await acct.pairBag(bagId: "SmartBag-Harness", name: "Harness bag")
    } catch { check(false, "wallet/bag changes: \(userMessage(error))") }
    await settle()
    check(m.payment.isEmpty && m.bag == .connected("SmartBag-Harness"), "no card; real bag connected (\(m.bag))")
    m.bagAlertDelay = 0.5
    let before = Notify.shared.posted.count
    let entry = Task { await m.checkNow(fixes: 1, gap: 0, timeout: 8) }
    try? await Task.sleep(nanoseconds: 300_000_000)
    m.location.onFix(CLLocation(lat: lat + 10 / 111_320.0, lng: lng, acc: 10))
    await entry.value
    await settle(1.5)
    check(m.inStore?.id == "bayside-grocer", "region entry check placed the phone in the store (\(m.store))")
    let arrival = Notify.shared.posted.dropFirst(before).first { $0.kind == .store }
    check(arrival?.body == "Add a card before you leave, or you'll pay at the store.", "new visit: arrival says add a card (\(arrival?.body ?? "none"))")
    m.link.onLine("+1001@B1")
    await settle(2)
    m.link.onState(.off)
    try? await Task.sleep(nanoseconds: 1_200_000_000)
    check(Notify.shared.posted.contains { $0.id == "bag-lost" }, "bag gone 0.5 s in a store with items -> 'Your bag disconnected'")
    let lostCount = Notify.shared.posted.filter { $0.id == "bag-lost" }.count
    m.link.onState(.connected("SmartBag-Harness"))
    await settle(1)
    let exit = Task { await m.checkNow(fixes: 2, gap: 1, timeout: 12) }
    try? await Task.sleep(nanoseconds: 300_000_000)
    m.location.onFix(CLLocation(lat: lat + 270 / 111_320.0, lng: lng, acc: 8))
    try? await Task.sleep(nanoseconds: 1_400_000_000)
    m.location.onFix(CLLocation(lat: lat + 300 / 111_320.0, lng: lng, acc: 8))
    await exit.value
    await settle(1)
    if let due = m.paid, !due.isPaid {
        check(true, "background exit check: two fixes -> walk-out, payment due \(money(due.totalCents))")
        check(!m.link.sent.contains("PAID \(due.code)"), "no PAID to the bag for a payment-due trip")
        check(Notify.shared.posted.last?.title == "Payment due at Bayside Grocer", "payment-due notification (\(Notify.shared.posted.last?.body ?? "-"))")
        m.finishReceipt(); m.foreground(); await settle()
        check(Notify.shared.posted.filter { $0.id == "trip-\(due.orderId)" }.count == 1 && m.paid == nil, "no duplicate receipt after the next refresh")
    } else { check(false, "background exit check walked out (paid \(String(describing: m.paid)), store \(m.store))") }
    check(Notify.shared.posted.filter { $0.id == "bag-lost" }.count == lostCount, "one bag alert per drop")
    var off = NotificationPrefs.load(); off.store = false; off.save()
    check(!NotificationPrefs.load().allows(.store), "store arrivals can be turned off")
    NotificationPrefs().save()
    do { try await acct.addCard(draft, nickname: "Demo", makeDefault: true) } catch { check(false, "re-add card") }

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
    m.start(); await settle(1.5)
    check(m.bag == .connected("SmartBag-Harness"), "bag reconnected after log-in (\(m.bag))")
    do { let s = try await m.api.state(); check(s.bag == "SmartBag-Harness", "bag name re-sent to the module after log-in ('\(s.bag)')") } catch { check(false, "state") }
    // Mid-trip: deleting the account is refused and nothing local changes.
    m.location.onFix(CLLocation(lat: lat + 10 / 111_320.0, lng: lng, acc: 10)); await settle(2)
    m.link.onLine("+1001@C1"); await settle(2)
    do { try await acct.deleteAccount(password: pw); check(false, "delete refused mid-trip") } catch {
        check(userMessage(error).contains("middle of a trip") && acct.signedIn && UserDefaults.standard.data(forKey: "account_cache") != nil,
              "mid-trip delete refused, still signed in, cache kept -> \(userMessage(error))")
    }
    m.dismissLine("1001"); m.link.onLine("-1001@C1"); await settle(2)
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
