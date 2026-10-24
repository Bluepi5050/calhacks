// ShopView.swift: the Shop tab. The hero says where you are; in a store the bag list and the walk-out dock
// appear. There is no Pay button: the database charges the wallet's default card when the phone leaves.
// 1.2: "Paying with <card>" opens the card picker; no card -> "Add card"; no paired bag -> the chip pairs one.

import SwiftUI

struct ShopView: View {
    @EnvironmentObject var m: ShopModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var reopened: Receipt?            // "Last trip" reopened as a sheet
    @State private var sheetUp = false               // the live receipt waits until that sheet has fully gone
    @State private var picking = false               // card picker
    @State private var addingCard = false
    @State private var pairing = false

    /// The bag can't count yet and nothing is in it: the dock carries the one fix (thumb zone), not a notice.
    private var bagFixInDock: Bool { m.inStore != nil && m.count == 0 && !m.bag.isConnected && !m.bag.isBusy }

    /// A payment-due pass stays reachable for a few hours, including back inside the store to pay.
    private var duePass: Receipt? {
        guard let r = m.visibleLastReceipt, !r.isPaid, Date().timeIntervalSince(r.paidAt) < 6 * 3600 else { return nil }
        return r
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    TopBar(pair: { pairing = true })
                    if case .problem(let message) = m.bag, !bagFixInDock {
                        Notice(symbol: "exclamationmark.circle", message: message,
                               action: message.contains("Settings") ? "Open Settings" : "Try again") {
                            if message.contains("Settings") { openAppSettings() } else { m.connectBag() }
                        }
                    }
                    if m.preciseLocationOff {
                        Notice(symbol: "location.slash",
                               message: m.inStore != nil ? "Precise Location is off, so we may miss when you leave."
                                                         : "Precise Location is off, so we can't tell which store you're in.",
                               action: "Turn on", perform: openAppSettings)
                    }
                    Hero().padding(.top, Space.xxl)
                    if m.inStore != nil || !m.lines.isEmpty {
                        if let r = duePass, m.paid == nil {   // back inside to pay: the pass comes first
                            LastTrip(receipt: r) { open(r) }.padding(.top, Space.xxl)
                        }
                        BagList().padding(.top, Space.xxl)
                    } else if let r = m.visibleLastReceipt, m.paid == nil {
                        LastTrip(receipt: r) { open(r) }.padding(.top, Space.xxxl)
                    }
                }
                .padding(.horizontal, Space.margin)
                .padding(.top, Space.s)
                .padding(.bottom, Space.xxl)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.25), value: m.lines)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.25), value: m.store)
            }
            .onChange(of: m.flash) { f in
                guard let f, let line = m.lines.first(where: { $0.sku == f.sku }) else { return }
                if line.sold { Haptics.itemAdded() } else { Haptics.notice(.warning) }
                withAnimation(reduceMotion ? nil : .easeOut(duration: 0.3)) { proxy.scrollTo(f.sku, anchor: .center) }
                announce(line.sold ? "Added \(line.name), \(money(line.priceCents)). Total \(money(m.total))."
                                   : "Unknown item. It isn't sold here and won't be charged.")
            }
        }
        .background(Theme.bg.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            switch m.store {
            case .noLocation:
                ActionDock(title: "Open Settings", perform: openAppSettings)
            case .offline:
                ActionDock(title: "Try again") { m.foreground() }
            default:
                if m.inStore != nil || !m.lines.isEmpty {
                    WalkOutDock(fix: bagFixInDock, onCards: { picking = true }, onAddCard: { addingCard = true }, onPair: { pairing = true })
                }
            }
        }
        .onAppear { Haptics.prepare() }
        .sheet(isPresented: $picking) { CardPickerSheet().presentationDetents([.medium, .large]).appModels() }
        .sheet(isPresented: $addingCard) { AddCardScreen { addingCard = false }.appModels() }
        .fullScreenCover(isPresented: $pairing) { PairingFlow { pairing = false }.appModels() }
        // A live receipt must be able to cover the screen even if a Last trip sheet is open.
        .onChange(of: m.paid?.id) { if $0 != nil { reopened = nil } }
        .sheet(item: $reopened, onDismiss: { sheetUp = false }) { r in
            ReceiptScreen(receipt: r, live: false) { reopened = nil }
                .presentationDragIndicator(.visible)
        }
        // The walk-out receipt covers the shop; Done returns to an empty bag.
        .fullScreenCover(item: Binding(get: { sheetUp ? nil : m.paid },
                                       set: { if $0 == nil { m.finishReceipt() } })) { r in
            ReceiptScreen(receipt: r, live: true) { m.finishReceipt() }
        }
        .onChange(of: m.inStore?.leaving) { leaving in
            if leaving == true, let s = m.inStore {
                announce("Leaving \(s.name). " + (m.count > 0 ? "Charging \(money(m.total)) as you leave." : "Nothing to charge."))
            }
        }
    }
}

