// WelcomeView.swift: the signed-out welcome (Create account / I already have an account), and the permission
// priming screens that open setup: one screen per permission whose button triggers that single system prompt
// (Bluetooth, Location, Notifications).

import SwiftUI
import UserNotifications

/// Signed out: the promise, three beats, then an account. Payments are simulated; the card comes from the wallet.
struct WelcomeScreen: View {
    let onCreate: () -> Void
    let onLogIn: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Lockup(height: 32).padding(.top, Space.xxxl)
                    Text("The bag that\nchecks you out.")
                        .font(TypeScale.display).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                        .padding(.top, Space.xxl)
                    Text("No lines. No scanning.")
                        .font(TypeScale.body).foregroundColor(Theme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, Space.m)
                    VStack(alignment: .leading, spacing: Space.l) {
                        Beat(n: 1, title: "Drop it in", why: "Put groceries in your bag as you shop.")
                        Beat(n: 2, title: "It knows", why: "Each item shows up here with the store's price.")
                        Beat(n: 3, title: "Walk out", why: "Your card is charged as you leave the store.")
                    }
                    .padding(.top, Space.xxl)
                    Text("Demo: payments are simulated. No money moves.")
                        .font(TypeScale.caption).foregroundColor(Theme.faint)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, Space.xxl)
                }
                .padding(.horizontal, Space.margin)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            VStack(spacing: Space.xs) {
                Button("Create account", action: onCreate).buttonStyle(PillStyle())
                Button("I already have an account", action: onLogIn).buttonStyle(TextAction())
            }
            .padding(.horizontal, Space.margin)
            .padding(.top, Space.l)
            .padding(.bottom, Space.s)
        }
        .background(Theme.bg.ignoresSafeArea())
    }
}

/// Setup step 1: one priming screen per permission, each right before its single system prompt.
struct PermissionSteps: View {
    let step: (Int, Int)
    let onDone: () -> Void
    @EnvironmentObject var m: ShopModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var page = 0                  // 0 Bluetooth, 1 Location, 2 All the time, 3 Notifications
    @State private var advancing = false         // a double tap must not skip a screen

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            FlowBar(step: step).padding(.top, Space.s)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: Space.xxxl)
                    content
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .id(page)
            .transition(reduceMotion ? .opacity : .asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity),
                                                              removal: .opacity))
            buttons.padding(.top, Space.l).disabled(advancing)
        }
        .padding(.horizontal, Space.margin)
        .padding(.bottom, Space.s)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.bg.ignoresSafeArea())
    }

    @ViewBuilder private var content: some View {
        switch page {
        case 0:
            Prime(art: AnyView(SlotMark(size: 48, lit: 0).padding(.leading, -6)),   // the slot first lights when a real bag connects
                  title: "Connect your bag",
                  why: "Bag It uses Bluetooth to hear what you drop in your bag.")
        case 1:
            Prime(art: AnyView(Symbol(name: "location")),
                  title: "Find your store",
                  why: "Your location tells us which store you're in, and when you leave so we can charge your bag.",
                  tip: "Choose Allow While Using App with Precise Location on.")
        case 2:
            Prime(art: AnyView(Symbol(name: "location")),
                  title: "Pay with the app closed",
                  why: "Allow Bag It to use your location all the time so leaving a store pays even when the app is closed.",
                  tip: "Choose Change to Always Allow. If you force-quit Bag It, iOS may not wake it, so leave it running in the background. You can change this later in Account › Notifications.")
        default:
            Prime(art: AnyView(Symbol(name: "bell")),
                  title: "Get your receipt",
                  why: "We'll send it the moment you walk out.")
        }
    }

    @ViewBuilder private var buttons: some View {
        VStack(spacing: Space.s) {
            switch page {
            case 0:
                Button("Allow Bluetooth") { m.link.requestAccess(); next(after: 0.4) }.buttonStyle(PillStyle())
            case 1:
                Button("Allow Location") { m.location.start(); next(after: 0.4) }.buttonStyle(PillStyle())
            case 2:
                Button("Allow all the time") {
                    // iOS shows this prompt only after While Using; otherwise just move on.
                    next(after: m.location.requestAlways() ? 0.4 : 0)
                }
                .buttonStyle(PillStyle())
                Button("Not now") { next() }.buttonStyle(PillStyle(primary: false))
            default:
                Button("Allow Notifications") {
                    advancing = true
                    UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
                        DispatchQueue.main.async { onDone() }
                    }
                }
                .buttonStyle(PillStyle())
                Button("Not now") { advancing = true; onDone() }.buttonStyle(PillStyle(primary: false))
            }
        }
    }

    /// Advances after the system prompt has had time to appear, so the next screen waits behind it.
    private func next(after delay: Double = 0) {
        guard !advancing else { return }
        advancing = true
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            withAnimation(.easeInOut(duration: 0.3)) { page += 1 }
            advancing = false
        }
    }
}

private struct Beat: View {
    let n: Int
    let title: String
    let why: String
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.l) {
            Text("\(n)").font(TypeScale.body.monospacedDigit()).foregroundColor(Theme.faint).frame(width: 16, alignment: .leading)
            VStack(alignment: .leading, spacing: Space.xs) {
                Text(title).font(TypeScale.bodyStrong).foregroundColor(Theme.ink)
                Text(why).font(TypeScale.caption).foregroundColor(Theme.muted).fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct Symbol: View {
    let name: String
    var body: some View {
        Image(systemName: name).symbolVariant(.fill).font(.system(.largeTitle)).foregroundColor(Theme.ink)
            .frame(minWidth: 48, minHeight: 48, alignment: .leading).accessibilityHidden(true)
    }
}

private struct Prime: View {
    let art: AnyView
    let title: String
    let why: String
    var tip: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            art
            Text(title).font(TypeScale.display).foregroundColor(Theme.ink)
                .accessibilityAddTraits(.isHeader).padding(.top, Space.xxl)
            Text(why).font(TypeScale.body).foregroundColor(Theme.muted)
                .fixedSize(horizontal: false, vertical: true).padding(.top, Space.m)
            if let tip {
                Text(tip).font(TypeScale.caption).foregroundColor(Theme.muted)
                    .fixedSize(horizontal: false, vertical: true).padding(.top, Space.l)
            }
        }
    }
}
