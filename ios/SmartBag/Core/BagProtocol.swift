// BagProtocol.swift: the text the bag sends over BLE, one message per line (port of Android Protocol.kt).
// The database parses lines itself; the phone parses only to answer the bag ("OK +1005 4.69" etc).
//
//   1005 | +1005 | ADD 1005        add one
//   -1005 | DEL 1005               remove one
//   +1005@E28011                   add, ignoring repeat reads of the same tag
//   {"op":"add","sku":"1005","uid":"E28011","qty":1}

import Foundation

public struct BagEvent: Equatable, Sendable {
    public let op: String      // "add" | "remove"
    public let sku: String
    public let uid: String?
    public let qty: Int
}

public enum BagProtocol {
    static func key(_ s: String) -> String {
        var out = ""
        for ch in s.trimmingCharacters(in: .whitespacesAndNewlines) { out.append(".#$[]/".contains(ch) ? "_" : ch) }
        return out
    }

    public static func parse(_ raw: String) -> BagEvent? {
        let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if line.isEmpty { return nil }
        if line.hasPrefix("{") {
            guard let data = line.data(using: .utf8),
                  let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
            let op = ((j["op"] as? String) ?? "add").lowercased()
            var sku = ""
            for k in ["sku", "id", "code"] {
                if let v = j[k], !(v is NSNull) { sku = "\(v)".trimmingCharacters(in: .whitespaces); break }
            }
            if sku.isEmpty { return nil }
            let uid = j["uid"].flatMap { $0 is NSNull ? nil : "\($0)" }
            let qty = max(1, (j["qty"] as? NSNumber)?.intValue ?? 1)
            return BagEvent(op: ["remove", "del", "rm"].contains(op) ? "remove" : "add", sku: key(sku), uid: uid, qty: qty)
        }
        var op = "add"
        var rest = Substring(line)
        let upper = line.uppercased()
        if let verb = ["ADD", "DEL", "REMOVE", "RM"].first(where: { upper.hasPrefix($0 + " ") || upper.hasPrefix($0 + "\t") }) {
            op = verb == "ADD" ? "add" : "remove"
            rest = line.dropFirst(verb.count)
        } else if line.hasPrefix("+") {
            rest = line.dropFirst()
        } else if line.hasPrefix("-") {
            op = "remove"; rest = line.dropFirst()
        }
        let parts = rest.trimmingCharacters(in: .whitespaces).split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false)
        let sku = parts.first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
        if sku.isEmpty { return nil }
        let uid = parts.count > 1 ? parts[1].trimmingCharacters(in: .whitespaces) : ""
        return BagEvent(op: op, sku: key(sku), uid: uid.isEmpty ? nil : uid, qty: 1)
    }

    /// Reply the phone writes back to the bag after the database has taken a line.
    public static func ack(for ev: BagEvent, in lines: [CartLine]) -> String {
        if ev.op == "remove" { return "OK -\(ev.sku)" }
        if let l = lines.first(where: { $0.sku == ev.sku }), l.sold { return "OK +\(ev.sku) \(decimal(l.priceCents))" }
        return "ERR \(ev.sku) unknown"
    }
}

/// "$4.69", always with a dot, like the Android app.
public func money(_ cents: Int) -> String { "$" + decimal(cents) }
func decimal(_ cents: Int) -> String {
    let sign = cents < 0 ? "-" : ""
    let a = abs(cents)
    return sign + "\(a / 100)." + (a % 100 < 10 ? "0" : "") + "\(a % 100)"
}
public func distanceText(_ m: Int) -> String {
    if m < 1000 { return "\(m) m" }
    let km = Double(m) / 1000
    return m < 10_000 ? String(format: "%.1f km", km) : String(format: "%.0f km", km)
}