/// Full-width action in the thumb zone for states whose fix is one tap (location off, offline).
private struct ActionDock: View {
    let title: String
    let perform: () -> Void
    var body: some View {
        Button(title, action: perform).buttonStyle(PillStyle())
            .padding(.horizontal, Space.margin).padding(.top, Space.m).padding(.bottom, Space.s)
            .background(Theme.bg.ignoresSafeArea(edges: .bottom))
    }
}

extension ShopView {
    private func open(_ r: Receipt) { sheetUp = true; reopened = r }
}

// MARK: - Top bar

struct TopBar: View {
    var pair: () -> Void = {}
    var body: some View {
        HStack(alignment: .center) {
            Wordmark(height: 20).accessibilityAddTraits(.isHeader)
            Spacer(minLength: Space.l)
            BagChip(pair: pair)
        }
        .frame(minHeight: 44)
    }
}

/// Bag status. Tap to connect (or, with no bag paired yet, to pair one); hidden demo: hold 1 s to drop a random
/// item (also a VoiceOver action).
struct BagChip: View {
    @EnvironmentObject var m: ShopModel
    var pair: () -> Void = {}

    /// No bag on the account and none connected: the chip is the way to pair one.
    private var unpaired: Bool { m.pairedBags.isEmpty && !m.bag.isConnected }

    var body: some View {
        let (label, color, busy): (String, Color, Bool) = {
            if unpaired && !m.bag.isBusy { return ("Pair your bag", Theme.faint, false) }
            switch m.bag {
            case .connected: return ("Bag connected", Theme.good, false)
            case .connecting: return ("Connecting", Theme.faint, true)
            case .searching: return ("Looking for your bag", Theme.faint, true)
            case .problem: return ("Bag not connected", Theme.bad, false)
            case .off: return ("Bag not connected", Theme.faint, false)
            }
        }()
        HStack(spacing: Space.s) {
            if busy { ProgressView().controlSize(.mini).tint(Theme.muted) } else { Dot(color: color) }
            Text(label).font(TypeScale.caption).foregroundColor(Theme.ink).lineLimit(1).minimumScaleFactor(0.85)
        }
        .padding(.horizontal, Space.m)
        .frame(minHeight: 32)
        .background(Capsule().fill(Theme.surface))
        .overlay(Capsule().stroke(Theme.hair, lineWidth: 1))
        .padding(.vertical, Space.s)                  // 48 pt touch target around a 32 pt capsule
        .contentShape(Rectangle())
        .onTapGesture { tap() }
        .onLongPressGesture(minimumDuration: 1.0, maximumDistance: 10) { haptic(); m.demoDrop() }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Bag")
        .accessibilityValue(connectedName.map { "Connected, \($0)" } ?? label)
        .accessibilityHint(unpaired ? "Double-tap to pair your bag." : (m.bag.isBusy ? "" : "Double-tap to connect your bag."))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { tap() }
        .accessibilityAction(named: "Demo: add a random item") { m.demoDrop() }
    }

    private var connectedName: String? { if case let .connected(n) = m.bag { return n } else { return nil } }

    private func tap() {
        if unpaired { pair() } else if !m.bag.isBusy { m.connectBag() }
    }
}

// MARK: - Hero: where you are

