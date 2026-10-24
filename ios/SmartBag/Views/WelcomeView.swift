import SwiftUI

struct WelcomeView: View {
    let onStart: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 24)
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Theme.ink)
                .frame(width: 64, height: 64)
                .overlay(AppMark().frame(width: 42, height: 42))
            Text("Skip the checkout line")
                .font(.system(size: 30, weight: .semibold))
                .foregroundColor(Theme.ink)
                .padding(.top, 28)
            Text("Drop groceries in your SmartBag and just walk out. Your card is charged the moment you leave the store.")
                .font(.system(size: 16))
                .lineSpacing(4)
                .foregroundColor(Theme.muted)
                .padding(.top, 12)
            VStack(alignment: .leading, spacing: 14) {
                Need(title: "Bluetooth", why: "so your bag can tell us what's inside")
                Need(title: "Location", why: "so we know which store you're in, and when you've left. Choose “Always” so it works with your phone in your pocket.")
                Need(title: "Notifications", why: "for your receipt when you walk out")
            }
            .padding(.top, 32)
            Spacer(minLength: 40)
            Pill(title: "Get started", action: onStart)
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(Theme.bg.ignoresSafeArea())
    }
}

/// The mark as it appears on the dark app tile (light body, like the launcher icon).
struct AppMark: View {
    var body: some View {
        GeometryReader { g in
            let s = min(g.size.width, g.size.height)
            let style = StrokeStyle(lineWidth: 1.5 * s / 24, lineCap: .round, lineJoin: .round)
            ZStack {
                BagBodyShape().stroke(Theme.bg, style: style)
                BagHandleShape().stroke(Theme.faint, style: style)
                BagCheckShape().stroke(Theme.accent, style: style)
            }
        }
        .accessibilityHidden(true)
    }
}

private struct Need: View {
    let title: String
    let why: String
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Dot(color: Theme.accent, size: 6).padding(.top, 7)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .medium)).foregroundColor(Theme.ink)
                Text(why).font(.system(size: 14)).foregroundColor(Theme.muted).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
