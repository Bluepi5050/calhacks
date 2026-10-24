import SwiftUI

struct ShopView: View {
    @EnvironmentObject var m: ShopModel

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    TopRow()
                    StoreHeader().padding(.top, 26)
                    BagCard().padding(.top, 22)
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 24)
            }
            if !m.lines.isEmpty || m.inStore != nil { WalkOutBar() }
        }
        .background(Theme.bg.ignoresSafeArea())
    }
}

/// There is no Pay button: the database charges the bag when the phone leaves the store.
struct WalkOutBar: View {
    @EnvironmentObject var m: ShopModel

    var body: some View {
        let store = m.inStore
        let leaving = store?.leaving == true
        let unsold = store == nil ? 0 : m.lines.filter { !$0.sold }.reduce(0) { $0 + $1.qty }
        let pay = m.payment.isEmpty ? "your card" : m.payment
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Dot(color: leaving ? Theme.accent : store != nil ? Theme.good : Theme.faint)
                Text(leaving ? "Leaving \(store!.name)…"
                     : store != nil && m.count > 0 ? "Just walk out"
                     : store != nil ? "Fill your bag, then just walk out"
                     : "Walk into a SmartBag store to shop")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(Theme.ink)
                Spacer(minLength: 8)
                if m.count > 0 { Text(money(m.total)).font(Theme.mono(20)).foregroundColor(Theme.ink) }
            }
            Text(leaving ? "Charging \(money(m.total)) to \(pay) as you leave."
                 : store != nil && m.count > 0 ? "We'll charge \(pay) when you leave \(store!.name)." + (unsold > 0 ? " Items this store doesn't sell aren't charged." : "")
                 : store != nil ? "Paying with \(pay). No checkout, no scanning."
                 : "Items in your bag are priced by the store you're in.")
                .font(.system(size: 13))
                .foregroundColor(Theme.muted)
                .fixedSize(horizontal: false, vertical: true)
            if let e = m.syncError { Text(e).font(.system(size: 13)).foregroundColor(Theme.bad) }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.white.ignoresSafeArea(edges: .bottom))
        .overlay(Rectangle().fill(Theme.hair).frame(height: 1), alignment: .top)
        .animation(.easeInOut(duration: 0.2), value: leaving)
    }
}

struct TopRow: View {
    @EnvironmentObject var m: ShopModel

