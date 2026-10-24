// ShopModel.swift: the app-wide shopping session (port of Android ShopModel.kt).
//
// The database is the source of truth: every call returns the shopper's full priced bag, and payment
// happens in the database the moment a location fix shows the shopper has left the store. There is
// no Pay button. Calls go out strictly one at a time, in order, over the one WebSocket.

import Foundation
import Combine
import CoreLocation
import UIKit

enum BagState: Equatable {
    case off, searching
    case connecting(String)
    case connected(String)
    case problem(String)

    var isConnected: Bool { if case .connected = self { return true } else { return false } }
    var isBusy: Bool {
        switch self { case .searching, .connecting, .connected: return true; default: return false }
    }
}

enum StoreState: Equatable {
    case locating
    case noLocation
    case inStore(id: String, name: String, address: String, leaving: Bool)
    case away(nearestName: String?, nearestDistance: Int?)
    case offline(String)
}

struct Flash: Equatable { let sku: String; let n: Int }

@MainActor
final class ShopModel: ObservableObject {
    static let shared = ShopModel()

    @Published private(set) var bag: BagState = .off
    @Published private(set) var store: StoreState = .locating
    @Published private(set) var payment = ""
    @Published private(set) var total = 0
    @Published private(set) var count = 0
    @Published private(set) var lines: [CartLine] = []
    @Published private(set) var paid: Receipt?              // receipt to show after a walk-out
    @Published private(set) var lastReceipt: Receipt?       // latest walk-out order, for "Last trip" (UI only)
    @Published private(set) var flash: Flash?
    @Published private(set) var syncError: String?
    @Published private(set) var preciseLocationOff = false
    /// Paired bags from the account (Bluetooth names, DEMO for the demo bag). Empty: the chip offers pairing.
    @Published private(set) var pairedBags: [String] = []
    @Published private(set) var started = false

    let api = SpacetimeClient(tokens: KeychainTokenStore())
    private(set) var link: BagLink!
    private(set) var location: PhoneLocation!

    private var fix: CLLocation?
    private var sentAt: CLLocation?
    private var sentTime = Date.distantPast
    private var locating = false
    private var flashN = 0
    private var ignoreFixesUntil = Date.distantPast
    // Notifications and background checks (1.3)
    /// The app is on screen: store and bag notices show in-app instead of as notifications.
    private(set) var foregroundActive = false
    /// How long the bag may be gone (in a store, with items) before "Your bag disconnected". Tests shorten it.
    var bagAlertDelay: TimeInterval = 20
    private var bagAlerted = false
    private var bagLostAt: Date?
    private var forcedFixes = 0                 // background exit check: send the next N fixes regardless of distance
    private var forcedGap: TimeInterval = 3
    private var forcedSince = Date.distantPast  // only fixes taken after the check began count (no cached fix from before)
    private var demoBag = false                 // only the demo bag is paired: it counts as connected, no Bluetooth
    private var signedIn = false
    private let defaults = UserDefaults.standard

    // Everything goes to the database strictly in order, one call at a time.
    typealias Op = @MainActor () async throws -> Void
    private let ops: AsyncStream<Op>.Continuation

    var inStore: (id: String, name: String, address: String, leaving: Bool)? {
        if case let .inStore(id, name, address, leaving) = store { return (id, name, address, leaving) }
        return nil
    }

    private init() {
        var c: AsyncStream<Op>.Continuation!
        let stream = AsyncStream<Op>(bufferingPolicy: .unbounded) { c = $0 }
        ops = c
        link = BagLink(onLine: { [weak self] line in self?.onLine(line) },
                       onState: { [weak self] s in self?.onBag(s) })
        location = PhoneLocation(onFix: { [weak self] l in self?.onFix(l) },
                                 onStatus: { [weak self] s in self?.onLocationStatus(s) })
        Task { @MainActor [weak self] in
            for await op in stream {
                guard let self else { return }
                do {
                    try await op()
                    self.syncError = nil
                } catch {
                    let message = (error as? LocalizedError)?.errorDescription ?? "Couldn't reach Bag It."
                    self.syncError = message
                    if self.store == .locating { self.store = .offline(message) }
                }
            }
        }
        // Payment comes only from the wallet now (the module copies the default card onto this phone).
        enqueue { [unowned self] in self.apply(try await self.api.state(), fromLocation: false) }
    }

