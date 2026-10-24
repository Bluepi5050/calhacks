// SmartBagApp.swift: customer-only SmartBag app. No store picker, no catalog, no Pay button:
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
                } else if let r = model.paid {
                    ReceiptScreen(receipt: r) { model.finishReceipt() }
                        .transition(.move(edge: .bottom))
                } else {
                    ShopView()
                }
            }
            .environmentObject(model)
            .preferredColorScheme(.light)
            .tint(Theme.accent)
            .animation(.easeInOut(duration: 0.25), value: model.paid?.orderId)
        }
        .onChange(of: phase) { p in
            if p == .active && onboarded { model.start(); model.foreground() }
        }
    }
}
