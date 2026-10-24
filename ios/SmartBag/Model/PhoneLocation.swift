// PhoneLocation.swift: continuous location, including with the phone locked in a pocket.
//
// Walking out is what pays, so updates keep running in the background (UIBackgroundModes: location,
// allowsBackgroundLocationUpdates, never paused). "While Using" is enough as long as updates were started
// in the foreground (iOS shows the blue location pill); "Always" additionally lets significant-change
// monitoring relaunch the app if iOS terminated it. Runs on the main thread.

import Foundation
import CoreLocation

final class PhoneLocation: NSObject, CLLocationManagerDelegate {
    enum Status: Equatable { case unknown, denied, reducedAccuracy, ok }

    private let manager = CLLocationManager()
    private let onFix: (CLLocation) -> Void
    private let onStatus: (Status) -> Void
    private var running = false
    private var best: CLLocation?

    init(onFix: @escaping (CLLocation) -> Void, onStatus: @escaping (Status) -> Void) {
        self.onFix = onFix
        self.onStatus = onStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .fitness                  // walking: don't snap to roads
        manager.pausesLocationUpdatesAutomatically = false
    }

    /// Safe to call repeatedly: asks for permission the first time, then (re)starts updates.
    func start() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()      // locationManagerDidChangeAuthorization continues
        case .denied, .restricted:
            stop()
            onStatus(.denied)
        default:
            begin()
        }
    }

    private func begin() {
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        if manager.accuracyAuthorization == .reducedAccuracy {
            // Approximate location (kilometres) can't tell which store you're in.
            manager.requestTemporaryFullAccuracyAuthorization(withPurposeKey: "StoreDetection")
            onStatus(.reducedAccuracy)
        } else {
            onStatus(.ok)
        }
        if !running {
            running = true
            manager.startUpdatingLocation()
        }
        if manager.authorizationStatus == .authorizedAlways {
            manager.startMonitoringSignificantLocationChanges()
        }
        // "Always" is asked on its own priming screen (setup) or from Account › Notifications, never by surprise.
    }

    /// iOS shows the "Change to Always Allow" prompt once, after While Using was granted. Returns false when iOS
    /// won't ask again (then only Settings can change it).
    @discardableResult
    func requestAlways() -> Bool {
        guard manager.authorizationStatus == .authorizedWhenInUse, !UserDefaults.standard.bool(forKey: "asked_always") else { return false }
        UserDefaults.standard.set(true, forKey: "asked_always")
        manager.requestAlwaysAuthorization()
        return true
    }

    var authorization: CLAuthorizationStatus { manager.authorizationStatus }
    var precise: Bool { manager.accuracyAuthorization == .fullAccuracy }

    func stop() {
        running = false
        manager.stopUpdatingLocation()
    }

    func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        if m.authorizationStatus == .notDetermined { return }
        start()
    }

    func locationManager(_ m: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for l in locations { consider(l) }
    }

    func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {
        if (error as? CLError)?.code == .denied { stop(); onStatus(.denied) }
    }

    /// Keeps the best recent fix: a newer one replaces it if it is as accurate, or if the old one is stale.
    private func consider(_ l: CLLocation) {
        guard l.horizontalAccuracy >= 0, abs(l.timestamp.timeIntervalSinceNow) < 60 else { return }
        if let b = best, l.timestamp.timeIntervalSince(b.timestamp) <= 20, l.horizontalAccuracy > b.horizontalAccuracy { return }
        best = l
        onFix(l)
    }
}