    var body: some View {
        let (label, dot): (String, Color) = {
            switch m.bag {
            case .connected(let name): return (name, Theme.good)
            case .connecting: return ("Connecting…", Theme.accent)
            case .searching: return ("Looking for your bag…", Theme.accent)
            case .problem: return ("Connect bag", Theme.bad)
            case .off: return ("Connect bag", Theme.faint)
            }
        }()
        VStack(alignment: .trailing, spacing: 8) {
            HStack(spacing: 8) {
                BagMark(size: 24)
                Text("SmartBag").font(.system(size: 17, weight: .semibold)).foregroundColor(Theme.ink)
                Spacer()
                HStack(spacing: 8) {
                    Dot(color: dot)
                    Text(label).font(.system(size: 13)).foregroundColor(Theme.ink).lineLimit(1).truncationMode(.tail)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(Capsule().fill(Color.white))
                .overlay(Capsule().stroke(Theme.hair, lineWidth: 1))
                .contentShape(Capsule())
                .onTapGesture { if !m.bag.isBusy { m.connectBag() } }
                // Hidden demo aid: long-press the bag chip to drop a random item in the bag.
                .onLongPressGesture(minimumDuration: 0.5) { haptic(); m.demoDrop() }
                .accessibilityAddTraits(.isButton)
            }
            if case .problem(let message) = m.bag {
                Text(message).font(.system(size: 13)).foregroundColor(Theme.bad)
            }
        }
    }
}

struct StoreHeader: View {
    @EnvironmentObject var m: ShopModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            switch m.store {
            case .locating:
                Caps("Finding your store")
                Text("One moment…").font(.system(size: 26, weight: .semibold)).foregroundColor(Theme.faint)
            case .noLocation:
                Caps("Location is off", color: Theme.bad)
                Text("Turn on location so we can find your store").font(.system(size: 22, weight: .semibold)).foregroundColor(Theme.ink)
                Button("Open settings", action: openAppSettings)
                    .font(.system(size: 15, weight: .medium)).foregroundColor(Theme.accent).padding(.top, 4)
            case let .inStore(_, name, address, leaving):
                HStack(spacing: 8) {
                    Dot(color: leaving ? Theme.accent : Theme.good, size: 7)
                    Caps(leaving ? "Leaving" : "You're shopping at", color: leaving ? Theme.accent : Theme.good)
                }
                // Hidden demo aid: long-press the store name to simulate walking out.
                Text(name)
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundColor(Theme.ink)
                    .onLongPressGesture(minimumDuration: 0.5) { haptic(.heavy); m.demoWalkOut() }
                if !address.trimmingCharacters(in: .whitespaces).isEmpty {
                    Text(address).font(.system(size: 14)).foregroundColor(Theme.muted)
                }
            case let .away(nearestName, nearestDistance):
                Caps("Not in a SmartBag store")
                Text("Walk into a SmartBag store to start shopping").font(.system(size: 22, weight: .semibold)).foregroundColor(Theme.ink)
                if let n = nearestName, let d = nearestDistance {
                    Text("Nearest: \(n) · \(distanceText(d))").font(.system(size: 14)).foregroundColor(Theme.muted)
                }
            case .offline(let message):
                Caps("Offline", color: Theme.bad)
                Text("Can't reach SmartBag").font(.system(size: 22, weight: .semibold)).foregroundColor(Theme.ink)
                Text(message).font(.system(size: 14)).foregroundColor(Theme.muted)
                Button("Try again") { m.foreground() }
                    .font(.system(size: 15, weight: .medium)).foregroundColor(Theme.accent).padding(.top, 4)
            }
            if m.preciseLocationOff {
                Button(action: openAppSettings) {
                    Text("Precise Location is off, so we can't tell which store you're in. Turn it on in Settings.")
                        .font(.system(size: 13)).foregroundColor(Theme.bad).multilineTextAlignment(.leading)
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct BagCard: View {
    @EnvironmentObject var m: ShopModel

    var body: some View {
        VStack(spacing: 0) {
            if m.lines.isEmpty {
                VStack(spacing: 0) {
                    BagMark(size: 44)
                    Text("Your bag is empty").font(.system(size: 16, weight: .medium)).foregroundColor(Theme.ink).padding(.top, 14)
                    Text("Drop groceries in. They show up here with this store's prices, and you pay by walking out.")
                        .font(.system(size: 14)).foregroundColor(Theme.muted).multilineTextAlignment(.center).padding(.top, 4)
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 40)
                .frame(maxWidth: .infinity)
            } else {
                VStack(spacing: 0) {
                    ForEach(m.lines, id: \.sku) { l in LineRow(line: l) }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                Dashed().padding(.horizontal, 20)
                HStack(alignment: .lastTextBaseline) {
                    Caps("\(m.count) item\(m.count == 1 ? "" : "s")")
                    Spacer()
                    Text(money(m.total)).font(Theme.mono(24)).foregroundColor(Theme.ink)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
            }
        }
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Color.white))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.hair, lineWidth: 1))
        .animation(.easeOut(duration: 0.25), value: m.lines)
    }
}

struct LineRow: View {
    @EnvironmentObject var m: ShopModel
    let line: CartLine
    @State private var glow = 0.0

    var body: some View {
        let missing = m.inStore != nil && !line.sold
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(line.sold ? line.name : "Item \(line.sku)")
                    .font(Theme.mono(15)).foregroundColor(missing ? Theme.faint : Theme.ink).lineLimit(1)
                Text(line.sold ? "\(line.qty) × \(money(line.priceCents))"
                     : missing ? "Not sold here · won't be charged" : "\(line.qty) in bag · waiting for store prices")
                    .font(Theme.mono(12)).foregroundColor(missing ? Theme.bad : Theme.faint)
            }
            Spacer(minLength: 0)
            if line.sold {
                Text(money(line.lineCents)).font(Theme.mono(15)).foregroundColor(Theme.ink)
            } else if missing {
                Button("Remove") { m.dismissLine(line.sku) }
                    .font(.system(size: 13, weight: .medium)).foregroundColor(Theme.accent)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 11)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Theme.accent.opacity(0.16 * glow)))
        .onChange(of: m.flash) { f in
            guard let f, f.sku == line.sku else { return }
            glow = 1
            withAnimation(.easeOut(duration: 1.3)) { glow = 0 }
        }
        .onAppear {
            if let f = m.flash, f.sku == line.sku, f.n > 0 {
                glow = 1
                withAnimation(.easeOut(duration: 1.3)) { glow = 0 }
            }
        }
    }
}
