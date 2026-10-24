// AccountView.swift: who you are, your bags (rename / unpair, pair another), the three permissions with their
// state and a way to Settings, sign out and delete the account.

import SwiftUI
import CoreBluetooth
import CoreLocation
import UserNotifications

struct AccountView: View {
    @EnvironmentObject var account: AccountModel
    @EnvironmentObject var m: ShopModel
    @Environment(\.scenePhase) private var phase
    @State private var pairing = false
    @State private var openBag: BagInfo?
    @State private var confirmSignOut = false
    @State private var deleting = false
    @State private var signOutError: String?
    @State private var notifications = "…"
    @State private var notificationSettings = false

    var body: some View {
        let s = account.state
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: Space.xs) {
                    Text(s?.name ?? "").font(TypeScale.display).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
                    Text(s?.email ?? "").font(TypeScale.body).foregroundColor(Theme.muted)
                }
                .padding(.top, Space.xl)

                SectionLabel(text: "Your bags").padding(.top, Space.xxl)
                ForEach(s?.bags ?? []) { b in
                    RowButton(action: { openBag = b }) {
                        VStack(alignment: .leading, spacing: Space.xs) {
                            Text(b.name).font(TypeScale.body).foregroundColor(Theme.ink).lineLimit(2)
                            Text(BagProtocol.displayName(b.bagId)).font(TypeScale.caption).foregroundColor(Theme.muted)
                        }
                        Spacer(minLength: Space.s)
                        bagStatus(b)
                    }
                }
                RowButton(action: { pairing = true }) {
                    Text((s?.bags.isEmpty ?? true) ? "Pair your bag" : "Pair a bag").font(TypeScale.body).foregroundColor(Theme.ink)
                    Spacer()
                }

                SectionLabel(text: "Notifications").padding(.top, Space.xxl)
                RowButton(action: { notificationSettings = true }) {
                    Text("Notifications").font(TypeScale.body).foregroundColor(Theme.ink)
                    Spacer()
                    Text(notifications).font(TypeScale.caption).foregroundColor(Theme.muted)
                }
                .accessibilityHint("Choose what Bag It tells you about.")

                SectionLabel(text: "Permissions").padding(.top, Space.xxl)
                permission("Bluetooth", bluetooth)
                permission("Location", location)
                permission("Notifications", (notifications, false))

                VStack(spacing: 0) {
                    Button { confirmSignOut = true } label: {
                        Text("Sign out").font(TypeScale.body).foregroundColor(Theme.ink)
                            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Hairline()
                    Button { deleting = true } label: {
                        Text("Delete account").font(TypeScale.body).foregroundColor(Theme.bad)
                            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Hairline()
                }
                .padding(.top, Space.xxl)
                if let e = signOutError {
                    Text(e).font(TypeScale.caption).foregroundColor(Theme.bad).padding(.top, Space.s)
                }
                Text(version).font(TypeScale.captionNum).foregroundColor(Theme.faint).padding(.top, Space.xl)
            }
            .padding(.horizontal, Space.margin)
            .padding(.bottom, Space.xxl)
        }
        .background(Theme.bg.ignoresSafeArea())
        .confirmationDialog("Sign out of Bag It?", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { signOut() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your cards and bags stay with your account. Until you log in again, this phone won't pay when you walk out.")
        }
        .fullScreenCover(isPresented: $pairing) { PairingFlow { pairing = false }.appModels() }
        .sheet(item: $openBag) { b in BagSheet(bagId: b.id) { openBag = nil }.presentationDetents([.medium, .large]).appModels() }
        .sheet(isPresented: $deleting) { DeleteAccountSheet { deleting = false }.appModels() }
        .sheet(isPresented: $notificationSettings, onDismiss: { Task { await readNotifications() } }) {
            NotificationSettingsView { notificationSettings = false }.appModels()
        }
        .task { await readNotifications() }
        .onChange(of: phase) { p in if p == .active { Task { await readNotifications() } } }
    }

    @ViewBuilder private func bagStatus(_ b: BagInfo) -> some View {
        let connected = b.isDemo || m.connectedBagId == b.bagId
        HStack(spacing: Space.s) {
            if connected { Dot(color: Theme.good) }
            Text(connected ? "Connected" : "Not nearby").font(TypeScale.caption).foregroundColor(Theme.muted)
        }
    }

    private func permission(_ name: String, _ status: (String, Bool)) -> some View {
        RowButton(action: openAppSettings) {
            Text(name).font(TypeScale.body).foregroundColor(Theme.ink)
            Spacer()
            Text(status.0).font(TypeScale.caption).foregroundColor(status.1 ? Theme.bad : Theme.muted)
        }
        .accessibilityLabel("\(name), \(status.0)")
        .accessibilityHint("Opens Settings.")
    }

    /// (word, is a problem)
    private var bluetooth: (String, Bool) {
        switch CBManager.authorization {
        case .allowedAlways: return ("On", false)
        case .notDetermined: return ("Not asked", false)
        default: return ("Off", true)
        }
    }

