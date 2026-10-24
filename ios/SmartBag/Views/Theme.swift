// Theme.swift: the Bag It visual system (spec: Firebase apps/smart-bag/app/mobile-design/ios).
//
// 60 / 30 / 10: zinc neutrals / ink / coral. Coral is the bag's status light and nothing else: the slot in
// the mark, the item flash, the leaving line. Never text. Buttons are ink pills. Green and red only for real
// status. Type: 4 Dynamic Type sizes (34 / 22 / 17 / 13), 2 weights (Regular, Semibold), SF Mono for numbers.
// Spacing: 8-pt grid (4, 8, 12, 16, 24, 32, 48), screen margin 24.

import SwiftUI
import UIKit

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: opacity)
    }
}

enum Theme {
    /// Light and dark values from the brand (brand.md): zinc neutrals, one coral.
    private static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
    // 60: neutral base
    static let bg = adaptive(0xFAFAFA, 0x0B0B0C)
    static let surface = adaptive(0xFFFFFF, 0x18181B)
    static let hair = adaptive(0xE4E4E7, 0x27272A)
    static let unlit = adaptive(0xD4D4D8, 0x3F3F46)        // an empty bag drawing, disabled things
    // 30: ink and its quieter steps (all ≥ 4.5:1 on bg and surface)
    static let ink = adaptive(0x18181B, 0xF4F4F5)
    static let muted = adaptive(0x52525B, 0xA1A1AA)
    static let faint = adaptive(0x71717A, 0x8E8E97)
    static let onInk = adaptive(0xFFFFFF, 0x18181B)        // text on an ink pill
    // 10: the light
    static let light = Color(hex: 0xE8654A)
    // status only
    static let good = adaptive(0x157A4A, 0x34C47C)
    static let bad = adaptive(0xB42318, 0xF07171)
}

/// The four sizes. Text styles, so all of it follows Dynamic Type up to the accessibility sizes.
enum TypeScale {
    static let display = Font.system(.largeTitle).weight(.semibold)            // 34: screen title
    static let displayMono = Font.system(.largeTitle, design: .monospaced)      // 34: receipt amount
    static let titleMono = Font.system(.title2, design: .monospaced)            // 22: dock total
    static let body = Font.system(.body)                                        // 17
    static let bodyStrong = Font.system(.body).weight(.semibold)
    static let bodyMono = Font.system(.body, design: .monospaced)
    static let caption = Font.system(.footnote)                                 // 13
    static let captionStrong = Font.system(.footnote).weight(.semibold)
    static let captionMono = Font.system(.footnote, design: .monospaced)
    static let captionNum = Font.system(.footnote).monospacedDigit()           // counts, distances: SF Pro, tabular digits
}

enum Space {
    static let xs: CGFloat = 4, s: CGFloat = 8, m: CGFloat = 12, l: CGFloat = 16
    static let xl: CGFloat = 24, xxl: CGFloat = 32, xxxl: CGFloat = 48
    static let margin: CGFloat = 24
}

// MARK: - The slot mark (brand/mark.svg, viewBox 4 8 40 40)

/// A die-cut tote whose handle slot is the bag's status light. `lit` 0…1: 0 leaves the slot cut out
/// (light off), 1 fills it coral (light on). The logo itself is always lit.
struct SlotMark: View {
    var size: CGFloat = 28
    var tone: Color = Theme.ink
    var lit: Double = 1

