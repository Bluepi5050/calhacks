// StoreRegions.swift: background walk-out detection when Bag It isn't running (iOS region monitoring).
//
// With "Always" location, the phone monitors a circle around each of the 20 nearest Bag It stores (iOS's limit;
// radius = store radius + 40 m) and refreshes the set on significant location changes. Crossing a circle makes iOS
// launch or wake the app in the background: it then reports two fresh fixes a few seconds apart, which is what the
// module needs to count a walk-out and charge the bag, and the receipt notification comes from that reply.
//
// Honest limits: needs Always (with While Using the app must stay open while you leave); iOS decides when region
// events arrive (typically within a minute, sometimes later); if the shopper force-quits Bag It, iOS may not wake it
// at all. Background time is short: on expiry the check stops asking for fixes and the task ends cleanly.

import Foundation
import CoreLocation
import UIKit

/// The background task id, ended exactly once (by the check finishing or by expiry).
private final class TaskBox {
    var id: UIBackgroundTaskIdentifier = .invalid
    @MainActor func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}

@MainActor
final class StoreRegions: NSObject, CLLocationManagerDelegate {
    static let shared = StoreRegions()
    private let manager = CLLocationManager()
    private var lastRefresh: (at: Date, where: CLLocation)?
    private var checking = false
    private static let prefix = "store:"

    /// Created in didFinishLaunching so region events that relaunched the app reach a delegate.
    override init() {
        super.init()
        manager.delegate = self
    }

    var available: Bool { CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) }
    var always: Bool { manager.authorizationStatus == .authorizedAlways }
    var monitoredCount: Int { manager.monitoredRegions.filter { $0.identifier.hasPrefix(Self.prefix) }.count }

    /// Signed in and set up: keep the nearest stores monitored (only with Always; otherwise nothing is promised).
    func activate() {
        guard always, available else { stop(); return }
        manager.startMonitoringSignificantLocationChanges()      // delivers a first location soon, then on ~500 m moves
    }

    /// Signed out: stop watching stores.
    func stop() {
        manager.stopMonitoringSignificantLocationChanges()
        for r in manager.monitoredRegions where r.identifier.hasPrefix(Self.prefix) { manager.stopMonitoring(for: r) }
        lastRefresh = nil
    }

    private func refresh(near l: CLLocation) async {
        if let last = lastRefresh, Date().timeIntervalSince(last.at) < 15 * 60, l.distance(from: last.where) < 1000 { return }
        guard let stores = try? await ShopModel.shared.api.nearbyStores(lat: l.coordinate.latitude, lng: l.coordinate.longitude, limit: 20) else { return }
        lastRefresh = (Date(), l)
        let wanted = Dictionary(stores.prefix(20).map { (Self.prefix + $0.id, $0) }, uniquingKeysWith: { a, _ in a })
        for r in manager.monitoredRegions where r.identifier.hasPrefix(Self.prefix) && wanted[r.identifier] == nil { manager.stopMonitoring(for: r) }
        let have = Set(manager.monitoredRegions.map(\.identifier))
        for (id, s) in wanted where !have.contains(id) {
            let radius = min(Double(s.radiusM + 40), manager.maximumRegionMonitoringDistance)
            let region = CLCircularRegion(center: CLLocationCoordinate2D(latitude: s.lat, longitude: s.lng), radius: radius, identifier: id)
            region.notifyOnEntry = true
            region.notifyOnExit = true
            manager.startMonitoring(for: region)
        }
    }

    /// A store circle was crossed: run the exit check inside a background task that ends cleanly when time is up.
    private func crossed() {
        guard !checking, AccountModel.shared.signedIn, AccountModel.shared.setup.isEmpty else { return }
        checking = true
        let shop = ShopModel.shared
        shop.start()                                              // no-op if the session is already running
        let box = TaskBox()
        box.id = UIApplication.shared.beginBackgroundTask(withName: "walk-out check") {
            shop.cancelCheck()                                    // expiry (main thread): stop asking for fixes, end at once
            self.checking = false
            box.end()
        }
        Task { @MainActor in
            await shop.checkNow(fixes: 2, gap: 3, timeout: 20)
            self.checking = false
            box.end()
        }
    }

    // MARK: CLLocationManagerDelegate (delivered on the main thread: the manager was created there)

    nonisolated func locationManager(_ m: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last else { return }
        Task { @MainActor in
            let box = TaskBox()                                   // a significant-change wake is short: finish the fetch
            box.id = UIApplication.shared.beginBackgroundTask(withName: "store regions") { box.end() }
            await self.refresh(near: l)
            box.end()
        }
    }
    nonisolated func locationManager(_ m: CLLocationManager, didEnterRegion region: CLRegion) {
        Task { @MainActor in self.crossed() }
    }
    nonisolated func locationManager(_ m: CLLocationManager, didExitRegion region: CLRegion) {
        Task { @MainActor in self.crossed() }
    }
    nonisolated func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        Task { @MainActor in if AccountModel.shared.signedIn { self.activate() } }
    }
    nonisolated func locationManager(_ m: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {}
    nonisolated func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {}
}