    private func enqueue(_ op: @escaping Op) { ops.yield(op) }

    /// Called after onboarding, and at every launch once onboarded (including background relaunches
    /// for location or Bluetooth restoration).
    func start() {
        guard !started else { return }
        started = true
        location.start()
        if demoBag { setBag(.connected("Demo bag")) } else { link.connect() }
    }

    /// Signed out: this phone stops watching the bag and the store (the module has already cleared its card and bag).
    func stop() {
        guard started else { return }
        started = false
        location.stop()
        link.disconnect()
        setBag(.off)
        refresh()
    }

    /// Fresh priced bag from the database (after a wallet change, for example).
    func refresh() {
        enqueue { [unowned self] in self.apply(try await self.api.state(), fromLocation: false) }
    }

    /// The account changed: paired bags decide which bags Bluetooth may connect to, the default card is what pays.
    func accountChanged(_ a: AccountState) {
        let wasSignedIn = signedIn
        signedIn = a.signedIn
        if wasSignedIn && !a.signedIn {
            // Hide the previous shopper's last trip from whoever signs in next on this phone, then stop watching.
            if let r = lastReceipt { defaults.set(NSNumber(value: Int64(bitPattern: r.orderId)), forKey: "trip_floor") }
            pairedBags = []
            link.setAllowed(nil)
            stop()
            demoBag = false
            return
        }
        let real = a.bags.filter { !$0.isDemo }.map(\.bagId)
        let unpairedConnected = connectedBagId.map { pairedBags.contains($0) && !a.bags.map(\.bagId).contains($0) } ?? false
        pairedBags = a.bags.map(\.bagId)
        // Any Bag It bag while none is paired (or only the demo bag); afterwards only the account's own bags.
        link.setAllowed(real.isEmpty ? nil : Set(real))
        let demo = real.isEmpty && a.bags.contains { $0.isDemo }
        if demo != demoBag {
            demoBag = demo
            if started {
                if demo { link.disconnect(); setBag(.connected("Demo bag")) } else { setBag(.off); link.connect() }
            }
        }
        if unpairedConnected && !demoBag && started {
            link.disconnect()                     // the bag just unpaired is no longer this phone's
            link.connect()
        }
        if a.payment != payment { refresh() }
    }

    /// Module v5.2 clears the bag name on every account change: after a successful sign-in / sign-up, tell it again.
    func resendBag() {
        guard case let .connected(name) = bag else { return }
        enqueue { [unowned self] in self.apply(try await self.api.setBag(name), fromLocation: false) }
    }

    /// After pairing without keeping a connection: pick the shopping connection back up if the shop is running.
    func resumeBag() { if started { connectBag() } }

    // MARK: notifications

    /// The app came to the foreground or went away (scene phase).
    func setForeground(_ on: Bool) { foregroundActive = on }

    /// Posts a notification if the shopper wants that kind.
    private func notify(_ t: NotificationText) {
        guard NotificationPrefs.load(defaults).allows(t.kind) else { return }
        Notify.shared.post(t)
    }

    /// "You're at <store>" once per visit: remembered across relaunches, forgotten once the phone is out of the store.
    private func visitChanged() {
        let current = defaults.string(forKey: "visit_store")
        if case let .inStore(id, name, _, _) = store {
            guard current != id else { return }
            defaults.set(id, forKey: "visit_store")
            if !foregroundActive { notify(NotificationCopy.arrival(store: name, storeId: id, payment: payment)) }
        } else if case .away = store, current != nil {
            defaults.removeObject(forKey: "visit_store")
        }
    }

    /// The bag dropped while in a store with items: after `bagAlertDelay` still gone -> one notification per drop.
    private func watchBag(before: BagState) {
        if bag.isConnected { bagLostAt = nil; bagAlerted = false; return }
        guard before.isConnected, !demoBag, started else { return }
        let at = Date()
        bagLostAt = at
        Task { @MainActor [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: UInt64(self.bagAlertDelay * 1_000_000_000))
            guard self.bagLostAt == at, !self.bag.isConnected, !self.bagAlerted, self.inStore != nil, self.count > 0,
                  !self.foregroundActive, self.started else { return }
            self.bagAlerted = true
            self.notify(NotificationCopy.bagLost())
        }
    }

