// ReceiptView.swift: the peak and the end. The database charged the bag as the phone left the store; at the
// same moment the bag gets "PAID <code>" and its light blinks. Here the bag mark's slot lights up the same way.

import SwiftUI
import UIKit
import CoreImage
import CoreImage.CIFilterBuiltins

/// Exit pass QR, "SMARTBAG-EXIT:<code>:<orderId>:<totalCents>" (same payload as Android), ink on white.
enum QRCode {
    static func image(_ text: String) -> UIImage? {
        let qr = CIFilter.qrCodeGenerator()
        qr.message = Data(text.utf8)
        qr.correctionLevel = "M"
        guard let raw = qr.outputImage else { return nil }
        let color = CIFilter.falseColor()
        color.inputImage = raw
        color.color0 = CIColor(red: 0x18 / 255.0, green: 0x18 / 255.0, blue: 0x1B / 255.0)
        color.color1 = CIColor(red: 1, green: 1, blue: 1)
        guard let tinted = color.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
              let cg = CIContext().createCGImage(tinted, from: tinted.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

struct ReceiptScreen: View {
    let receipt: Receipt
    /// true = just charged (play the peak); false = reopened from "Last trip".
    var live = true
    let onDone: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var phase
    @AccessibilityFocusState private var summaryFocused: Bool
    @State private var played = false
    @State private var lit: Double = 0
    @State private var line: CGFloat = 0.5           // the dock's leaving line, finishing: the charge went through
    @State private var lineOpacity: Double = 0
    @State private var stage = 0                 // 0 nothing, 1 summary, 2 pass and items
    @State private var qr: UIImage?

    var body: some View {
        let r = receipt
        let due = !r.isPaid
        ScrollView {
            VStack(spacing: 0) {
                SlotMark(size: 56, lit: lit).padding(.top, Space.xxxl)

                VStack(spacing: Space.s) {
                    Text(money(r.totalCents)).font(TypeScale.displayMono).foregroundColor(Theme.ink)
                        .lineLimit(1).minimumScaleFactor(0.6)
                    HStack(alignment: .firstTextBaseline, spacing: Space.s) {
                        Image(systemName: due ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                            .foregroundColor(due ? Theme.bad : Theme.good)
                        Text(due ? "Payment due at \(r.storeName)" : "Paid as you left \(r.storeName)")
                            .foregroundColor(Theme.ink)
                    }
                    .font(TypeScale.body)
                    .multilineTextAlignment(.center)
                    Text(when(r.paidAt) + (r.isPaid && !r.payment.isEmpty ? " · \(unbreakable(r.payment))" : ""))
                        .font(TypeScale.caption).foregroundColor(Theme.muted).multilineTextAlignment(.center)
                    if due {
                        Text("No card on file, so nothing was charged.")
                            .font(TypeScale.caption).foregroundColor(Theme.bad).multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true).padding(.top, Space.xs)
                    }
                }
                .padding(.top, Space.xl)
                .accessibilityElement(children: .combine)
                .accessibilityLabel((due ? "Payment due, \(money(r.totalCents)), at \(r.storeName)."
                                         : "Paid \(money(r.totalCents)) as you left \(r.storeName).")
                                    + " \(when(r.paidAt))." + (r.isPaid && !r.payment.isEmpty ? " \(spoken(r.payment))." : "")
                                    + (due ? " No card on file, so nothing was charged. Show the exit pass to staff to pay." : ""))
                .accessibilityAddTraits(.isHeader)
                .accessibilityFocused($summaryFocused)
                .opacity(stage >= 1 ? 1 : 0)
                .offset(y: stage >= 1 || reduceMotion ? 0 : 8)

                VStack(spacing: 0) {
                    pass(r).padding(.top, Space.xxl)
                    lines(r).padding(.top, Space.xxl)
                }
                .opacity(stage >= 2 ? 1 : 0)
                .offset(y: stage >= 2 || reduceMotion ? 0 : 8)
            }
            .padding(.horizontal, Space.margin)
            .padding(.bottom, Space.xl)
        }
        .background(Theme.bg.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                // Where the dock's half line was: it completes (the charge went through), then hands over to the slot.
                GeometryReader { g in
                    ZStack(alignment: .bottomLeading) {
                        Rectangle().fill(Theme.hair).frame(height: 1)
                        Rectangle().fill(Theme.light).frame(width: g.size.width * line, height: 2).opacity(lineOpacity)
                    }
                    .frame(maxHeight: .infinity, alignment: .bottom)
                }
                .frame(height: 2)
                .accessibilityHidden(true)
                VStack(spacing: Space.s) {
                    Button("Done", action: onDone).buttonStyle(PillStyle())
                    if live || due {
                        Text(due ? "You'll find this pass under Last trip." : "Thanks for shopping at \(r.storeName). Your bag is ready for next time.")
                            .font(TypeScale.caption).foregroundColor(Theme.muted).multilineTextAlignment(.center)
                    }
                }
                .padding(.horizontal, Space.margin)
                .padding(.top, Space.m)
                .padding(.bottom, Space.s)
            }
            .background(Theme.bg.ignoresSafeArea(edges: .bottom))
        }
        .accessibilityAction(.escape, onDone)
        .onAppear {
            qr = QRCode.image(receipt.exitPass)
            if phase == .active { play() }
        }
        .onChange(of: phase) { p in if p == .active { play() } }      // charged in a pocket: play when it is seen
    }

    private func pass(_ r: Receipt) -> some View {
        VStack(spacing: 0) {
            Text("Exit pass").font(TypeScale.caption).foregroundColor(Theme.muted)
            Group {
                if let qr { Image(uiImage: qr).interpolation(.none).resizable().scaledToFit() } else { Color.white }
            }
            .frame(width: 160, height: 160)
            .padding(Space.m)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.white))
            .padding(.top, Space.l)
            .accessibilityLabel("Exit pass QR code")
            Text(r.code).font(TypeScale.bodyMono).tracking(4).foregroundColor(Theme.ink).padding(.top, Space.l)
                .accessibilityLabel("Code \(r.code.map(String.init).joined(separator: " "))")
            Text(r.isPaid ? "Show this if staff ask to check your bag." : "Show this to staff to pay.")
                .font(TypeScale.caption).foregroundColor(Theme.muted).multilineTextAlignment(.center).padding(.top, Space.xs)
        }
        .padding(Space.xl)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.surface))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.hair, lineWidth: 1))
        .accessibilityElement(children: .contain)
    }

    private func lines(_ r: Receipt) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(items(r.itemCount)).font(TypeScale.captionNum).foregroundColor(Theme.muted).padding(.bottom, Space.s)
            Hairline()
            ForEach(Array(r.lines.enumerated()), id: \.offset) { _, item in
                HStack(alignment: .center, spacing: Space.m) {
                    VStack(alignment: .leading, spacing: Space.xs) {           // same row shape as the bag list
                        Text(item.name).font(TypeScale.body).foregroundColor(Theme.ink).lineLimit(2)
                        (Text("\(item.qty) × ").font(TypeScale.captionNum) + Text(money(item.priceCents)).font(TypeScale.captionMono))
                            .foregroundColor(Theme.muted)
                    }
                    Spacer(minLength: Space.s)
                    Text(money(item.priceCents * item.qty)).font(TypeScale.bodyMono).foregroundColor(Theme.ink)
                }
                .padding(.vertical, Space.m)
                .accessibilityElement(children: .combine)
                Hairline()
            }
            HStack(alignment: .firstTextBaseline) {
                Text("Total").font(TypeScale.bodyStrong).foregroundColor(Theme.ink)
                Spacer()
                Text(money(r.totalCents)).font(TypeScale.bodyMono.weight(.semibold)).foregroundColor(Theme.ink)
            }
            .padding(.vertical, Space.m)
            .accessibilityElement(children: .combine)
            if r.unpricedCount > 0 {
                Text("\(items(r.unpricedCount)) not sold there, not charged.")
                    .font(TypeScale.caption).foregroundColor(Theme.muted)
            }
        }
    }

    /// The peak, once, when the receipt is actually on screen: after the cover has slid up, the slot lights
    /// (the bag's own light did the same when it got PAID) together with the haptic; then the amount, then the
    /// details. Payment due leaves the slot dark: no money moved.
    private func play() {
        guard !played else { return }
        played = true
        let paid = receipt.isPaid
        guard live, !reduceMotion, !UIAccessibility.isVoiceOverRunning else {
            lit = paid ? 1 : 0; stage = 2
            if live { Haptics.notice(paid ? .success : .warning) }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { summaryFocused = true }
            return
        }
        if paid {
            // Continuity with the dock: its half line completes along the bottom bar, then hands over to the slot.
            lineOpacity = 1
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { withAnimation(.easeOut(duration: 0.35)) { line = 1 } }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { withAnimation(.easeOut(duration: 0.4)) { lineOpacity = 0 } }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            Haptics.notice(paid ? .success : .warning)
            if paid { withAnimation(.easeOut(duration: 0.45)) { lit = 1 } }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.55) {
            withAnimation(.easeOut(duration: 0.35)) { stage = 1 }
            summaryFocused = true
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.75) { withAnimation(.easeOut(duration: 0.35)) { stage = 2 } }
    }
}
