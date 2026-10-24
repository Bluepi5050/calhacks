// SmartBagApp.swift: Bag It, the customer SmartBag app. An account holds the wallet and the paired bags; the
// database finds the store from the phone's location and charges the default card when the phone leaves.
// There is no Pay button.

import SwiftUI
import UIKit

@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        Notify.shared.install()
        // Also covers background relaunches (significant location change, Bluetooth state restoration): a signed-in,
        // set-up phone restarts the session at once so the walk-out is still noticed. The cached account (loaded by
        // AccountModel.shared) tells Bluetooth which bags are this account's before it reconnects.
        // Synchronously, as Core Bluetooth state restoration requires.
        let account = AccountModel.shared
        if account.signedIn && account.setup.isEmpty && UserDefaults.standard.bool(forKey: "onboarded") {
            ShopModel.shared.start()
        }
        return true
    }
}

@main
struct SmartBagApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = ShopModel.shared
    @StateObject private var account = AccountModel.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(account)
                .tint(Theme.ink)
        }
        .onChange(of: phase) { p in
            guard p == .active, account.signedIn else { return }
            Task { await account.refresh() }
            if account.setup.isEmpty { model.start(); model.foreground() }
        }
    }
}
