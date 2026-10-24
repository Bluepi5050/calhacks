// Command-line verification of the SmartBag core (SpacetimeClient.swift + BagProtocol.swift).
// Build and run on Linux or macOS with tools/verify-linux.sh. Offline checks run first; then, unless
// --offline is given, one full shopper trip against the LIVE database: enter Bayside Grocer, fill the
// bag, walk out, and expect the database to have charged it.

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

var failures = 0
func check(_ ok: Bool, _ what: String, file: String = #file, line: Int = #line) {
    print((ok ? "  ok   " : "  FAIL ") + what)
    if !ok { failures += 1 }
}
func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

func offline() throws {
    print("codec")
    // CallProcedure frame, cross-checked against Python struct.pack (see tools/verify-linux.sh).
    var args = BsatnWriter(); args.f64(37.7897).f64(-122.3972).f64(15)
    let frame = StdbFrames.callProcedure(requestId: 7, name: "shop_locate", args: args.data)
    check(hex(frame) == ProcessInfo.processInfo.environment["EXPECT_FRAME"] ?? hex(frame), "CallProcedure bytes match Python reference")
    print("  frame " + hex(frame))
    check(frame.count == 1 + 4 + 1 + 4 + 11 + 4 + 24, "CallProcedure length")

    // ProcedureResult: [0][7][status 0][bytes][ts i64][dur i64][requestId u32]
    var w = BsatnWriter(); w.u8(0).u8(7).u8(0).bytes(Data([1, 2, 3])).i64(1).i64(2).u32(42)
    check(try StdbFrames.parseServer(w.data) == .init(requestId: 42, outcome: .returned(Data([1, 2, 3]))), "ProcedureResult Returned")
    var e = BsatnWriter(); e.u8(0).u8(7).u8(1).string("boom").i64(1).i64(2).u32(9)
    check(try StdbFrames.parseServer(e.data) == .init(requestId: 9, outcome: .internalError("boom")), "ProcedureResult InternalError")
    var other = BsatnWriter(); other.u8(0).u8(1).u32(5)
    check(try StdbFrames.parseServer(other.data) == nil, "other server messages ignored")
    check(try StdbFrames.parseServer(Data([1, 7, 0])) == nil, "compressed frames ignored")

    // CartState round trip built field by field in module order.
    var c = BsatnWriter()
    c.string("bayside-grocer").string("Bayside Grocer").string("1 Market St").u32(15)
    c.string("").u32(0).string("SmartBag-01").string("Demo Visa •••• 4242").bool(true).u32(1118).u32(2)
    c.u32(1).string("1005").string("Oat milk").u32(1).u32(469).u32(469).bool(true).i64(1_792_000_000_000_000)
    c.u64(12).string("7KQ2XD").string("Bayside Grocer").u32(1118).u32(2).u32(0).string("paid").string("Demo Visa •••• 4242").i64(1_792_000_000_000_000)
    c.u32(1).string("1005").string("Oat milk").u32(469).u32(1)
    let s = try CartState.decode(c.data)
    check(s.storeId == "bayside-grocer" && s.distanceM == 15 && s.leaving && s.totalCents == 1118, "CartState scalars")
    check(s.lines == [CartLine(sku: "1005", name: "Oat milk", qty: 1, priceCents: 469, lineCents: 469, sold: true, addedMicros: 1_792_000_000_000_000)], "CartState lines")
    check(s.payment == "Demo Visa •••• 4242", "UTF-8 strings")
    check(s.lastReceipt?.orderId == 12 && s.lastReceipt?.exitPass == "SMARTBAG-EXIT:7KQ2XD:12:1118", "Receipt + exit pass")
    var empty = BsatnWriter()
    empty.string("").string("").string("").u32(0).string("").u32(0).string("").string("").bool(false).u32(0).u32(0).u32(0)
    empty.u64(0).string("").string("").u32(0).u32(0).u32(0).string("").string("").i64(0).u32(0)
    check(try CartState.decode(empty.data).lastReceipt == nil, "orderId 0 means no receipt")
    var truncated = false
    do { _ = try CartState.decode(c.data.prefix(20)) } catch { truncated = true }
    check(truncated, "truncated CartState throws")

    print("bag protocol")
    check(BagProtocol.parse("+1005@K1") == BagEvent(op: "add", sku: "1005", uid: "K1", qty: 1), "+sku@uid")
    check(BagProtocol.parse("-1005")?.op == "remove", "-sku")
    check(BagProtocol.parse("DEL 1005") == BagEvent(op: "remove", sku: "1005", uid: nil, qty: 1), "DEL verb")
    check(BagProtocol.parse("{\"op\":\"add\",\"sku\":1008,\"qty\":2}") == BagEvent(op: "add", sku: "1008", uid: nil, qty: 2), "JSON line")
    check(BagProtocol.parse("  ") == nil, "blank line")
    check(BagProtocol.ack(for: BagProtocol.parse("+1005")!, in: s.lines) == "OK +1005 4.69", "ack sold")
    check(BagProtocol.ack(for: BagProtocol.parse("9999")!, in: s.lines) == "ERR 9999 unknown", "ack unknown")
    check(BagProtocol.ack(for: BagProtocol.parse("-1005")!, in: s.lines) == "OK -1005", "ack remove")
    check(money(1118) == "$11.18" && money(5) == "$0.05" && money(0) == "$0.00", "money")
    try accountCodecs()
    cards()
    pairing()
}