struct Hero: View {
    @EnvironmentObject var m: ShopModel
    @State private var slow = false                  // GPS taking a while

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            switch m.store {
            case .locating:
                HStack(spacing: Space.m) {
                    Title("Finding your store")
                    ProgressView().tint(Theme.muted)
                }
                Lead(slow ? "Still looking. Stepping near a window or door helps." : "Checking your location.")
            case .noLocation:
                Title("Location is off")
                Lead("Bag It needs your location to find your store, and to charge your bag when you leave.")
            case .offline(let message):
                Title("Can't reach Bag It")
                Lead(message)
            case let .away(nearestName, nearestDistance):
                Title("Not in a Bag It store")
                Lead("Walk into one and your bag starts pricing on its own.")
                if let n = nearestName, let d = nearestDistance {
                    HStack(spacing: Space.s) {
                        Image(systemName: "location").font(TypeScale.caption).foregroundColor(Theme.faint).accessibilityHidden(true)
                        Text("Nearest: \(n) · \(distanceText(d))").font(TypeScale.captionNum).foregroundColor(Theme.muted)
                    }
                    .padding(.top, Space.l)
                    .accessibilityElement(children: .combine)
                }
            case let .inStore(_, name, address, leaving):
                Text(leaving ? "Leaving" : "You're at")
                    .font(leaving ? TypeScale.captionStrong : TypeScale.caption)
                    .foregroundColor(leaving ? Theme.ink : Theme.muted)
                    .accessibilityHidden(true)
                // Hidden demo: hold the store name 1.5 s to walk out (also a VoiceOver action). The empty tap
                // gesture keeps the long press from swallowing scrolls that start on the name.
                Text(name)
                    .font(TypeScale.display).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, Space.xs)
                    .onTapGesture {}
                    .onLongPressGesture(minimumDuration: 1.5, maximumDistance: 10) { haptic(.heavy); m.demoWalkOut() }
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityLabel((leaving ? "Leaving " : "You're at ") + name)
                    .accessibilityAction(named: "Demo: walk out of the store") { m.demoWalkOut() }
                if !address.trimmingCharacters(in: .whitespaces).isEmpty {
                    Text(address).font(TypeScale.caption).foregroundColor(Theme.muted).padding(.top, Space.xs)
                }
                if leaving {
                    Lead("Step back inside to keep shopping.")
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .task(id: m.store == .locating) { [locating = m.store == .locating] in
            slow = false                                   // the task restarts (and the old one is cancelled) when this changes
            guard locating else { return }
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            if !Task.isCancelled { slow = true }
        }
    }
}

private struct Title: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(TypeScale.display).foregroundColor(Theme.ink)
            .fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
    }
}

private struct Lead: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(TypeScale.body).foregroundColor(Theme.muted)
            .fixedSize(horizontal: false, vertical: true).padding(.top, Space.m)
    }
}

// MARK: - The bag

struct BagList: View {
    @EnvironmentObject var m: ShopModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if m.lines.isEmpty {
                EmptyBag(store: m.inStore?.name, bag: m.bag, unpaired: m.pairedBags.isEmpty)
            } else {
                HStack(alignment: .firstTextBaseline) {
                    Text("In your bag").font(TypeScale.caption).foregroundColor(Theme.muted)
                    Spacer()
                    Text(items(m.count)).font(TypeScale.captionNum).foregroundColor(Theme.muted)
                }
                .padding(.bottom, Space.s)
                .accessibilityElement(children: .combine)
                Hairline()
                // Newest first, so a new item lands at the top, in view, where it slides in from.
                ForEach(m.lines.reversed(), id: \.sku) { l in
                    VStack(spacing: 0) {
                        LineRow(line: l)
                        Hairline()
                    }
                    .id(l.sku)
                    .transition(reduceMotion ? .opacity
                                : .asymmetric(insertion: .move(edge: .top).combined(with: .opacity), removal: .opacity))
                }
            }
        }
    }
}

private struct EmptyBag: View {
    let store: String?
    let bag: BagState
    var unpaired = false
    var body: some View {
        let (title, why): (String, String) = {
            switch bag {
            case .connected:
                return ("Drop something in", store.map { "Items show up here with \($0)'s prices." } ?? "Items show up here with the store's prices.")
            case .searching, .connecting:
                return ("Looking for your bag", "Switch it on and keep it close.")
            case .problem, .off:
                if unpaired { return ("No bag paired yet", "Pair your bag and items show up here as you drop them in.") }
                return ("Your bag is empty", "Items show up here once your bag is connected.")
            }
        }()
        VStack(spacing: 0) {
            SlotMark(size: 48, tone: Theme.unlit, lit: 0)
            Text(title).font(TypeScale.bodyStrong).foregroundColor(Theme.ink).padding(.top, Space.l)
            Text(why)
                .font(TypeScale.caption).foregroundColor(Theme.muted)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                .padding(.top, Space.xs)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Space.xxxl)
        .accessibilityElement(children: .combine)
    }
}

struct LineRow: View {
    @EnvironmentObject var m: ShopModel
    let line: CartLine
    @Environment(\.colorScheme) private var scheme
    @State private var wash = 0.0

