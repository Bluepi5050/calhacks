// NotificationSettingsView.swift: Account › Notifications. Four kinds with switches (all on by default; stored on
// this phone, trips and security also sent to the server when this phone is registered for push), the system
// permission, background location, and a test. Plain list in the 1.2 design system. Honest about iOS: with While
// Using the app must stay open while you leave, and a force-quit app may not be woken at all.

import SwiftUI
import CoreLocation
import UserNotifications

struct NotificationSettingsView: View {
    let onClose: () -> Void
    @EnvironmentObject var m: ShopModel
    @ObservedObject private var push = PushRegistrar.shared
    @Environment(\.scenePhase) private var phase
    @State private var prefs = NotificationPrefs.load()
    @State private var system: UNAuthorizationStatusWord = .unknown
    @State private var location: CLAuthorizationStatus = .notDetermined
    @State private var testResult: String?
    @State private var testing = false

    var body: some View {
        VStack(spacing: 0) {
            FlowBar(close: onClose, closeLabel: "Done").padding(.horizontal, Space.margin).padding(.top, Space.s)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ScreenTitle(title: "Notifications").padding(.top, Space.l)

                    SectionLabel(text: "Tell me about").padding(.top, Space.xxl)
                    toggle("Trips and receipts", "When a walk-out is charged, or payment is due.", \.trips)
                    toggle("Store arrivals", "Once when you walk into a Bag It store.", \.store)
                    toggle("Bag alerts", "If your bag disconnects while you shop.", \.bag)
                    toggle("Account security", push.state?.serverPush == true && push.state?.registered == true
                           ? "Sign-ins and new cards on your other phones."
                           : "Sign-ins and new cards on your other phones. Needs server push, which isn't set up yet.", \.security)

                    SectionLabel(text: "On this phone").padding(.top, Space.xxl)
                    statusRow("Notifications", system.word, problem: system == .off,
                              action: system == .notAsked ? ("Turn on", askNotifications) : (system == .off ? ("Open Settings", openAppSettings) : nil))
                    statusRow("Background location", locationWord, problem: location != .authorizedAlways,
                              action: locationAction)
                    Text(locationNote)
                        .font(TypeScale.caption).foregroundColor(Theme.muted).fixedSize(horizontal: false, vertical: true)
                        .padding(.top, Space.m)

                    Button {
                        Task { testing = true; testResult = await push.test(); testing = false }
                    } label: {
                        ZStack { Text("Send a test notification").opacity(testing ? 0 : 1); if testing { ProgressView().tint(Theme.ink) } }
                    }
                    .buttonStyle(PillStyle(primary: false))
                    .disabled(testing || system != .on)
                    .padding(.top, Space.xxl)
                    if let testResult {
                        Text(testResult).font(TypeScale.caption).foregroundColor(Theme.muted).padding(.top, Space.s)
                    } else if system != .on {
                        Text("Turn notifications on to send a test.").font(TypeScale.caption).foregroundColor(Theme.faint).padding(.top, Space.s)
                    }
                }
                .padding(.horizontal, Space.margin)
                .padding(.bottom, Space.xxl)
            }
        }
        .background(Theme.bg.ignoresSafeArea())
        .task { await read(); await push.refreshStatus() }
        .onChange(of: phase) { p in if p == .active { Task { await read() } } }
    }

    private func toggle(_ title: String, _ why: String, _ key: WritableKeyPath<NotificationPrefs, Bool>) -> some View {
        VStack(spacing: 0) {
            Toggle(isOn: Binding(get: { prefs[keyPath: key] }, set: { v in
                prefs[keyPath: key] = v
                prefs.save()
                Task { await push.prefsChanged() }
            })) {
                VStack(alignment: .leading, spacing: Space.xs) {
                    Text(title).font(TypeScale.body).foregroundColor(Theme.ink)
                    Text(why).font(TypeScale.caption).foregroundColor(Theme.muted).fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(Theme.ink)
            .padding(.vertical, Space.m)
            .frame(minHeight: 56)
            Hairline()
        }
    }

    private func statusRow(_ title: String, _ word: String, problem: Bool, action: (String, () -> Void)?) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: Space.m) {
                VStack(alignment: .leading, spacing: Space.xs) {
                    Text(title).font(TypeScale.body).foregroundColor(Theme.ink)
                    Text(word).font(TypeScale.caption).foregroundColor(problem ? Theme.bad : Theme.muted)
                }
                Spacer(minLength: Space.s)
                if let action { Button(action.0, action: action.1).buttonStyle(InlineAction()).padding(.trailing, -Space.s) }
            }
            .padding(.vertical, Space.m)
            .frame(minHeight: 56)
            .accessibilityElement(children: .combine)
            Hairline()
        }
    }

    private var locationWord: String {
        switch location {
        case .authorizedAlways: return "Always"
        case .authorizedWhenInUse: return "While using"
        case .notDetermined: return "Not asked"
        default: return "Off"
        }
    }

    private var locationNote: String {
        switch location {
        case .authorizedAlways:
            return "Leaving a store pays even when Bag It is in the background. iOS may not wake Bag It if you force-quit it, so leave it running."
        case .authorizedWhenInUse:
            return "With While using, keep Bag It open while you leave so the walk-out is noticed."
        default:
            return "Bag It needs your location to find your store and to notice when you leave."
        }
    }

    private var locationAction: (String, () -> Void)? {
        switch location {
        case .authorizedAlways: return nil
        case .authorizedWhenInUse:
            return ("Allow all the time", {
                if !m.location.requestAlways() { openAppSettings() }        // iOS asks once; after that only Settings can
            })
        default: return ("Open Settings", openAppSettings)
        }
    }

    private func askNotifications() {
        Task { _ = await Notify.shared.requestPermission(); await read(); PushRegistrar.shared.start() }
    }

    private func read() async {
        system = UNAuthorizationStatusWord(await Notify.shared.status())
        location = m.location.authorization
    }
}

/// The system notification permission in the words the screen uses.
enum UNAuthorizationStatusWord: Equatable {
    case unknown, on, off, notAsked
    init(_ s: UNAuthorizationStatus) {
        switch s {
        case .authorized, .provisional, .ephemeral: self = .on
        case .notDetermined: self = .notAsked
        default: self = .off
        }
    }
    var word: String {
        switch self { case .on: return "On"; case .off: return "Off"; case .notAsked: return "Not asked"; case .unknown: return "…" }
    }
}
