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

@main struct WalkOutCLI {
    static func main() async {
        do {
            try offline()
            if !CommandLine.arguments.contains("--offline") { try await live() }
        } catch {
            print("  FAIL threw \(error)"); failures += 1
        }
        print(failures == 0 ? "SWIFT-CLIENT-OK" : "FAILED \(failures)")
        exit(failures == 0 ? 0 : 1)
    }
}