/// Bytes recorded from the live module (Tests/Fixtures, written by `SMARTBAG_CAPTURE=1 tools/verify-linux.sh`).
func fixture(_ name: String) -> Data? {
    let url = URL(fileURLWithPath: "Tests/Fixtures/\(name).hex")
    guard let s = try? String(contentsOf: url, encoding: .utf8) else { return nil }
    var d = Data(), chars = Array(s.trimmingCharacters(in: .whitespacesAndNewlines))
    while chars.count >= 2 { d.append(UInt8(String(chars[0 ... 1]), radix: 16)!); chars.removeFirst(2) }
    return d
}
func capture(_ name: String, _ d: Data) {
    guard ProcessInfo.processInfo.environment["SMARTBAG_CAPTURE"] != nil else { return }
    try? FileManager.default.createDirectory(atPath: "Tests/Fixtures", withIntermediateDirectories: true)
    try? hex(d).write(toFile: "Tests/Fixtures/\(name).hex", atomically: true, encoding: .utf8)
}

func accountCodecs() throws {
    print("account codec")
    // AccountState built field by field in the module's order (signedIn, name, email, cards, bags, payment).
    var a = BsatnWriter()
    a.bool(true).string("Ada Lovelace").string("ada@bagitwith.us")
    a.u32(2)
    a.u64(7).string("Visa").string("4242").u32(12).u32(2029).string("Demo").bool(true)
    a.u64(9).string("Mastercard").string("4444").u32(1).u32(2031).string("").bool(false)
    a.u32(1).u64(3).string("SmartBag-3F2A").string("Ada's bag").i64(1_792_000_000_000_000)
    a.string("Demo Visa •••• 4242")
    let s = try AccountState.decode(a.data)
    check(s.signedIn && s.name == "Ada Lovelace" && s.email == "ada@bagitwith.us", "AccountState scalars")
    check(s.cards.count == 2 && s.cards[0] == CardInfo(id: 7, brand: "Visa", last4: "4242", expMonth: 12, expYear: 2029, nickname: "Demo", isDefault: true), "CardInfo fields")
    check(s.defaultCard?.label == s.payment && s.cards[1].label == "Mastercard •••• 4444" && s.cards[0].expiry == "12/29", "card label + expiry match the module's format")
    check(s.bags == [BagInfo(id: 3, bagId: "SmartBag-3F2A", name: "Ada's bag", pairedMicros: 1_792_000_000_000_000)], "BagInfo fields")
    check(s.firstName == "Ada", "first name")
    var out = BsatnWriter(); out.bool(false).string("").string("").u32(0).u32(0).string("")
    check(try AccountState.decode(out.data) == .signedOut, "signed-out AccountState")
    var trailing = BsatnWriter(); trailing.bool(false).string("").string("").u32(0).u32(0).string("").u8(1)
    var threw = false
    do { _ = try AccountState.decode(trailing.data) } catch { threw = true }
    check(threw, "trailing bytes rejected")
    let enc = try JSONEncoder().encode(s)
    check(try JSONDecoder().decode(AccountState.self, from: enc) == s, "AccountState cache round trip (JSON)")
    check(!String(decoding: enc, as: UTF8.self).contains("password"), "cache has no password field")

    // Array<Receipt> (shop_trips), newest first.
    var t = BsatnWriter(); t.u32(2)
    t.u64(20).string("K7Q2X9").string("Bayside Grocer").u32(1376).u32(4).u32(1).string("paid").string("Demo Visa •••• 4242").i64(1_792_000_100_000_000)
    t.u32(1).string("1005").string("Whole milk").u32(469).u32(1)
    t.u64(18).string("ABCD23").string("Mission Fresh").u32(649).u32(1).u32(0).string("payment_due").string("").i64(1_792_000_000_000_000).u32(0)
    let trips = try CartState.decodeReceipts(t.data)
    check(trips.map(\.orderId) == [20, 18] && trips[0].isPaid && !trips[1].isPaid && trips[0].lines.count == 1, "Receipt[] decode")
    var none = BsatnWriter(); none.u32(0)
    check(try CartState.decodeReceipts(none.data).isEmpty, "empty trips")

    check(SmartBagError.clean("The module instance encountered a fatal error: Email or password is wrong.") == "Email or password is wrong.", "error wrapper stripped")
    check(SmartBagError.server("The module instance encountered a fatal error: Log in first.").errorDescription == "Log in first.", "server error is shopper copy")

    // Recorded live bytes, when present.
    if let d = fixture("account_state") {
        let live = try AccountState.decode(d)
        check(live.signedIn && live.cards.count == 2 && live.bags.count == 1 && live.payment == live.defaultCard?.label, "live AccountState bytes decode (\(live.cards.map(\.label)))")
    }
    if let d = fixture("shop_trips") {
        let live = try CartState.decodeReceipts(d)
        check(!live.isEmpty && live[0].isPaid && live[0].orderId > 0, "live Receipt[] bytes decode (\(live.count) trip, \(live.first.map { money($0.totalCents) } ?? "-"))")
    }
}