    // MARK: background exit check

    /// A store region was crossed (iOS woke the app): report the next `fixes` location fixes at least `gap` s apart,
    /// whatever the distance moved (the module needs two fixes outside to count the walk-out). Returns when they
    /// are sent or `timeout` passes. StoreRegions wraps this in a background task and calls cancelCheck() on expiry.
    func checkNow(fixes: Int = 2, gap: TimeInterval = 3, timeout: TimeInterval = 25) async {
        ignoreFixesUntil = .distantPast             // a real region crossing outranks a demo pause
        forcedFixes = fixes
        forcedGap = gap
        forcedSince = Date()
        let deadline = Date().addingTimeInterval(timeout)
        while forcedFixes > 0 && Date() < deadline && !Task.isCancelled {
            sendForced()                            // re-sends the newest fresh fix every `gap` s even if no new one arrives
            try? await Task.sleep(nanoseconds: 300_000_000)
        }
        forcedFixes = 0
        // Let the last locate finish (the receipt and its notification come from its reply).
        let settle = Date().addingTimeInterval(4)
        while locating && Date() < settle && !Task.isCancelled { try? await Task.sleep(nanoseconds: 200_000_000) }
    }

    /// Background time is up: stop asking for more fixes. A locate already sent finishes on its own.
    func cancelCheck() { forcedFixes = 0 }

    private func sendForced() {
        guard forcedFixes > 0, !locating, let f = fix, f.timestamp >= forcedSince,
              Date().timeIntervalSince(sentTime) >= forcedGap else { return }
        forcedFixes -= 1
        refreshStore()
    }

    /// "Last trip": only this account's trips on this phone (see accountChanged).
    var visibleLastReceipt: Receipt? {
        guard let r = lastReceipt else { return nil }
        let floor = (defaults.object(forKey: "trip_floor") as? NSNumber)?.int64Value ?? 0
        return Int64(bitPattern: r.orderId) > floor ? r : nil
    }

    /// The app came to the foreground: pick up permission changes and refresh the bag.
    func foreground() {
        guard started else { return }
        location.start()
        if case .problem = bag { link.connect() }
        if store == .noLocation || fix == nil { refreshStore() }
        enqueue { [unowned self] in self.apply(try await self.api.state(), fromLocation: false) }
    }

    private func apply(_ s: CartState, fromLocation: Bool) {
        payment = s.payment
        if !s.storeId.isEmpty {
            store = .inStore(id: s.storeId, name: s.storeName, address: s.storeAddress, leaving: s.leaving)
        } else if fromLocation || (store != .locating && store != .noLocation) {
            store = .away(nearestName: s.nearestName.isEmpty ? nil : s.nearestName, nearestDistance: s.nearestName.isEmpty ? nil : s.nearestM)
        }
        visitChanged()
        lines = s.lines.sorted { $0.addedMicros < $1.addedMicros }
        total = s.totalCents
        count = s.itemCount

        // Receipts: show one only when the database has charged a NEW walk-out since we last looked.
        let seen = (defaults.object(forKey: "seen_order") as? NSNumber)?.int64Value
        let r = s.lastReceipt
        lastReceipt = r
        let rid = Int64(bitPattern: r?.orderId ?? 0)
        if let r, let seen, rid > seen {
            paid = r
            // The bag's light says "paid" on PAID; a payment-due trip was not paid, so the bag isn't told it was.
            if r.isPaid { link.send("PAID \(r.code)") }
            // On screen the receipt cover shows it; otherwise a notification (id trip-<orderId>: never twice).
            if !foregroundActive { notify(NotificationCopy.receipt(r)) }
            // The success/warning haptic plays in ReceiptScreen, at the moment the slot lights.
        }
        if seen == nil || (r != nil && rid > (seen ?? 0)) { defaults.set(NSNumber(value: rid), forKey: "seen_order") }
    }

