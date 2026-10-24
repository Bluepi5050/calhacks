// Notifications.swift: what Bag It says in a notification, and which kinds the shopper wants. Pure Foundation, so
// the copy is tested on Linux (tools/verify-linux.sh). Local notifications use the same words as the module's
// server pushes, so a person never sees two styles. One exception: the server's payment-due line says "Add a card
// in Bag It to settle it", but there is no way to settle a past trip in the app, so the phone says what is true.

import Foundation

public enum NotificationKind: String, CaseIterable, Sendable {
    case trips, store, bag, security
    /// iOS thread ids (grouping in Notification Center).
    public var thread: String { switch self { case .trips: return "trips"; case .store: return "store"; case .bag: return "bag"; case .security: return "account" } }
}

public struct NotificationText: Equatable, Sendable {
    public let kind: NotificationKind
    public let id: String                 // request identifier: the same event replaces, never duplicates
    public let title: String
    public let body: String
    public let info: [String: String]     // routing for a tap: type, code, orderId
}

public enum NotificationCopy {
    /// A walk-out charged on this phone: "Paid $11.18 at Bayside Grocer" / "2 items with Visa •••• 4242. Exit code SRF6WQ."
    public static func receipt(_ r: Receipt) -> NotificationText {
        let n = r.itemCount == 1 ? "1 item" : "\(r.itemCount) items"
        let info = ["type": r.isPaid ? "trip" : "payment_due", "code": r.code, "orderId": String(r.orderId)]
        if r.isPaid {
            let card = r.payment.isEmpty ? "" : " with \(r.payment)"
            return NotificationText(kind: .trips, id: "trip-\(r.orderId)", title: "Paid \(money(r.totalCents)) at \(r.storeName)",
                                    body: "\(n)\(card). Exit code \(r.code).", info: info)
        }
        return NotificationText(kind: .trips, id: "trip-\(r.orderId)", title: "Payment due at \(r.storeName)",
                                body: "\(money(r.totalCents)) for \(n). No card was on file: show exit code \(r.code) to staff to pay.", info: info)
    }

    /// Once per visit: "You're at Bayside Grocer".
    public static func arrival(store: String, storeId: String, payment: String) -> NotificationText {
        NotificationText(kind: .store, id: "store-\(storeId)", title: "You're at \(store)",
                         body: payment.isEmpty ? "Add a card before you leave, or you'll pay at the store."
                                               : "Drop things in your bag. Leaving pays with \(payment).",
                         info: ["type": "store"])
    }

    /// The bag dropped for 20 s while in a store with items in it.
    public static func bagLost() -> NotificationText {
        NotificationText(kind: .bag, id: "bag-lost", title: "Your bag disconnected",
                         body: "Keep it near your phone so new items are counted.", info: ["type": "bag"])
    }

    public static func test() -> NotificationText {
        NotificationText(kind: .trips, id: "test", title: "Notifications are on",
                         body: "This is how Bag It tells you about a trip.", info: ["type": "test"])
    }
}

/// Which notifications the shopper wants. All on by default. trips and security are also the server push prefs.
public struct NotificationPrefs: Equatable, Sendable {
    public var trips = true, store = true, bag = true, security = true
    public init(trips: Bool = true, store: Bool = true, bag: Bool = true, security: Bool = true) {
        self.trips = trips; self.store = store; self.bag = bag; self.security = security
    }
    public func allows(_ k: NotificationKind) -> Bool {
        switch k { case .trips: return trips; case .store: return store; case .bag: return bag; case .security: return security }
    }
    /// For push_register / push_set_prefs: "trips,security", "trips", "".
    public var server: String { [trips ? "trips" : nil, security ? "security" : nil].compactMap { $0 }.joined(separator: ",") }

    private static let key = "notification_prefs"
    public static func load(_ d: UserDefaults = .standard) -> NotificationPrefs {
        guard let off = d.stringArray(forKey: key) else { return NotificationPrefs() }       // stored = the kinds turned off
        return NotificationPrefs(trips: !off.contains("trips"), store: !off.contains("store"), bag: !off.contains("bag"), security: !off.contains("security"))
    }
    public func save(_ d: UserDefaults = .standard) {
        d.set(NotificationKind.allCases.filter { !allows($0) }.map(\.rawValue), forKey: Self.key)
    }
}
