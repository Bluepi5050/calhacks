// Minimal stand-ins for the iOS-only types ShopModel touches, so ShopModel.swift itself can be
// typechecked and driven on Linux. They mirror the real signatures used by ShopModel; they are NOT
// part of the app. BagLink and PhoneLocation are fakes the harness drives directly.
import Foundation

struct CLLocationCoordinate2D { var latitude: Double; var longitude: Double }
class CLLocation {
    let coordinate: CLLocationCoordinate2D
    let horizontalAccuracy: Double
    init(lat: Double, lng: Double, acc: Double) { coordinate = .init(latitude: lat, longitude: lng); horizontalAccuracy = acc }
    func distance(from o: CLLocation) -> Double {
        let r = 6_371_000.0, p = Double.pi / 180
        let dLat = (o.coordinate.latitude - coordinate.latitude) * p, dLng = (o.coordinate.longitude - coordinate.longitude) * p
        let a = sin(dLat / 2) * sin(dLat / 2) + cos(coordinate.latitude * p) * cos(o.coordinate.latitude * p) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(a))
    }
}
final class UINotificationFeedbackGenerator {
    enum FeedbackType { case success, warning, error }
    func notificationOccurred(_ t: FeedbackType) {}
}
final class ObservableObjectStub {}
protocol ObservableObject: AnyObject {}
@propertyWrapper struct Published<Value> {
    var wrappedValue: Value
    init(wrappedValue: Value) { self.wrappedValue = wrappedValue }
}

final class BagLink {
    let onLine: (String) -> Void
    let onState: (BagState) -> Void
    var sent: [String] = []
    var allowed: Set<String>?? = .none          // .none = never set
    var connects = 0
    init(onLine: @escaping (String) -> Void, onState: @escaping (BagState) -> Void) { self.onLine = onLine; self.onState = onState }
    func connect() { connects += 1; onState(.connected("SmartBag-Harness")) }
    func disconnect() { onState(.off) }
    func setAllowed(_ names: Set<String>?) { allowed = .some(names) }
    func send(_ message: String) { sent.append(message) }
}

final class PhoneLocation {
    enum Status: Equatable { case unknown, denied, reducedAccuracy, ok }
    let onFix: (CLLocation) -> Void
    let onStatus: (Status) -> Void
    init(onFix: @escaping (CLLocation) -> Void, onStatus: @escaping (Status) -> Void) { self.onFix = onFix; self.onStatus = onStatus }
    var running = false
    func start() { running = true; onStatus(.ok) }
    func stop() { running = false }
}

final class Notify {
    static let shared = Notify()
    var posted: [Receipt] = []
    func requestPermission() {}
    func paid(_ r: Receipt) { posted.append(r) }
}

final class KeychainTokenStore: TokenStore, @unchecked Sendable {
    var token: String?
}