    var body: some View {
        let missing = m.inStore != nil && !line.sold
        HStack(alignment: .center, spacing: Space.m) {
            VStack(alignment: .leading, spacing: Space.xs) {
                if line.sold {
                    Text(line.name).font(TypeScale.body).foregroundColor(Theme.ink).lineLimit(2)
                    (Text("\(line.qty) × ").font(TypeScale.captionNum) + Text(money(line.priceCents)).font(TypeScale.captionMono))
                        .foregroundColor(Theme.muted)
                } else {
                    (Text("Unknown item  ").font(TypeScale.body).foregroundColor(missing ? Theme.faint : Theme.ink)
                     + Text(line.sku).font(TypeScale.captionMono).foregroundColor(Theme.faint))
                        .lineLimit(2)
                    Text(missing ? "Not sold here · won't be charged" : "Priced when you're in a store")
                        .font(TypeScale.caption).foregroundColor(Theme.muted)
                }
            }
            Spacer(minLength: Space.s)
            if line.sold {
                Text(money(line.lineCents)).font(TypeScale.bodyMono).foregroundColor(Theme.ink)
            } else if missing {
                Button("Dismiss") { m.dismissLine(line.sku) }.buttonStyle(InlineAction()).padding(.trailing, -Space.s)
            }
        }
        .padding(.vertical, Space.m)
        .frame(minHeight: 56)
        .background(Theme.light.opacity((scheme == .dark ? 0.14 : 0.10) * wash).padding(.horizontal, -Space.margin))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(label(missing))
        .accessibilityActions { if missing { Button("Dismiss") { m.dismissLine(line.sku) } } }
        .onChange(of: m.flash) { f in if let f { blink(f) } }
        .onAppear { if let f = m.flash { blink(f) } }       // a brand-new row appears with its flash already set
    }

    private func label(_ missing: Bool) -> String {
        if line.sold {
            return line.qty > 1 ? "\(line.name), \(line.qty) at \(money(line.priceCents)) each, \(money(line.lineCents))"
                                : "\(line.name), \(money(line.lineCents))"
        }
        return missing ? "Unknown item \(line.sku), not sold here, won't be charged" : "Unknown item \(line.sku), priced when you're in a store"
    }

    /// Flashes already played, so a rebuilt list doesn't replay an old one.
    private static var played = Set<Int>()

    /// The row lights up like the bag's light does when it accepts an item (OK), then fades. Unknown items get
    /// no light: the bag answers them with ERR and its LED stays dark.
    private func blink(_ f: Flash) {
        guard f.sku == line.sku, line.sold, !Self.played.contains(f.n) else { return }
        Self.played.insert(f.n)
        wash = 1
        DispatchQueue.main.async { withAnimation(.easeOut(duration: 1.2)) { wash = 0 } }
    }
}

private struct LastTrip: View {
    let receipt: Receipt
    let open: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Last trip").font(TypeScale.caption).foregroundColor(Theme.muted).padding(.bottom, Space.s)
            Hairline()
            Button(action: open) {
                HStack(spacing: Space.m) {
                    VStack(alignment: .leading, spacing: Space.xs) {
                        Text(receipt.storeName).font(TypeScale.body).foregroundColor(Theme.ink)
                        Text(receipt.isPaid ? when(receipt.paidAt) : "Payment due · Show to staff")
                            .font(TypeScale.caption).foregroundColor(receipt.isPaid ? Theme.muted : Theme.bad)
                    }
                    Spacer()
                    Text(money(receipt.totalCents)).font(TypeScale.bodyMono).foregroundColor(Theme.ink)
                    Image(systemName: "chevron.right").font(TypeScale.caption).foregroundColor(Theme.faint).accessibilityHidden(true)
                }
                .padding(.vertical, Space.m)
                .frame(minHeight: 56)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Shows the receipt and exit pass.")
            Hairline()
        }
    }
}

func when(_ d: Date) -> String {
    let t = DateFormatter.localizedString(from: d, dateStyle: .none, timeStyle: .short)
    if Calendar.current.isDateInToday(d) { return "Today, \(t)" }
    if Calendar.current.isDateInYesterday(d) { return "Yesterday, \(t)" }
    return DateFormatter.localizedString(from: d, dateStyle: .medium, timeStyle: .short)
}

// MARK: - Walk-out dock (thumb zone)

/// There is no Pay button. The dock says what happens next and which card pays (tap it to choose another), and
/// the bag mark is a live copy of the bag's light: it blinks when an item is accepted and breathes while you are
/// leaving. When the bag can't count yet (`fix`) or no card is on file, the dock carries the one action that fixes it.
struct WalkOutDock: View {
    @EnvironmentObject var m: ShopModel
    @Environment(\.dynamicTypeSize) private var typeSize
    var fix = false
    var onCards: () -> Void = {}
    var onAddCard: () -> Void = {}
    var onPair: () -> Void = {}

