// Theme.swift: Android 3.0's palette and shared pieces: light zinc neutrals, coral accent, pill buttons.

import SwiftUI
import UIKit

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: opacity)
    }
}

enum Theme {
    static let bg = Color(hex: 0xFAFAF9)
    static let ink = Color(hex: 0x18181B)
    static let muted = Color(hex: 0x71717A)
    static let faint = Color(hex: 0xA1A1AA)
    static let hair = Color(hex: 0xE4E4E7)
    static let accent = Color(hex: 0xE8654A)
    static let good = Color(hex: 0x1F9D63)
    static let bad = Color(hex: 0xD64545)
    static func mono(_ size: CGFloat) -> Font { .system(size: size, design: .monospaced) }
}

/// The SmartBag mark (24-unit grid, same geometry as the Android vector and the Mana app icon).
struct BagMark: View {
    var size: CGFloat = 28
    var body: some View {
        let w = 1.6 * size / 24
        let style = StrokeStyle(lineWidth: w, lineCap: .round, lineJoin: .round)
        ZStack {
            BagBodyShape().stroke(Theme.ink, style: style)
            BagHandleShape().stroke(Theme.faint, style: style)
            BagCheckShape().stroke(Theme.accent, style: style)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

private func scaled(_ rect: CGRect, _ build: (inout Path) -> Void) -> Path {
    var p = Path()
    build(&p)
    let s = min(rect.width, rect.height) / 24
    return p.applying(CGAffineTransform(scaleX: s, y: s).concatenating(CGAffineTransform(translationX: rect.minX, y: rect.minY)))
}

struct BagBodyShape: Shape {
    func path(in rect: CGRect) -> Path {
        scaled(rect) { p in
            p.move(to: CGPoint(x: 4.5, y: 8))
            p.addLine(to: CGPoint(x: 19.5, y: 8))
            p.addLine(to: CGPoint(x: 18.3, y: 20.2))
            p.addQuadCurve(to: CGPoint(x: 17.3, y: 21), control: CGPoint(x: 18.22, y: 21))
            p.addLine(to: CGPoint(x: 6.7, y: 21))
            p.addQuadCurve(to: CGPoint(x: 5.7, y: 20.2), control: CGPoint(x: 5.78, y: 21))
            p.closeSubpath()
        }
    }
}

struct BagHandleShape: Shape {
    func path(in rect: CGRect) -> Path {
        scaled(rect) { p in
            p.move(to: CGPoint(x: 9, y: 8))
            p.addLine(to: CGPoint(x: 9, y: 6.5))
            p.addCurve(to: CGPoint(x: 12, y: 3.5), control1: CGPoint(x: 9, y: 4.843), control2: CGPoint(x: 10.343, y: 3.5))
            p.addCurve(to: CGPoint(x: 15, y: 6.5), control1: CGPoint(x: 13.657, y: 3.5), control2: CGPoint(x: 15, y: 4.843))
            p.addLine(to: CGPoint(x: 15, y: 8))
        }
    }
}

struct BagCheckShape: Shape {
    func path(in rect: CGRect) -> Path {
        scaled(rect) { p in
            p.move(to: CGPoint(x: 9.5, y: 14.5))
            p.addLine(to: CGPoint(x: 11.5, y: 16.5))
            p.addLine(to: CGPoint(x: 15, y: 12.7))
        }
    }
}

struct Caps: View {
    let text: String
    var color: Color = Theme.muted
    init(_ text: String, color: Color = Theme.muted) { self.text = text; self.color = color }
    var body: some View {
        Text(text.uppercased()).font(.system(size: 11, weight: .medium)).tracking(1.8).foregroundColor(color)
    }
}

struct Pill: View {
    let title: String
    var filled = true
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(filled ? .white : Theme.ink)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .padding(.horizontal, 22)
                .background(Capsule().fill(filled ? Theme.accent.opacity(enabled ? 1 : 0.35) : Color.white))
                .overlay(Capsule().stroke(filled ? Color.clear : Theme.hair, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

struct Dashed: View {
    var body: some View {
        GeometryReader { g in
            Path { p in p.move(to: .zero); p.addLine(to: CGPoint(x: g.size.width, y: 0)) }
                .stroke(Theme.hair, style: StrokeStyle(lineWidth: 1, dash: [5, 4]))
        }
        .frame(height: 1)
    }
}

struct Dot: View {
    let color: Color
    var size: CGFloat = 8
    var body: some View { Circle().fill(color).frame(width: size, height: size) }
}

func haptic(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .medium) {
    UIImpactFeedbackGenerator(style: style).impactOccurred()
}

func openAppSettings() {
    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
}