    private var location: (String, Bool) {
        let lm = CLLocationManager()
        switch lm.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            if lm.accuracyAuthorization == .reducedAccuracy { return ("Approximate", true) }
            return (lm.authorizationStatus == .authorizedAlways ? "Always" : "While using", false)
        case .notDetermined: return ("Not asked", false)
        default: return ("Off", true)
        }
    }

    private func readNotifications() async {
        let s = await UNUserNotificationCenter.current().notificationSettings()
        switch s.authorizationStatus {
        case .authorized, .provisional, .ephemeral: notifications = "On"
        case .notDetermined: notifications = "Not asked"
        default: notifications = "Off"
        }
    }

    private var version: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
        return "Bag It \(v) (\(b))"
    }

    private func signOut() {
        signOutError = nil
        Task {
            do { try await account.signOut() } catch { signOutError = userMessage(error) }
        }
    }
}

/// A paired bag: rename it, or unpair it (confirmed in place).
struct BagSheet: View {
    let bagId: UInt64
    let onClose: () -> Void
    @EnvironmentObject var account: AccountModel
    @State private var name = ""
    @State private var confirming = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            FlowBar(close: onClose, closeLabel: "Done").padding(.top, Space.s)
            if let bag = account.state?.bags.first(where: { $0.id == bagId }) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ScreenTitle(title: bag.name, lead: BagProtocol.displayName(bag.bagId)).padding(.top, Space.l)
                        FormField(label: "Name", text: $name).padding(.top, Space.xl)
                            .onAppear { if name.isEmpty { name = bag.name } }
                        if confirming {
                            Text("Unpair \(bag.name)?").font(TypeScale.bodyStrong).foregroundColor(Theme.ink).padding(.top, Space.xxl)
                            Text(bag.isDemo ? "The demo bag goes away." : "Bag It stops connecting to it. You can pair it again any time.")
                                .font(TypeScale.caption).foregroundColor(Theme.muted)
                                .fixedSize(horizontal: false, vertical: true).padding(.top, Space.xs)
                        }
                        if let error {
                            Text(error).font(TypeScale.caption).foregroundColor(Theme.bad).padding(.top, Space.m)
                        }
                    }
                }
                VStack(spacing: Space.s) {
                    if confirming {
                        Button("Unpair") { run { try await account.unpairBag(bag.id); onClose() } }.buttonStyle(PillStyle(destructive: true))
                        Button("Keep bag") { confirming = false }.buttonStyle(PillStyle(primary: false))
                    } else {
                        Button("Save name") {
                            let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
                            run { try await account.renameBag(bag.id, name: n); onClose() }
                        }
                        .buttonStyle(PillStyle())
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || name == bag.name)
                        Button("Unpair") { confirming = true }.buttonStyle(PillStyle(primary: false, destructive: true))
                    }
                }
                .disabled(busy)
                .padding(.top, Space.m).padding(.bottom, Space.s)
            } else {
                Spacer()
            }
        }
        .padding(.horizontal, Space.margin)
        .background(Theme.bg.ignoresSafeArea())
    }

    private func run(_ op: @escaping () async throws -> Void) {
        busy = true; error = nil
        Task {
            do { try await op() } catch { self.error = userMessage(error) }
            busy = false
        }
    }
}

/// Asks for the password; explains what goes and what stays.
struct DeleteAccountSheet: View {
    let onClose: () -> Void
    @EnvironmentObject var account: AccountModel
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: 0) {
            FlowBar(close: onClose).padding(.horizontal, Space.margin).padding(.top, Space.s)
            ScrollView {
                VStack(alignment: .leading, spacing: Space.xl) {
                    ScreenTitle(title: "Delete account",
                                lead: "This removes your account, your saved cards and your paired bags. Receipts stay with the stores you shopped at.")
                        .padding(.top, Space.l)
                    FormField(label: "Password", text: $password, kind: .password, content: .password)
                }
                .padding(.horizontal, Space.margin)
                .disabled(busy)
            }
        }
        .background(Theme.bg.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: Space.s) {
                if let error {
                    Text(error).font(TypeScale.caption).foregroundColor(Theme.bad).frame(maxWidth: .infinity, alignment: .leading)
                }
                Button {
                    submit()
                } label: {
                    ZStack { Text("Delete account").opacity(busy ? 0 : 1); if busy { ProgressView().tint(Theme.onInk) } }
                }
                .buttonStyle(PillStyle(destructive: true))
                .disabled(busy || password.isEmpty)
            }
            .padding(.horizontal, Space.margin).padding(.top, Space.m).padding(.bottom, Space.s)
            .background(Theme.bg.ignoresSafeArea(edges: .bottom))
        }
        .onDisappear { password = "" }
    }

    private func submit() {
        busy = true; error = nil
        Task {
            do { try await account.deleteAccount(password: password); password = ""; onClose() }
            catch { self.error = userMessage(error); password = "" }
            busy = false
        }
    }
}
