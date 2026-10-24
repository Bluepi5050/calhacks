// Push.swift: optional server push (Firebase Cloud Messaging) for what this phone can't see itself: a trip charged
// on another phone of the account, a sign-in or a card added elsewhere. Local notifications cover everything on
// this phone and need none of this.
//
// OFF by default and the app builds without it. It turns on only when BOTH are true:
//   1. the FirebaseMessaging Swift package is added to the target (File › Add Package Dependencies…,
//      https://github.com/firebase/firebase-ios-sdk, product FirebaseMessaging), and
//   2. GoogleService-Info.plist (from Scott's Firebase project) is in the app bundle.
// Remote notifications also need a paid Apple Developer account: the Push Notifications capability
// (aps-environment entitlement) and an APNs auth key uploaded to Firebase. A free Personal Team cannot sign that
// entitlement, so it is not in the project by default. Nothing here has been tested on a device.

import Foundation
import UIKit
#if canImport(FirebaseCore) && canImport(FirebaseMessaging)
import FirebaseCore
import FirebaseMessaging
#endif

@MainActor
final class PushRegistrar: NSObject, ObservableObject {
    static let shared = PushRegistrar()
    @Published private(set) var state: PushState?           // from push_status / push_register
    private var token: String?

    /// Firebase is compiled in and its plist is bundled.
    static var configured: Bool {
        #if canImport(FirebaseCore) && canImport(FirebaseMessaging)
        return Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil
        #else
        return false
        #endif
    }

    /// App launch (signed in): set up Firebase if present, ask for an APNs token once notifications are allowed.
    func start() {
        #if canImport(FirebaseCore) && canImport(FirebaseMessaging)
        guard Self.configured else { return }
        if FirebaseApp.app() == nil { FirebaseApp.configure() }
        Messaging.messaging().delegate = self
        if let token { Task { await register(token) } }          // a token that arrived while signed out
        Task {
            if await Notify.shared.status() == .authorized { UIApplication.shared.registerForRemoteNotifications() }
        }
        #endif
    }

    /// From the app delegate.
    func apnsToken(_ data: Data) {
        #if canImport(FirebaseCore) && canImport(FirebaseMessaging)
        Messaging.messaging().apnsToken = data
        #endif
    }

    /// FCM token (new or refreshed): register this phone with the account. Idempotent on the server.
    func register(_ fcm: String) async {
        token = fcm
        guard AccountModel.shared.signedIn else { return }
        state = try? await ShopModel.shared.api.pushRegister(token: fcm, prefs: NotificationPrefs.load().server)
    }

    /// Server status without registering (works without Firebase: reports whether the module has push configured).
    func refreshStatus() async { state = try? await ShopModel.shared.api.pushStatus() }

    /// Preferences changed: the server learns trips / security only if this phone is registered.
    func prefsChanged() async {
        guard state?.registered == true else { return }
        state = try? await ShopModel.shared.api.pushSetPrefs(NotificationPrefs.load().server)
    }

    /// "Send a test notification": a server push when this phone is registered and the module has push set up,
    /// otherwise a local one. Returns a short line for the screen.
    func test() async -> String {
        if let s = state, s.serverPush, s.registered {
            let r = (try? await ShopModel.shared.api.pushTest()) ?? "failed"
            if r.hasPrefix("sent 1") { return "Sent. It should arrive in a few seconds." }
            Notify.shared.post(NotificationCopy.test())
            return "The server couldn't send it (\(r)). Sent one from this phone instead."
        }
        Notify.shared.post(NotificationCopy.test())
        return "Sent from this phone."
    }
}

#if canImport(FirebaseCore) && canImport(FirebaseMessaging)
extension PushRegistrar: MessagingDelegate {
    nonisolated func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let fcmToken else { return }
        Task { @MainActor in await PushRegistrar.shared.register(fcmToken) }
    }
}
#endif
