// CardViews.swift: the wallet. Add a card (checked on the phone; only brand, last four and expiry are sent), the
// card picker behind the shop's "Paying with", the Wallet tab and a card's sheet (pay with it / remove it).
// Payments are simulated.

import SwiftUI

struct AddCardScreen: View {
    /// In setup: a step bar and "Add later"; elsewhere a Cancel.
    var step: (Int, Int)? = nil
    let onDone: () -> Void
    @EnvironmentObject var account: AccountModel
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.scenePhase) private var phase
    // The number and CVC exist only here, while typing, and are cleared after every submit.
    @State private var number = ""
    @State private var expiry = ""
    @State private var cvc = ""
    @State private var nickname = ""
    @State private var makeDefault = false
    @State private var errors = CardErrors()
    @State private var serverError: String?
    @State private var busy = false

    private var firstCard: Bool { account.state?.cards.isEmpty ?? true }
    private var brand: CardBrand { CardBrand.detect(CardCheck.digits(number)) }

    var body: some View {
        VStack(spacing: 0) {
            Group {
                if let step { FlowBar(step: step) } else { FlowBar(close: onDone) }
            }
            .padding(.horizontal, Space.margin).padding(.top, Space.s)
            ScrollView {
                VStack(alignment: .leading, spacing: Space.xl) {
                    ScreenTitle(title: "Add a card").padding(.top, Space.l)
                    FormField(label: "Card number", text: $number, kind: .number, placeholder: "1234 1234 1234 1234",
                              error: errors.number, content: .creditCardNumber,
                              trailing: CardCheck.digits(number).count >= 2 ? brand.rawValue : nil, sensitive: true)
                        .onChange(of: number) { v in let f = CardCheck.formatNumber(v); if f != v { number = f } }
                    let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: Space.xl))
                                                              : AnyLayout(HStackLayout(alignment: .top, spacing: Space.xl))
                    layout {
                        FormField(label: "Expiry", text: $expiry, kind: .number, placeholder: "MM/YY", error: errors.expiry, sensitive: true)
                            .onChange(of: expiry) { v in let f = CardCheck.formatExpiry(v); if f != v { expiry = f } }
                        FormField(label: "Security code", text: $cvc, kind: .number, placeholder: brand == .amex ? "4 digits" : "3 digits",
                                  error: errors.cvc, sensitive: true)
                            .onChange(of: cvc) { v in let f = CardCheck.formatCVC(v, brand: brand); if f != v { cvc = f } }
                    }
                    FormField(label: "Nickname", text: $nickname, placeholder: "Optional")
                    Toggle(isOn: firstCard ? .constant(true) : $makeDefault) {
                        VStack(alignment: .leading, spacing: Space.xs) {
                            Text("Pay with this card").font(TypeScale.body).foregroundColor(Theme.ink)
                            if firstCard {
                                Text("Your first card pays when you walk out.").font(TypeScale.caption).foregroundColor(Theme.muted)
                            }
                        }
                    }
                    .tint(Theme.ink)
                    .disabled(firstCard)
                    Text("Payments are simulated. Only the card type and last four digits are saved; the full number never leaves your phone.")
                        .font(TypeScale.caption).foregroundColor(Theme.faint).fixedSize(horizontal: false, vertical: true)
                    Button("Use demo card", action: fillDemo).buttonStyle(InlineAction()).padding(.leading, -Space.s).padding(.top, -Space.m)
                }
                .padding(.horizontal, Space.margin)
                .padding(.bottom, Space.xl)
                .disabled(busy)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(Theme.bg.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            FormDock(title: "Add card", busy: busy, error: serverError, action: submit) {
                if step != nil {
                    Button("Add later") { clear(); onDone() }.buttonStyle(TextAction()).disabled(busy)
                }
            }
        }
        // The app switcher snapshot must not show the card number.
        .overlay { if phase != .active { Theme.bg.ignoresSafeArea() } }
        .onAppear { makeDefault = firstCard }
        .onDisappear { clear() }
    }

    private func fillDemo() {
        number = CardCheck.demo.number; expiry = CardCheck.demo.expiry; cvc = CardCheck.demo.cvc
        if nickname.isEmpty { nickname = CardCheck.demo.nickname }
        errors = CardErrors()
    }

    private func clear() { number = ""; cvc = "" }

    private func submit() {
        serverError = nil
        let d = Calendar(identifier: .gregorian).dateComponents([.year, .month], from: Date())
        let checked = CardCheck.validate(number: number, expiry: expiry, cvc: cvc, now: (d.year ?? 2026, d.month ?? 1))
        switch checked {
        case .failure(let e):
            errors = e
        case .success(let draft):
            errors = CardErrors()
            clear()                                        // the full number and CVC are done with
            busy = true
            Task {
                do {
                    try await account.addCard(draft, nickname: nickname, makeDefault: firstCard || makeDefault)
                    busy = false
                    onDone()
                } catch {
                    serverError = userMessage(error)
                    busy = false
                }
            }
        }
    }
}

/// Behind the dock's "Paying with": tap a card and it pays from now on.
struct CardPickerSheet: View {
    @EnvironmentObject var account: AccountModel
    @Environment(\.dismiss) private var dismiss
    @State private var adding = false
    @State private var busy: UInt64?
    @State private var error: String?