    var body: some View {
        ZStack {
            TotePath().fill(tone, style: FillStyle(eoFill: true))
            SlotPath().fill(Theme.light).opacity(lit)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

private func markSpace(_ rect: CGRect, _ build: (inout Path) -> Void) -> Path {
    var p = Path()
    build(&p)
    let s = min(rect.width, rect.height) / 40
    return p.applying(CGAffineTransform(translationX: -4, y: -8)
        .concatenating(CGAffineTransform(scaleX: s, y: s))
        .concatenating(CGAffineTransform(translationX: rect.minX, y: rect.minY)))
}

private func addSlot(_ p: inout Path) {
    p.addRoundedRect(in: CGRect(x: 16.5, y: 17.5, width: 15, height: 4.8), cornerSize: CGSize(width: 2.4, height: 2.4))
}

/// Tote body with the slot knocked out (even-odd fill).
struct TotePath: Shape {
    func path(in rect: CGRect) -> Path {
        markSpace(rect) { p in
            p.move(to: CGPoint(x: 10.5, y: 12))
            p.addLine(to: CGPoint(x: 37.5, y: 12))
            p.addQuadCurve(to: CGPoint(x: 40, y: 14.6), control: CGPoint(x: 40, y: 12))
            p.addLine(to: CGPoint(x: 39, y: 41.2))
            p.addQuadCurve(to: CGPoint(x: 36.2, y: 44), control: CGPoint(x: 39, y: 44))
            p.addLine(to: CGPoint(x: 12.8, y: 44))
            p.addQuadCurve(to: CGPoint(x: 10, y: 41.2), control: CGPoint(x: 10, y: 44))
            p.addLine(to: CGPoint(x: 9, y: 14.6))
            p.addQuadCurve(to: CGPoint(x: 10.5, y: 12), control: CGPoint(x: 9, y: 12))
            p.closeSubpath()
            addSlot(&p)
        }
    }
}

struct SlotPath: Shape {
    func path(in rect: CGRect) -> Path { markSpace(rect) { addSlot(&$0) } }
}

// MARK: - The wordmark (brand/logo.svg): lowercase monoline "bag it", 4-unit stroke, round caps

struct Wordmark: View {
    var height: CGFloat = 16
    var color: Color = Theme.ink
    var body: some View {
        ZStack {
            WordmarkStrokes().stroke(color, style: StrokeStyle(lineWidth: 4 * height / 44, lineCap: .round, lineJoin: .round))
            WordmarkDot().fill(color)
        }
        .frame(width: height * 104 / 44, height: height)
        .accessibilityElement()
        .accessibilityLabel("Bag It")
    }
}

private func wordSpace(_ rect: CGRect, _ build: (inout Path) -> Void) -> Path {
    var p = Path()
    build(&p)
    let s = rect.height / 44
    return p.applying(CGAffineTransform(translationX: 0, y: -10)
        .concatenating(CGAffineTransform(scaleX: s, y: s))
        .concatenating(CGAffineTransform(translationX: rect.minX, y: rect.minY)))
}

private struct WordmarkStrokes: Shape {
    func path(in rect: CGRect) -> Path {
        wordSpace(rect) { p in
            let k: CGFloat = 0.5523
            func ring(_ x: CGFloat) { p.addEllipse(in: CGRect(x: x - 8, y: 24, width: 16, height: 16)) }
            p.move(to: CGPoint(x: 2, y: 12)); p.addLine(to: CGPoint(x: 2, y: 40))          // b
            ring(10)
            ring(33.5)                                                                     // a
            p.move(to: CGPoint(x: 41.5, y: 24)); p.addLine(to: CGPoint(x: 41.5, y: 40))
            ring(57.5)                                                                     // g
            p.move(to: CGPoint(x: 65.5, y: 24)); p.addLine(to: CGPoint(x: 65.5, y: 43))
            p.addCurve(to: CGPoint(x: 57.5, y: 51), control1: CGPoint(x: 65.5, y: 43 + 8 * k), control2: CGPoint(x: 57.5 + 8 * k, y: 51))
            p.addLine(to: CGPoint(x: 53.5, y: 51))
            p.move(to: CGPoint(x: 83, y: 24)); p.addLine(to: CGPoint(x: 83, y: 40))          // i
            p.move(to: CGPoint(x: 95, y: 14)); p.addLine(to: CGPoint(x: 95, y: 35))          // t
            p.addCurve(to: CGPoint(x: 100, y: 40), control1: CGPoint(x: 95, y: 35 + 5 * k), control2: CGPoint(x: 100 - 5 * k, y: 40))
            p.addLine(to: CGPoint(x: 101.5, y: 40))
            p.move(to: CGPoint(x: 90, y: 24)); p.addLine(to: CGPoint(x: 100, y: 24))
        }
    }
}

private struct WordmarkDot: Shape {
    func path(in rect: CGRect) -> Path {
        wordSpace(rect) { $0.addEllipse(in: CGRect(x: 83 - 3.1, y: 15.2 - 3.1, width: 6.2, height: 6.2)) }
    }
}

/// Logo lockup, same unit as brand/logo.svg: the tote's top and bottom line up with the "b" stem, and the
/// gap is 9 units. The mark's own 5-unit inset is pulled back so the tote sits on the margin.
struct Lockup: View {
    var height: CGFloat = 32
    var body: some View {
        HStack(alignment: .top, spacing: height * 0.125) {
            SlotMark(size: height).padding(.leading, -height * 0.125)
            Wordmark(height: height * 1.1).padding(.top, height * 0.1)
        }
        .accessibilityElement()
        .accessibilityLabel("Bag It")
    }
}

// MARK: - Controls

/// Ink pill (primary) or hairline pill (secondary). 52 pt tall, full width.
struct PillStyle: ButtonStyle {
    var primary = true
    var full = true
    @Environment(\.isEnabled) private var enabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(TypeScale.bodyStrong)
            .multilineTextAlignment(.center)
            .foregroundColor(primary ? Theme.onInk : Theme.ink)
            .padding(.horizontal, Space.xl)
            .padding(.vertical, Space.m)
            .frame(maxWidth: full ? .infinity : nil, minHeight: full ? 52 : 44)
            .background(Capsule().fill(primary ? Theme.ink : Theme.surface))
            .overlay(Capsule().stroke(primary ? Color.clear : Theme.hair, lineWidth: 1))
            .opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.4)
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
            .contentShape(Capsule())
    }
}

/// A quiet inline action ("Remove", "Try again"): ink, semibold, 44 pt target.
struct InlineAction: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(TypeScale.captionStrong)
            .foregroundColor(Theme.ink)
            .underline()
            .padding(.horizontal, Space.s)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

struct Hairline: View {
    var body: some View { Rectangle().fill(Theme.hair).frame(height: 1).accessibilityHidden(true) }
}

struct Dot: View {
    let color: Color
    var size: CGFloat = 8
    var body: some View { Circle().fill(color).frame(width: size, height: size).accessibilityHidden(true) }
}

/// One-line problem notice under the header: status icon, message, one action.
struct Notice: View {
    let symbol: String
    let message: String
    let action: String
    let perform: () -> Void
    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: Space.m) {
                Image(systemName: symbol).font(TypeScale.body).foregroundColor(Theme.bad).accessibilityHidden(true)
                Text(message).font(TypeScale.caption).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: Space.s)
                Button(action, action: perform).buttonStyle(InlineAction()).padding(.trailing, -Space.s)
            }
            .padding(.vertical, Space.xs)
            Hairline()
        }
        .accessibilityElement(children: .combine)
        .accessibilityHint(action)
        .accessibilityAction { perform() }
    }
}

func haptic(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .medium) {
    UIImpactFeedbackGenerator(style: style).impactOccurred()
}

/// Kept and prepared, so the item-added tap lands with the row flash rather than a beat late.
enum Haptics {
    private static let tap = UIImpactFeedbackGenerator(style: .light)
    private static let note = UINotificationFeedbackGenerator()
    static func prepare() { tap.prepare(); note.prepare() }
    static func itemAdded() { tap.impactOccurred(); tap.prepare() }
    static func notice(_ type: UINotificationFeedbackGenerator.FeedbackType) { note.notificationOccurred(type); note.prepare() }
}

func announce(_ text: String) {
    UIAccessibility.post(notification: .announcement, argument: text)
}

func openAppSettings() {
    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
}

func items(_ n: Int) -> String { "\(n) item\(n == 1 ? "" : "s")" }

/// What VoiceOver should say for a card label: "Demo Visa ending in 4242", not "bullet bullet…".
func spoken(_ card: String) -> String { card.replacingOccurrences(of: "•••• ", with: "ending in ") }

/// Keeps "Demo Visa •••• 4242" on one line (no-break spaces).
func unbreakable(_ s: String) -> String { s.replacingOccurrences(of: " ", with: "\u{00A0}") }