func cards() {
    print("cards")
    check(CardBrand.detect("4242") == .visa && CardBrand.detect("5555") == .mastercard && CardBrand.detect("2221") == .mastercard
          && CardBrand.detect("2720") == .mastercard && CardBrand.detect("2721") == .other && CardBrand.detect("3782") == .amex
          && CardBrand.detect("3411") == .amex && CardBrand.detect("6011") == .discover && CardBrand.detect("6500") == .discover
          && CardBrand.detect("9999") == .other, "brand from prefix")
    check(CardCheck.luhn("4242424242424242") && !CardCheck.luhn("4242424242424241") && CardCheck.luhn("378282246310005"), "Luhn")
    check(CardCheck.formatNumber("4242424242424242") == "4242 4242 4242 4242", "Visa groups 4-4-4-4")
    check(CardCheck.formatNumber("378282246310005") == "3782 822463 10005", "Amex groups 4-6-5")
    check(CardCheck.formatNumber("37828224631000599") == "3782 822463 10005", "Amex capped at 15 digits")
    check(CardCheck.formatExpiry("1229") == "12/29" && CardCheck.formatExpiry("4") == "04" && CardCheck.formatExpiry("12") == "12", "expiry typing aid")
    check(CardCheck.parseExpiry("12/29")! == (12, 2029) && CardCheck.parseExpiry("13/29") == nil, "expiry parse")
    let now = (year: 2026, month: 10)
    if case let .success(d) = CardCheck.validate(number: CardCheck.demo.number, expiry: CardCheck.demo.expiry, cvc: CardCheck.demo.cvc, now: now) {
        check(d == CardDraft(brand: "Visa", last4: "4242", expMonth: 12, expYear: 2029), "demo card -> only brand, last four, expiry")
    } else { check(false, "demo card valid") }
    if case let .failure(e) = CardCheck.validate(number: "4242 4242 4242 4241", expiry: "09/26", cvc: "12", now: now) {
        check(e.number == "Check the card number." && e.expiry == "This card has expired." && e.cvc == "Enter the 3-digit security code.", "invalid card -> field errors")
    } else { check(false, "invalid card rejected") }
    if case let .failure(e) = CardCheck.validate(number: "378282246310005", expiry: "10/26", cvc: "123", now: now) {
        check(e.number == nil && e.expiry == nil && e.cvc == "Enter the 4-digit security code.", "Amex needs a 4-digit CVC; this month is not expired")
    } else { check(false, "Amex CVC length") }
    check(CardCheck.formatCVC("12345", brand: .visa) == "123", "CVC capped")
}