    var body: some View {
        if adding {
            AddCardScreen { dismiss() }
        } else {
            VStack(alignment: .leading, spacing: 0) {
                FlowBar(close: { dismiss() }, closeLabel: "Done").padding(.top, Space.s)
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ScreenTitle(title: "Pay with").padding(.top, Space.l)
                        SectionLabel(text: "Your cards").padding(.top, Space.xxl)
                        ForEach(account.state?.cards ?? []) { c in
                            VStack(spacing: 0) {
                                Button { pick(c) } label: {
                                    HStack(spacing: Space.m) {
                                        CardLabel(card: c)
                                        if busy == c.id { ProgressView().tint(Theme.muted) }
                                        else if c.isDefault {
                                            Image(systemName: "checkmark").font(TypeScale.bodyStrong).foregroundColor(Theme.ink).accessibilityHidden(true)
                                        }
                                    }
                                    .padding(.vertical, Space.m).frame(minHeight: 56).contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityAddTraits(c.isDefault ? .isSelected : [])
                                Hairline()
                            }
                        }
                        if let error {
                            Text(error).font(TypeScale.caption).foregroundColor(Theme.bad).padding(.top, Space.m)
                        }
                        Button("Add a card") { adding = true }.buttonStyle(PillStyle(primary: false)).padding(.top, Space.xl)
                    }
                    .padding(.bottom, Space.xl)
                }
            }
            .padding(.horizontal, Space.margin)
            .background(Theme.bg.ignoresSafeArea())
            .disabled(busy != nil)
        }
    }

    private func pick(_ c: CardInfo) {
        guard !c.isDefault else { dismiss(); return }
        busy = c.id; error = nil
        Task {
            do { try await account.setDefaultCard(c.id); dismiss() } catch { self.error = userMessage(error) }
            busy = nil
        }
    }
}

// MARK: - Wallet tab

struct WalletView: View {
    @EnvironmentObject var account: AccountModel
    @State private var adding = false
    @State private var open: CardInfo?

    var body: some View {
        let cards = account.state?.cards ?? []
        let def = cards.first { $0.isDefault }
        let others = cards.filter { !$0.isDefault }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(title: "Wallet").padding(.top, Space.xl)
                if cards.isEmpty {
                    EmptyState(title: "No cards yet", why: "Add a card and leaving the store pays for you.").padding(.top, Space.xl)
                } else {
                    if let def {
                        SectionLabel(text: "Pays when you walk out").padding(.top, Space.xxl)
                        RowButton(action: { open = def }) { CardLabel(card: def, large: true) }
                            .padding(.vertical, Space.xs)
                    }
                    if !others.isEmpty {
                        SectionLabel(text: "Other cards").padding(.top, Space.xxl)
                        ForEach(others) { c in RowButton(action: { open = c }) { CardLabel(card: c) } }
                    }
                }
                Button("Add a card") { adding = true }.buttonStyle(PillStyle(primary: !cards.isEmpty ? false : true)).padding(.top, Space.xxl)
                Text("Payments are simulated. No money moves.")
                    .font(TypeScale.caption).foregroundColor(Theme.faint).padding(.top, Space.l)
            }
            .padding(.horizontal, Space.margin)
            .padding(.bottom, Space.xxl)
        }
        .background(Theme.bg.ignoresSafeArea())
        .sheet(isPresented: $adding) { AddCardScreen { adding = false }.appModels() }
        .sheet(item: $open) { c in
            CardSheet(cardId: c.id) { open = nil }.presentationDetents([.medium, .large]).appModels()
        }
    }
}

/// One card: make it the one that pays, or remove it (confirmed in place, saying which card takes over).
struct CardSheet: View {
    let cardId: UInt64
    let onClose: () -> Void
    @EnvironmentObject var account: AccountModel
    @State private var confirming = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            FlowBar(close: onClose, closeLabel: "Done").padding(.top, Space.s)
            if let card = account.state?.cards.first(where: { $0.id == cardId }) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        CardLabel(card: card, large: true).padding(.top, Space.l)
                        Text(card.isDefault ? "Pays when you walk out." : "Not used for walk-outs.")
                            .font(TypeScale.caption).foregroundColor(Theme.muted).padding(.top, Space.m)
                        if confirming {
                            let next = account.successor(removing: card)
                            Text("Remove \(card.brand) •••• \(card.last4)?").font(TypeScale.bodyStrong).foregroundColor(Theme.ink)
                                .padding(.top, Space.xxl)
                            Text(card.isDefault ? (next.map { "\($0.label) will pay when you walk out." }
                                                   ?? "You'll have no card, so walking out means paying at the store.")
                                                : "It won't be used again.")
                                .font(TypeScale.caption).foregroundColor(Theme.muted).fixedSize(horizontal: false, vertical: true)
                                .padding(.top, Space.xs)
                        }
                        if let error {
                            Text(error).font(TypeScale.caption).foregroundColor(Theme.bad).padding(.top, Space.m)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                VStack(spacing: Space.s) {
                    if confirming {
                        Button("Remove card") { run { try await account.removeCard(card.id); onClose() } }
                            .buttonStyle(PillStyle(destructive: true))
                        Button("Keep card") { confirming = false }.buttonStyle(PillStyle(primary: false))
                    } else {
                        if !card.isDefault {
                            Button("Pay with this card") { run { try await account.setDefaultCard(card.id) } }.buttonStyle(PillStyle())
                        }
                        Button("Remove card") { confirming = true }.buttonStyle(PillStyle(primary: false, destructive: true))
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
            confirming = false
        }
    }
}
