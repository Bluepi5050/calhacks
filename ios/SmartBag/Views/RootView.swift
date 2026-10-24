// RootView.swift: where the app is. Signed out -> welcome / create account / log in. Signed in with setup left ->
// permissions, pair your bag, add a card (each skippable but permissions). Otherwise the four tabs:
// Shop · Trips · Wallet · Account. Receipts and setup flows cover the tab bar.

import SwiftUI

struct RootView: View {
    @EnvironmentObject var m: ShopModel
    @EnvironmentObject var account: AccountModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private enum Route: Equatable { case signedOut, setup, tabs }
    private var route: Route { !account.signedIn ? .signedOut : (account.setup.isEmpty ? .tabs : .setup) }

    var body: some View {
        ZStack {
            switch route {
            case .signedOut: AuthFlow().transition(.opacity)
            case .setup: SetupFlow().transition(.opacity)
            case .tabs: MainTabs().transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: route)
        .onChange(of: account.signedIn) { signedIn in
            if !signedIn { StoreRegions.shared.stop(); AppRouter.shared.tab = 0; AppRouter.shared.pendingCode = nil }
        }
    }
}

/// After sign-up or log-in: only what this phone and account are missing, in order.
struct SetupFlow: View {
    @EnvironmentObject var account: AccountModel

    var body: some View {
        if let step = account.setup.first {
            let plan = account.setupPlan.isEmpty ? account.setup : account.setupPlan
            let bar = ((plan.firstIndex(of: step) ?? 0) + 1, plan.count)
            Group {
                switch step {
                case .permissions:
                    PermissionSteps(step: bar) {
                        UserDefaults.standard.set(true, forKey: "onboarded")
                        account.finishStep(.permissions)
                    }
                case .pair:
                    PairingFlow(step: bar) { account.finishStep(.pair) }
                case .card:
                    AddCardScreen(step: bar) { account.finishStep(.card) }
                }
            }
            .id(step)
            .transition(.opacity)
        }
    }
}

struct MainTabs: View {
    @EnvironmentObject var m: ShopModel
    @EnvironmentObject var account: AccountModel
    @ObservedObject private var router = AppRouter.shared

    init() { TabBarStyle.apply() }

    var body: some View {
        TabView(selection: $router.tab) {
            ShopView()
                .tabItem { Label("Shop", systemImage: "bag").environment(\.symbolVariants, .none) }.tag(0)
            TripsView()
                .tabItem { Label("Trips", systemImage: "list.bullet.rectangle.portrait").environment(\.symbolVariants, .none) }.tag(1)
            WalletView()
                .tabItem { Label("Wallet", systemImage: "creditcard").environment(\.symbolVariants, .none) }.tag(2)
            AccountView()
                .tabItem { Label("Account", systemImage: "person").environment(\.symbolVariants, .none) }.tag(3)
        }
        .onAppear {
            UserDefaults.standard.set(true, forKey: "onboarded")
            m.start()
            StoreRegions.shared.activate()
            PushRegistrar.shared.start()
        }
        .task { await account.refresh() }
    }
}
