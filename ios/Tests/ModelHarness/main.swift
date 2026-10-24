// Drives the real ShopModel.swift (copied verbatim, minus its Apple-only imports) through a shopping
// trip against the live database: first-run demo payment, store from a GPS fix, bag lines in order with
// acks to the bag, the hidden demo walk-out, then the receipt + notification + "PAID <code>" to the bag.
import Foundation

var failures = 0
func check(_ ok: Bool, _ what: String) { print((ok ? "  ok   " : "  FAIL ") + what); if !ok { failures += 1 } }

@MainActor func settle(_ seconds: Double = 2.5) async { try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9)) }

@MainActor func run() async {
    UserDefaults.standard.removeObject(forKey: "seen_order")
    let m = ShopModel.shared
    await settle()
    check(m.payment == SmartBagConfig.demoPayment, "first run sets demo payment (\(m.payment))")
    let seen = (UserDefaults.standard.object(forKey: "seen_order") as? NSNumber)?.int64Value
    check(seen == 0, "first state records seen order id (\(String(describing: seen)))")
    check(m.paid == nil, "no receipt shown on first state")
    m.start()
    await settle(1.5)
    check(m.bag == .connected("SmartBag-Harness"), "bag connected")
    let lat = 37.7897, lng = -122.3972
    m.location.onFix(CLLocation(lat: lat + 15 / 111_320.0, lng: lng, acc: 12))
    await settle()
    check(m.inStore?.id == "bayside-grocer", "GPS fix places the phone in Bayside Grocer (\(m.store))")
    for line in ["+1005@K1", "+1005@K1", "+1008@K2", "9999"] { m.link.onLine(line) }
    await settle(3)
    check(m.count == 2 && m.total == 1118, "bag lines applied in order: \(m.count) items \(money(m.total))")
    check(m.link.sent == ["OK +1005 4.69", "OK +1005 4.69", "OK +1008 6.49", "ERR 9999 unknown"], "acks to the bag: \(m.link.sent)")
    check(m.lines.contains { $0.sku == "9999" && !$0.sold }, "unknown SKU listed as not sold here")
    m.dismissLine("9999")
    await settle()
    check(!m.lines.contains { $0.sku == "9999" }, "Remove drops the unsold line")
    m.demoWalkOut()
    await settle(3)
    guard let r = m.paid else { check(false, "receipt shown after walk-out (store \(m.store), error \(m.syncError ?? "-"))"); return }
    check(r.totalCents == 1118 && r.isPaid, "walk-out charged \(money(r.totalCents)) \(r.status)")
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
}

@main struct Harness {
    static func main() async {
        await run()
        print(failures == 0 ? "SHOPMODEL-OK" : "FAILED \(failures)")
        exit(failures == 0 ? 0 : 1)
    }
}
