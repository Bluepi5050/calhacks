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
        if line.isEmpty || isControl(line) { return nil }
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

// MARK: - Pairing (firmware v2)
//
// Every bag advertises a unique name, SmartBag-XXXX (v1 bags all say SmartBag-01). Writing "HELLO" makes its
// light blink three times and it answers "BAG <name> fw2". Lines starting with "BAG " are control lines: they
// never go to the database as items.

public struct BagHello: Equatable, Sendable {
    public let name: String
    public let firmware: String
}

extension BagProtocol {
    public static let hello = "HELLO"

    public static func isControl(_ raw: String) -> Bool {
        raw.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("BAG ")
    }

    /// "BAG SmartBag-3F2A fw2" -> (SmartBag-3F2A, fw2).
    public static func helloReply(_ raw: String) -> BagHello? {
        let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard line.hasPrefix("BAG ") else { return nil }
        let parts = line.dropFirst(4).split(separator: " ", omittingEmptySubsequences: true)
        guard let name = parts.first else { return nil }
        return BagHello(name: String(name), firmware: parts.count > 1 ? String(parts[1]) : "")
    }

    /// What a shopper sees for a bag's Bluetooth name: "Bag 3F2A"; plain "Bag" for v1 bags (all SmartBag-01).
    public static func displayName(_ bleName: String) -> String {
        if bleName == SmartBagConfig.demoBagId { return "Demo bag" }
        let prefix = "SmartBag-"
        guard bleName.hasPrefix(prefix) else { return bleName.isEmpty ? "Bag" : bleName }
        let suffix = String(bleName.dropFirst(prefix.count))
        return suffix.isEmpty || suffix == "01" ? "Bag" : "Bag \(suffix)"
    }

    /// Signal strength in words, for the "Bags nearby" list.
    public static func signal(rssi: Int) -> String {
        if rssi >= -55 { return "Right here" }
        if rssi >= -75 { return "Nearby" }
        return "Farther away"
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