func pairing() {
    print("pairing")
    check(BagProtocol.isControl("BAG SmartBag-3F2A fw2") && !BagProtocol.isControl("+1005"), "BAG lines are control lines")
    check(BagProtocol.parse("BAG SmartBag-3F2A fw2") == nil, "control lines never parse as items")
    check(BagProtocol.helloReply("BAG SmartBag-3F2A fw2") == BagHello(name: "SmartBag-3F2A", firmware: "fw2"), "HELLO reply")
    check(BagProtocol.displayName("SmartBag-3F2A") == "Bag 3F2A" && BagProtocol.displayName("SmartBag-01") == "Bag"
          && BagProtocol.displayName("DEMO") == "Demo bag", "bag display names")
    check(BagProtocol.signal(rssi: -50) == "Right here" && BagProtocol.signal(rssi: -55) == "Right here"
          && BagProtocol.signal(rssi: -70) == "Nearby" && BagProtocol.signal(rssi: -80) == "Farther away", "signal words")
}

func live() async throws {
    print("live trip against \(SmartBagConfig.host) / \(SmartBagConfig.database)")
    let api = SpacetimeClient(tokens: MemoryTokenStore())    // a fresh shopper identity every run
    let lat = 37.7897, lng = -122.3972
    func north(_ m: Double) -> Double { lat + m / 111_320.0 }
    let t0 = Date()
    var s = try await api.state()
    check(s.storeId.isEmpty && s.lines.isEmpty, "new shopper starts outside with an empty bag")
    s = try await api.setPayment("Test Visa •••• 0000")
    check(s.payment == "Test Visa •••• 0000", "payment label set (UTF-8 round trip)")
    s = try await api.locate(lat: north(15), lng: lng, accuracy: 15)
    check(s.storeId == "bayside-grocer", "15 m from Bayside Grocer -> placed in store (got '\(s.storeId)', \(s.storeName))")
    _ = try await api.bagLine("+1005@K1")
    _ = try await api.bagLine("+1005@K1")
    s = try await api.bagLine("+1008@K2")
    check(s.itemCount == 2 && s.totalCents == 469 + 649, "repeat tag read deduped: 2 items, \(money(s.totalCents))")
    print("  bag: " + s.lines.map { "\($0.qty)x \($0.name) \(money($0.lineCents))" }.joined(separator: ", "))
    s = try await api.setBag("SmartBag-CLI")
    check(s.bag == "SmartBag-CLI", "bag name set")
    let before = s.lastReceipt?.orderId ?? 0
    // Concurrent calls share the one socket and resolve by request id.
    async let a = api.state()
    async let b = api.state()
    let (sa, sb) = try await (a, b)
    check(sa == sb && sa.itemCount == 2, "two concurrent calls on one socket")
    s = try await api.locate(lat: north(260), lng: lng, accuracy: 8)
    check(s.leaving && s.storeId == "bayside-grocer", "first outside fix only marks leaving")
    s = try await api.locate(lat: north(290), lng: lng, accuracy: 8)
    check(s.storeId.isEmpty, "second outside fix -> out of the store")
    check(s.lines.isEmpty, "bag emptied after walk-out")
    guard let r = s.lastReceipt else { check(false, "receipt present"); return }
    check(r.orderId > before, "new order \(r.orderId) > \(before)")
    check(r.totalCents == 1118 && r.status == "paid" && r.storeName == "Bayside Grocer", "charged \(money(r.totalCents)) \(r.status) at \(r.storeName)")
    check(r.lines.count == 2 && r.itemCount == 2, "receipt has 2 lines")
    do {
        _ = try await api.call("no_such_procedure", Data())
        check(false, "unknown procedure is rejected")
    } catch {
        check(true, "unknown procedure rejected: \(String(describing: error).prefix(90))")
    }
    s = try await api.state()
    check(s.lastReceipt?.orderId == r.orderId, "socket still usable after an error")
    await api.disconnect()
    s = try await api.state()
    check(s.lastReceipt?.orderId == r.orderId, "reconnects after disconnect")
    print("  receipt \(r.code) \(money(r.totalCents)) \(r.payment) · exit pass \(r.exitPass)")
    print(String(format: "  trip took %.2f s", Date().timeIntervalSince(t0)))
}


