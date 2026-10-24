import SwiftUI
import UIKit
import CoreImage
import CoreImage.CIFilterBuiltins

/// Exit pass QR, "SMARTBAG-EXIT:<code>:<orderId>:<totalCents>", drawn in ink on white like Android.
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
    let onDone: () -> Void
    @State private var drawn: CGFloat = 0
    @State private var qr: UIImage?

    private static let when: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        return f
    }()

    var body: some View {
        let r = receipt
        let due = !r.isPaid
        let tone = due ? Theme.bad : Theme.good
        ScrollView {
            VStack(spacing: 0) {
                ZStack {
                    Circle().fill(tone.opacity(0.12)).frame(width: 56, height: 56)
                    CheckShape().trim(from: 0, to: drawn)
                        .stroke(tone, style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
                        .frame(width: 26, height: 26)
                }
                Text(due ? "You left \(r.storeName). Payment due" : "Paid as you left \(r.storeName)")
                    .font(.system(size: 15)).foregroundColor(Theme.muted).multilineTextAlignment(.center).padding(.top, 14)
                Text(money(r.totalCents)).font(Theme.mono(40)).foregroundColor(Theme.ink)
                Text(Self.when.string(from: r.paidAt) + (r.payment.isEmpty ? "" : " · \(r.payment)"))
                    .font(.system(size: 13)).foregroundColor(Theme.faint)
                if r.unpricedCount > 0 {
                    Text("\(r.unpricedCount) item\(r.unpricedCount == 1 ? "" : "s") not sold there, not charged")
                        .font(.system(size: 13)).foregroundColor(Theme.faint)
                }

                VStack(spacing: 0) {
                    Caps("Receipt code")
                    Group {
                        if let qr {
                            Image(uiImage: qr).interpolation(.none).resizable().scaledToFit()
                        } else {
                            Color.white
                        }
                    }
                    .frame(width: 200, height: 200)
                    .padding(.top, 14)
                    .accessibilityLabel("Exit pass \(r.code)")
                    Text(r.code).font(Theme.mono(26)).tracking(7).foregroundColor(Theme.ink).padding(.top, 12)
                    Text("Staff can scan this to check your bag.").font(.system(size: 13)).foregroundColor(Theme.muted)
                }
                .padding(22)
                .frame(maxWidth: .infinity)
                .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Color.white))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.hair, lineWidth: 1))
                .padding(.top, 24)

                VStack(spacing: 0) {
                    ForEach(Array(r.lines.enumerated()), id: \.offset) { i, item in
                        if i > 0 { Rectangle().fill(Theme.hair).frame(height: 1) }
                        HStack {
                            Text((item.qty > 1 ? "\(item.qty) × " : "") + item.name)
                                .font(Theme.mono(14)).foregroundColor(Theme.ink).lineLimit(1)
                            Spacer()
                            Text(money(item.priceCents * item.qty)).font(Theme.mono(14)).foregroundColor(Theme.ink)
                        }
                        .padding(.vertical, 11)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Color.white))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.hair, lineWidth: 1))
                .padding(.top, 16)

                Pill(title: "Done", filled: false, action: onDone).padding(.top, 24)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 24)
        }
        .background(Theme.bg.ignoresSafeArea())
        .onAppear {
            qr = QRCode.image(r.exitPass)
            drawn = 0
            withAnimation(.easeOut(duration: 0.65)) { drawn = 1 }
        }
    }
}

private struct CheckShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.width * 0.18, y: rect.height * 0.52))
        p.addLine(to: CGPoint(x: rect.width * 0.40, y: rect.height * 0.72))
        p.addLine(to: CGPoint(x: rect.width * 0.82, y: rect.height * 0.30))
        return p
    }
}
