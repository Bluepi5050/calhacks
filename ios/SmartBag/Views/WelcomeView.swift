// WelcomeView.swift: first run. One welcome screen, then one priming screen per permission whose button
// triggers that single system prompt (Bluetooth, Location, Notifications), then the shop.

import SwiftUI
import UserNotifications

struct WelcomeView: View {
    let onStart: () -> Void
    @EnvironmentObject var m: ShopModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var step = 0                  // 0 welcome, 1 Bluetooth, 2 Location, 3 Notifications
    @State private var advancing = false         // a double tap must not skip a screen

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if step > 0 { StepBar(step: step).padding(.top, Space.l) }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: Space.xxxl)
                    page
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .id(step)
            .transition(reduceMotion ? .opacity : .asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity),
                                                              removal: .opacity))
            buttons.padding(.top, Space.l).disabled(advancing)
        }
        .padding(.horizontal, Space.margin)
        .padding(.bottom, Space.l)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.bg.ignoresSafeArea())
    }

    @ViewBuilder private var page: some View {
        switch step {
        case 0:
            Lockup(height: 32)
            Text("The bag that\nchecks you out.")
                .font(TypeScale.display).foregroundColor(Theme.ink)
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
            Text("Demo: payments are simulated with \(unbreakable(SmartBagConfig.demoPayment)).")
                .font(TypeScale.caption).foregroundColor(Theme.faint)
                .accessibilityLabel("Demo: payments are simulated with \(spoken(SmartBagConfig.demoPayment)).")
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, Space.xxl)
        case 1:
            Prime(art: AnyView(SlotMark(size: 48, lit: 0).padding(.leading, -6)),   // the slot first lights when a real bag connects
                  title: "Connect your bag",
                  why: "Bag It uses Bluetooth to hear what you drop in your bag.")
        case 2:
            Prime(art: AnyView(Symbol(name: "location")),
                  title: "Find your store",
                  why: "Your location tells us which store you're in, and when you leave so we can charge your bag.",
                  tip: "Choose Allow While Using App with Precise Location on. Later, choose Change to Always Allow.")
        default:
            Prime(art: AnyView(Symbol(name: "bell")),
                  title: "Get your receipt",
                  why: "We'll send it the moment you walk out.")
        }
    }

    @ViewBuilder private var buttons: some View {
        VStack(spacing: Space.s) {
            switch step {
            case 0:
                Button("Get started") { next() }.buttonStyle(PillStyle())
            case 1:
                Button("Allow Bluetooth") { m.connectBag(); next(after: 0.4) }.buttonStyle(PillStyle())
            case 2:
                Button("Allow Location") { m.location.start(); next(after: 0.4) }.buttonStyle(PillStyle())
            default:
                Button("Allow Notifications") {
                    advancing = true
                    UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
                        DispatchQueue.main.async { onStart() }
                    }
                }
                .buttonStyle(PillStyle())
                Button("Not now") { advancing = true; onStart() }.buttonStyle(PillStyle(primary: false))
            }
        }
    }

    /// Advances after the system prompt has had time to appear, so the next screen waits behind it.
    private func next(after delay: Double = 0) {
        guard !advancing else { return }
        advancing = true
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            withAnimation(.easeInOut(duration: 0.3)) { step += 1 }
            advancing = false
        }
    }
}

private struct StepBar: View {
    let step: Int
    var body: some View {
        HStack(spacing: Space.xs) {
            ForEach(1 ... 3, id: \.self) { i in
                Capsule().fill(i <= step ? Theme.ink : Theme.hair).frame(width: 24, height: 4)
            }
        }
        .accessibilityElement()
        .accessibilityLabel("Step \(step) of 3")
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