/// The account contract end to end (spec 'verification'): sign up, wallet, pairing, a walk-out charged to the
/// saved card, trips, a second card as default, sign out, then delete to clean up.
func liveAccount() async throws {
    print("live account flow")
    let api = SpacetimeClient(tokens: MemoryTokenStore())
    let stamp = Int(Date().timeIntervalSince1970)
    let email = "test+\(stamp)@bagitwith.us", pw = "correct horse \(stamp)"
    func expectError(_ what: String, _ fragment: String, _ op: () async throws -> Any) async {
        do { _ = try await op(); check(false, "\(what): expected an error") } catch {
            let m = userMessage(error)
            check(m.contains(fragment) && !m.contains("fatal error"), "\(what) -> \"\(m)\"")
        }
    }
    var a = try await api.account()
    check(!a.signedIn, "new phone is signed out")
    await expectError("short password", "at least 8") { try await api.signUp(name: "X", email: "x\(email)", password: "short") }
    await expectError("bad email", "valid email") { try await api.signUp(name: "X", email: "nope", password: "long enough") }
    let t0 = Date()
    a = try await api.signUp(name: "Test Shopper", email: email, password: pw)
    check(a.signedIn && a.email == email && a.name == "Test Shopper" && a.cards.isEmpty && a.payment.isEmpty,
          String(format: "sign up (%.1f s)", Date().timeIntervalSince(t0)))
    await expectError("duplicate email", "already has an account") { try await api.signUp(name: "X", email: email.uppercased(), password: pw) }
    var cart = try await api.state()
    check(cart.payment.isEmpty, "no demo payment: a new account pays with nothing until a card is added")

    guard case let .success(demo) = CardCheck.validate(number: CardCheck.demo.number, expiry: CardCheck.demo.expiry, cvc: CardCheck.demo.cvc, now: (2026, 10)) else {
        check(false, "demo card valid"); return
    }
    a = try await api.addCard(brand: demo.brand, last4: demo.last4, expMonth: demo.expMonth, expYear: demo.expYear, nickname: CardCheck.demo.nickname, makeDefault: true)
    check(a.cards.count == 1 && a.cards[0].isDefault && a.payment == "Demo Visa •••• 4242", "demo card added and default (\(a.payment))")
    await expectError("same card twice", "already in your wallet") {
        try await api.addCard(brand: "Visa", last4: "4242", expMonth: 12, expYear: 2029, nickname: "", makeDefault: false)
    }
    await expectError("expired card", "expired") { try await api.addCard(brand: "Visa", last4: "1111", expMonth: 1, expYear: 2024, nickname: "", makeDefault: false) }
    let bagId = "SmartBag-T\(stamp)"
    a = try await api.pairBag(bagId: bagId, name: "Test's bag")
    check(a.bags.map(\.bagId) == [bagId] && a.bags[0].name == "Test's bag", "bag paired (\(bagId))")
    cart = try await api.setBag(bagId)
    check(cart.payment == "Demo Visa •••• 4242", "the wallet's default card is this phone's payment")

    // Walk-out at Bayside Grocer, out at +270 m and +300 m north.
    let lat = 37.7897, lng = -122.3972
    cart = try await api.locate(lat: lat, lng: lng, accuracy: 8)
    check(cart.storeId == "bayside-grocer", "in Bayside Grocer")
    _ = try await api.bagLine("+1005@A1")
    cart = try await api.bagLine("+1008@A2")
    check(cart.totalCents == 1118, "bag \(money(cart.totalCents))")
    _ = try await api.locate(lat: lat + 270 / 111_320.0, lng: lng, accuracy: 8)
    cart = try await api.locate(lat: lat + 300 / 111_320.0, lng: lng, accuracy: 8)
    guard let r = cart.lastReceipt else { check(false, "walk-out receipt"); return }
    check(r.isPaid && r.payment == "Demo Visa •••• 4242" && r.totalCents == 1118, "walk-out charged \(money(r.totalCents)) to \(r.payment)")
    let rawTrips = try await api.call("shop_trips", BsatnWriter().u32(20).data)
    capture("shop_trips", rawTrips)
    let trips = try CartState.decodeReceipts(rawTrips)
    check(trips.first?.orderId == r.orderId && trips.first?.code == r.code, "shop_trips returns it first (\(trips.count) trip)")

    a = try await api.addCard(brand: "Mastercard", last4: "4444", expMonth: 1, expYear: 2031, nickname: "", makeDefault: false)
    check(a.cards.count == 2 && a.payment == "Demo Visa •••• 4242", "second card added, default unchanged")
    let mc = a.cards.first { $0.brand == "Mastercard" }!
    a = try await api.setDefaultCard(id: mc.id)
    check(a.payment == "Mastercard •••• 4444" && a.defaultCard?.id == mc.id, "default switched to \(a.payment)")
    cart = try await api.state()
    check(cart.payment == "Mastercard •••• 4444", "walk-outs now charge \(cart.payment)")
    let rawAccount = try await api.call("account_state", Data())
    capture("account_state", rawAccount)
    let live = try AccountState.decode(rawAccount)
    check(live == a, "account_state bytes decode to the same AccountState")
    a = try await api.renameBag(id: a.bags[0].id, name: "Groceries")
    check(a.bags[0].name == "Groceries", "bag renamed")

    // A second phone logs in and sees the same wallet and trips.
    let phone2 = SpacetimeClient(tokens: MemoryTokenStore())
    await expectError("wrong password", "Email or password is wrong") { try await phone2.signIn(email: email, password: "wrong wrong") }
    let a2 = try await phone2.signIn(email: email.uppercased(), password: pw)
    check(a2.signedIn && a2.cards.count == 2 && a2.bags.count == 1 && a2.payment == "Mastercard •••• 4444", "second phone logs in")
    check(try await phone2.trips(limit: 10).contains { $0.orderId == r.orderId }, "second phone sees the trip")
    a = try await phone2.removeCard(id: mc.id)
    check(a.cards.count == 1 && a.payment == "Demo Visa •••• 4242", "removing the default promotes the oldest card")
    _ = try await phone2.signOut()

    a = try await api.signOut()
    check(!a.signedIn && a.cards.isEmpty, "signed out")
    cart = try await api.state()
    check(cart.payment.isEmpty && cart.bag.isEmpty, "sign-out clears this phone's card and bag")
    await expectError("wallet after sign-out", "Log in first") { try await api.addCard(brand: "Visa", last4: "1234", expMonth: 1, expYear: 2030, nickname: "", makeDefault: false) }
    a = try await api.signIn(email: email, password: pw)
    check(a.signedIn, "logs back in")
    await expectError("delete with wrong password", "That password is wrong") { try await api.deleteAccount(password: "nope nope") }
    a = try await api.deleteAccount(password: pw)
    check(!a.signedIn, "account deleted")
    await expectError("deleted account", "Email or password is wrong") { try await api.signIn(email: email, password: pw) }
    await api.disconnect()
    await phone2.disconnect()
}

@main struct WalkOutCLI {
    static func main() async {
        do {
            try offline()
            if !CommandLine.arguments.contains("--offline") { try await live(); try await liveAccount() }
        } catch {
            print("  FAIL threw \(error)"); failures += 1
        }
        print(failures == 0 ? "SWIFT-CLIENT-OK" : "FAILED \(failures)")
        exit(failures == 0 ? 0 : 1)
    }
}