    var statusLine: String {
        switch store {
        case let .inStore(_, name, _, leaving):
            if leaving { return "Leaving \(name)…" }
            return count > 0 ? "\(name) · \(money(total)) in your bag · just walk out" : "Shopping at \(name)"
        case .away: return "Not in a Bag It store"
        default: return "Watching your bag"
        }
    }

    // MARK: bag

    private func onBag(_ s: BagState) {
        guard !demoBag else { return }          // the demo bag stands in for Bluetooth
        setBag(s)
    }

    private func setBag(_ s: BagState) {
        let before = bag
        bag = s
        watchBag(before: before)
        if s.isConnected != before.isConnected || (s.isConnected && s != before) {
            let name: String
            if case let .connected(n) = s { name = n } else { name = "" }
            enqueue { [unowned self] in self.apply(try await self.api.setBag(name), fromLocation: false) }
        }
    }

    func connectBag() { if !demoBag { link.connect() } }

    /// The Bluetooth name of the bag connected right now (nil for the demo bag or none).
    var connectedBagId: String? {
        if demoBag { return SmartBagConfig.demoBagId }
        if case let .connected(n) = bag { return n }
        return nil
    }

    private func onLine(_ raw: String) {
        // "BAG <name> fw2" answers HELLO during pairing: a control line, never an item.
        if BagProtocol.isControl(raw) { return }
        guard started else { return }            // nothing counts before setup is done and the shop is running
        let ev = BagProtocol.parse(raw)
        enqueue { [unowned self] in
            self.apply(try await self.api.bagLine(raw), fromLocation: false)
            guard let ev else { return }
            if ev.op == "add" { self.flashN += 1; self.flash = Flash(sku: ev.sku, n: self.flashN) }
            self.link.send(BagProtocol.ack(for: ev, in: self.lines))
        }
    }

    func dismissLine(_ sku: String) {
        enqueue { [unowned self] in self.apply(try await self.api.remove(sku: sku), fromLocation: false) }
    }

    /// Hidden demo aid (long-press the bag chip): behaves exactly as if the bag had read a random item.
    func demoDrop() { onLine("+\(Int.random(in: 1001 ... 1016))@DEMO\(Int.random(in: 1000 ... 9999))") }

    // MARK: location

    private func onLocationStatus(_ s: PhoneLocation.Status) {
        preciseLocationOff = (s == .reducedAccuracy)
        if s == .denied, inStore == nil { store = .noLocation }
    }

    private func onFix(_ l: CLLocation) {
        if Date() < ignoreFixesUntil { return }
        fix = l
        if store == .noLocation { store = .locating }
        if forcedFixes > 0 { sendForced(); return }
        let age = Date().timeIntervalSince(sentTime)
        // Inside a store we report often, so leaving is noticed within seconds.
        let due = sentAt == nil || l.distance(from: sentAt!) > 10 || age > (inStore != nil ? 8 : 20)
        if due { refreshStore() }
    }

    func refreshStore() {
        guard let l = fix, !locating else { return }
        locating = true
        sentAt = l
        sentTime = Date()
        enqueue { [unowned self] in
            defer { self.locating = false }
            self.apply(try await self.api.locate(lat: l.coordinate.latitude, lng: l.coordinate.longitude,
                                                 accuracy: max(0, l.horizontalAccuracy)), fromLocation: true)
        }
    }

    /// Hidden demo aid (long-press the store name): reports two accurate fixes 300 m north of where the
    /// phone is, exactly what walking out produces, then pauses real GPS for a minute.
    func demoWalkOut() {
        guard let l = fix else { return }
        ignoreFixesUntil = Date().addingTimeInterval(60)
        let lat = l.coordinate.latitude + 300.0 / 111_320.0, lng = l.coordinate.longitude
        enqueue { [unowned self] in self.apply(try await self.api.locate(lat: lat, lng: lng, accuracy: 8), fromLocation: true) }
        enqueue { [unowned self] in self.apply(try await self.api.locate(lat: lat + 0.0002, lng: lng, accuracy: 8), fromLocation: true) }
    }

    func finishReceipt() { paid = nil }
}
