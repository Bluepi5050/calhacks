// SmartBagApp.swift: Bag It, the customer SmartBag app. An account holds the wallet and the paired bags; the
// database finds the store from the phone's location and charges the default card when the phone leaves.
// There is no Pay button.

import SwiftUI
import UIKit

@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        Notify.shared.install()
        _ = StoreRegions.shared              // region events that relaunched the app need a delegate right away
        // Also covers background relaunches (significant location change, Bluetooth state restoration): a signed-in,
        // set-up phone restarts the session at once so the walk-out is still noticed. The cached account (loaded by
        // AccountModel.shared) tells Bluetooth which bags are this account's before it reconnects.
        // Synchronously, as Core Bluetooth state restoration requires.
        let account = AccountModel.shared
        if account.signedIn && account.setup.isEmpty && UserDefaults.standard.bool(forKey: "onboarded") {
            ShopModel.shared.start()
            PushRegistrar.shared.start()
        }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        PushRegistrar.shared.apnsToken(deviceToken)
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // No push entitlement (free Personal Team) or no network: server push stays off; local notifications still work.
    }
}

@main
struct SmartBagApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = ShopModel.shared
    @StateObject private var account = AccountModel.shared
    @StateObject private var router = AppRouter.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(account)
                .environmentObject(router)
                .tint(Theme.ink)
        }
        .onChange(of: phase) { p in
            model.setForeground(p == .active)
            guard p == .active, account.signedIn else { return }
            Task { await account.refresh() }
            if account.setup.isEmpty { model.start(); model.foreground() }
        }
    }
}