    private enum Line { case none, muted(String), red(String) }

    var body: some View {
        let store = m.inStore
        let leaving = store?.leaving == true
        let noCard = m.payment.isEmpty
        let card = unbreakable(m.payment)
        let ax = typeSize.isAccessibilitySize
        let has = m.count > 0
        let unpaired = m.pairedBags.isEmpty
        let (title, line, showCard): (String, Line, Bool) = {
            if leaving {
                if !has { return ("Nothing to charge", .muted("Your bag is empty"), false) }
                return noCard ? ("Leaving", .none, false) : ("Charging as you leave", .none, true)
            }
            if store == nil { return ("Not priced yet", .muted("Items are priced by the store you're in"), false) }
            if fix { return (unpaired ? "Pair your bag to start" : "Connect your bag to start", .none, false) }
            let t = has ? (noCard ? "Add a card to just walk out" : "Just walk out")
                        : (m.bag.isBusy ? "Looking for your bag" : "Fill your bag, then just walk out")
            if noCard {
                return (t, has ? .none : .muted("Add a card so leaving pays for you"), false)
            }
            return (t, .muted(has ? "Charged as you leave" : "Paying with"), true)
        }()
        // At accessibility sizes the card line is dropped, so "Paying with" carries the card itself.
        let line1: Line = {
            if ax, showCard, case .muted(let s) = line, s == "Paying with" { return .muted("Paying with \(card)") }
            return line
        }()
        let addCardPill = noCard && store != nil && !fix && (has || !leaving)
        // With items and no card, the consequence gets its own full-width red line above "Add card".
        let cardNote: String? = (noCard && has && store != nil)
            ? (leaving ? "No card on file. You'll pay at the store." : "No card on file. If you leave now, you'll pay at the store.") : nil
        let syncNote: String? = m.syncError == nil ? nil
            : (store != nil && has ? "Not synced yet. Bag It keeps trying. If you leave now, you'll be charged once it reconnects."
                                   : "Not synced yet. Bag It keeps trying.")
        let problem: String? = { if !unpaired, case let .problem(msg) = m.bag { return msg } else { return nil } }()
        let opensSettings = problem?.contains("Settings") == true
        let spokenLabel: String = {
            let c = spoken(m.payment)
            if leaving {
                if !has { return "Leaving. Nothing to charge." }
                return noCard ? "Leaving. No card on file, so you'll pay \(money(m.total)) at the store." : "Charging \(money(m.total)) to \(c) as you leave."
            }
            if store == nil { return "Not priced yet. Items are priced by the store you're in. \(items(m.count)) in your bag." }
            if fix { return "\(title). \(problem ?? "")" }
            if noCard {
                return has ? "Add a card to just walk out. \(money(m.total)) for \(items(m.count)). No card on file. If you leave now, you'll pay at the store."
                           : "\(title). Add a card so leaving pays for you."
            }
            if has { return "Just walk out. \(money(m.total)) for \(items(m.count)), charged to \(c) as you leave." }
            return "\(title). Paying with \(c)."
        }() + (syncNote.map { " " + $0 } ?? "")

        VStack(spacing: 0) {
            LeavingLine(leaving: leaving)
            let layout = ax ? AnyLayout(VStackLayout(alignment: .leading, spacing: Space.m))
                            : AnyLayout(HStackLayout(alignment: .center, spacing: Space.l))
            layout {
              Button(action: { if showCard { onCards() } }) {
              HStack(alignment: .center, spacing: Space.l) {
                BagLight(leaving: leaving, connected: m.bag.isConnected)
                VStack(alignment: .leading, spacing: Space.xs) {
                    Text(title).font(TypeScale.bodyStrong).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                    switch line1 {
                    case .none: EmptyView()
                    case .muted(let s):
                        Text(s).font(TypeScale.caption).foregroundColor(Theme.muted).lineLimit(ax ? 3 : 1).minimumScaleFactor(0.85)
                    case .red(let s):
                        Text(s).font(TypeScale.caption).foregroundColor(Theme.bad).fixedSize(horizontal: false, vertical: true)
                    }
                    if showCard && !ax {
                        HStack(spacing: Space.xs) {
                            Text(card).font(TypeScale.caption).foregroundColor(Theme.faint).lineLimit(1).minimumScaleFactor(0.85)
                            Image(systemName: "chevron.right").font(.system(.caption2).weight(.semibold)).foregroundColor(Theme.faint)
                        }
                    }
                }
              }
              .contentShape(Rectangle())
              }
              .buttonStyle(.plain)
              .disabled(!showCard)
                if !ax { Spacer(minLength: 0) }
                if has {
                    VStack(alignment: ax ? .leading : .trailing, spacing: Space.xs) {
                        Text(money(m.total)).font(TypeScale.titleMono).foregroundColor(Theme.ink)
                            .lineLimit(1).minimumScaleFactor(0.7)
                        if !ax { Text(items(m.count)).font(TypeScale.captionNum).foregroundColor(Theme.muted) }
                    }
                    .layoutPriority(1)
                }
            }
            .padding(.horizontal, Space.margin)
            .padding(.top, Space.l)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, (syncNote == nil && cardNote == nil && !fix && !addCardPill) ? Space.l : Space.s)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spokenLabel)
            .accessibilityAddTraits(showCard ? [.updatesFrequently, .isButton] : .updatesFrequently)
            .accessibilityHint(showCard ? "Choose which card pays." : "")
            .accessibilityAction { if showCard { onCards() } }
            if let e = syncNote {
                Text(e).font(TypeScale.caption).foregroundColor(Theme.bad).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.margin).padding(.bottom, (fix || addCardPill) ? Space.s : Space.l)
            }
            if let n = cardNote {
                Text(n).font(TypeScale.caption).foregroundColor(Theme.bad).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.margin).padding(.bottom, Space.s)
                    .accessibilityHidden(true)                      // already in the dock's spoken label
            }
            if fix {
                VStack(alignment: .leading, spacing: Space.s) {
                    if let problem {
                        Text(problem).font(TypeScale.caption).foregroundColor(Theme.bad).fixedSize(horizontal: false, vertical: true)
                            .accessibilityHidden(true)              // already in the dock's spoken label
                    }
                    Button(unpaired ? "Pair your bag" : (opensSettings ? "Open Settings" : "Connect bag")) {
                        if unpaired { onPair() } else if opensSettings { openAppSettings() } else { m.connectBag() }
                    }
                    .buttonStyle(PillStyle())
                }
                .padding(.horizontal, Space.margin).padding(.bottom, Space.s)
            } else if addCardPill {
                Button("Add card", action: onAddCard).buttonStyle(PillStyle())
                    .padding(.horizontal, Space.margin).padding(.bottom, Space.s)
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        .background(Theme.surface.ignoresSafeArea(edges: .bottom))
    }
}

