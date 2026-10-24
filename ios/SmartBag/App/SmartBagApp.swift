// SmartBagApp.swift: Bag It, the customer-only SmartBag app. No store picker, no catalog, no Pay button:
// the database finds the store from the phone's location and charges the bag when the phone leaves.

import SwiftUI
import UIKit

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        Notify.shared.install()
        // Also covers background relaunches (significant location change, Bluetooth state restoration):
        // once onboarded, the session restarts immediately so the walk-out is still noticed.
        if UserDefaults.standard.bool(forKey: "onboarded") {
            Task { @MainActor in ShopModel.shared.start() }
        }
        return true
    }
}

@main
struct SmartBagApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = ShopModel.shared
    @AppStorage("onboarded") private var onboarded = false
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            Group {
                if !onboarded {
                    WelcomeView {
                        onboarded = true
                        model.start()
                    }
                } else {
                    ShopView()
                }
            }
            .environmentObject(model)
            .tint(Theme.ink)                 // the walk-out receipt is presented by ShopView
        }
        .onChange(of: phase) { p in
            if p == .active && onboarded { model.start(); model.foreground() }
        }
    }
}