/// Top edge of the dock: a hairline, or while leaving a coral line at half (first of the two fixes the
/// database needs), the same signal the store console shows. Fixed 2 pt frame, so nothing shifts.
private struct LeavingLine: View {
    let leaving: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .bottomLeading) {
                Rectangle().fill(Theme.hair).frame(height: 1)
                Rectangle().fill(Theme.light).frame(width: leaving ? g.size.width * 0.5 : 0, height: 2)
            }
            .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .frame(height: 2)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.6), value: leaving)
        .accessibilityHidden(true)
    }
}

/// The bag mark whose slot mirrors the bag's light: blinks on an accepted item, breathes while leaving.
struct BagLight: View {
    @EnvironmentObject var m: ShopModel
    let leaving: Bool
    var connected = true                       // no bag: drawn unlit grey, like the empty-bag drawing
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var blink = 0.0
    @State private var breathe = false

    var body: some View {
        SlotMark(size: 32, tone: connected || leaving ? Theme.ink : Theme.unlit,
                 lit: leaving ? (reduceMotion ? 1 : (breathe ? 1 : 0.3)) : blink)
            .id(leaving)                     // a new identity drops the repeating animation when you step back in
            .onChange(of: m.flash) { f in
                guard let f, !leaving, m.lines.first(where: { $0.sku == f.sku })?.sold == true else { return }
                blink = 1
                DispatchQueue.main.async { withAnimation(.easeOut(duration: 1.0)) { blink = 0 } }
            }
            .onChange(of: leaving) { startBreathing($0) }
            .onAppear { startBreathing(leaving) }
    }

    private func startBreathing(_ on: Bool) {
        guard on, !reduceMotion else {
            withAnimation(.linear(duration: 0)) { breathe = false }
            return
        }
        breathe = false
        DispatchQueue.main.async {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { breathe = true }
        }
    }
}
