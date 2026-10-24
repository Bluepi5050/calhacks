// PairingView.swift: "Pair your bag". Scan (every Bag It bag nearby, with its signal in words) -> tap one: the
// phone connects and writes HELLO, the bag's light blinks three times -> "Is the light on your bag blinking?" ->
// name it -> paired. Or "No bag with you? Use a demo bag". Used in setup and from Shop / Account.

import SwiftUI

@MainActor
final class PairingModel: ObservableObject {
    enum Phase: Hashable { case scanning, connecting, check, name, done }

    @Published private(set) var phase = Phase.scanning
    @Published private(set) var nearby: [BagLink.Nearby] = []
    @Published private(set) var problem: String?              // Bluetooth off / not allowed
    @Published private(set) var noneFound = false
    @Published private(set) var connectError: String?
    @Published private(set) var blink = 0                     // bumps each time HELLO goes out: the screen blinks with the bag
    @Published private(set) var chosen: BagLink.Nearby?
    @Published private(set) var demo = false
    private var hello: BagHello?
    private var timer: Task<Void, Never>?
    private var helloTimer: Task<Void, Never>?
    private var connectTimer: Task<Void, Never>?
    private var listTimer: Task<Void, Never>?
    private var link: BagLink { ShopModel.shared.link }
    private var kept = false

    /// What gets paired: the name the bag gave in its HELLO reply, else the name it advertises.
    var bagId: String { hello?.name ?? chosen?.bleName ?? "SmartBag-01" }
    var displayName: String { BagProtocol.displayName(bagId) }

    func start() {
        link.onNearby = { [weak self] list in self?.nearby = list; if !list.isEmpty { self?.noneFound = false } }
        link.onPairState = { [weak self] s in self?.linkChanged(s) }
        link.onHello = { [weak self] h in self?.hello = h }
        scan()
    }

    func scan(keepError: Bool = false) {
        phase = .scanning
        chosen = nil
        hello = nil
        noneFound = false
        if !keepError { connectError = nil }
        link.beginPairing()
        listTimer?.cancel()
        listTimer = Task { [weak self] in                             // bags that go quiet drop off the list
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard let self, !Task.isCancelled, self.phase == .scanning else { return }
                self.link.refreshNearby()
            }
        }
        timer?.cancel()
        timer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            guard let self, !Task.isCancelled, self.phase == .scanning, self.nearby.isEmpty, self.problem == nil else { return }
            self.noneFound = true
        }
    }

    func choose(_ n: BagLink.Nearby) {
        chosen = n
        connectError = nil
        phase = .connecting
        link.pairConnect(n.id)
        connectTimer?.cancel()
        connectTimer = Task { [weak self] in                          // a bag that walked off must not spin forever
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard let self, !Task.isCancelled, self.phase == .connecting else { return }
            self.connectError = "Couldn't reach \(BagProtocol.displayName(n.bleName)). Try again."
            self.link.pairAnother()
            self.scan(keepError: true)
        }
    }

    private func linkChanged(_ s: BagState) {
        switch s {
        case .problem(let msg):
            if phase == .connecting {
                connectTimer?.cancel()
                connectError = "Couldn't connect to \(displayName). Try again."
                scan(keepError: true)
            } else if phase == .scanning {
                problem = msg
            }
        case .connected:
            problem = nil
            if phase == .connecting {
                connectTimer?.cancel()
                phase = .check
                blink += 1                                         // BagLink wrote HELLO the moment it connected
                startHello()
            }
        case .searching:
            problem = nil
        default:
            break
        }
    }

    private func startHello() {
        helloTimer?.cancel()
        helloTimer = Task { [weak self] in                             // again every 3 s, so the light keeps answering
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                guard let self, !Task.isCancelled, self.phase == .check else { return }
                self.link.sendHello()
                self.blink += 1
            }
        }
    }

    /// Back from "Name your bag": the bag is still connected, so just ask it to blink again.
    func backToCheck() {
        phase = .check
        link.sendHello()
        blink += 1
        startHello()
    }

    func notMine() {
        helloTimer?.cancel()
        link.pairAnother()
        phase = .scanning
        chosen = nil
        hello = nil
        scan()
    }

    func confirm() { helloTimer?.cancel(); phase = .name }

    func pair(name: String, account: AccountModel) async throws {
        try await account.pairBag(bagId: bagId, name: name)
        kept = true
        phase = .done
    }

    func useDemo(account: AccountModel) async throws {
        try await account.pairBag(bagId: SmartBagConfig.demoBagId, name: "Demo bag")
        demo = true
        kept = false
        phase = .done
    }

    /// Leaving the flow. A freshly paired bag keeps its connection; otherwise the shop picks its own bag back up.
    func finish() {
        timer?.cancel(); helloTimer?.cancel(); connectTimer?.cancel(); listTimer?.cancel()
        link.endPairing(keep: kept)
        if !kept { ShopModel.shared.resumeBag() }
    }
}

struct PairingFlow: View {
    /// In setup: a step bar and "Set up later"; elsewhere: Cancel.
    var step: (Int, Int)? = nil
    let onDone: () -> Void
    @EnvironmentObject var account: AccountModel
    @StateObject private var p = PairingModel()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var name = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: 0) {
            top.padding(.horizontal, Space.margin).padding(.top, Space.s)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    switch p.phase {
                    case .scanning, .connecting: scanPage
                    case .check: checkPage
                    case .name: namePage
                    case .done: donePage
                    }
                }
                .padding(.horizontal, Space.margin)
                .padding(.bottom, Space.xl)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollDismissesKeyboard(.interactively)
            .id(p.phase == .connecting ? .scanning : p.phase)
            .transition(reduceMotion ? .opacity : .asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
        }
        .background(Theme.bg.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) { dock }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: p.phase)
        .onAppear { p.start() }
        .onDisappear { p.finish() }
    }

    // MARK: top bar

    @ViewBuilder private var top: some View {
        switch p.phase {
        case .check:
            FlowBar(back: { error = nil; p.notMine() }, step: step)
        case .name:
            FlowBar(back: { error = nil; p.backToCheck() }, step: step)
        case .done:
            FlowBar(step: step)
        default:
            if let step { FlowBar(step: step) } else { FlowBar(close: onDone) }
        }
    }

    // MARK: 1 scan

    @ViewBuilder private var scanPage: some View {
        ScreenTitle(title: "Pair your bag", lead: "Turn your bag on and hold it near your phone.").padding(.top, Space.xl)
        if let problem = p.problem {
            Text(problem).font(TypeScale.body).foregroundColor(Theme.bad)
                .fixedSize(horizontal: false, vertical: true).padding(.top, Space.xxl)
        } else {
            SectionLabel(text: "Bags nearby").padding(.top, Space.xxl)
            if p.nearby.isEmpty {
                if p.noneFound {
                    VStack(alignment: .leading, spacing: Space.xs) {
                        Text("No bags found").font(TypeScale.bodyStrong).foregroundColor(Theme.ink)
                        Text("Make sure it's switched on and close to your phone.").font(TypeScale.caption).foregroundColor(Theme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                        Button("Scan again") { p.scan() }.buttonStyle(InlineAction()).padding(.leading, -Space.s)
                    }
                    .padding(.top, Space.l)
                } else {
                    HStack(spacing: Space.m) {
                        ProgressView().tint(Theme.muted)
                        Text("Looking for bags").font(TypeScale.caption).foregroundColor(Theme.muted)
                    }
                    .frame(minHeight: 56)
                    .accessibilityElement(children: .combine)
                }
            } else {
                ForEach(p.nearby) { n in bagRow(n) }
            }
            if let e = p.connectError {
                Text(e).font(TypeScale.caption).foregroundColor(Theme.bad).fixedSize(horizontal: false, vertical: true).padding(.top, Space.m)
            }
        }
        if let error {
            Text(error).font(TypeScale.caption).foregroundColor(Theme.bad).fixedSize(horizontal: false, vertical: true).padding(.top, Space.m)
        }
    }

    private func bagRow(_ n: BagLink.Nearby) -> some View {
        let yours = account.state?.bags.contains { $0.bagId == n.bleName } == true
        let connecting = p.phase == .connecting && p.chosen?.id == n.id
        return RowButton(action: { if p.phase == .scanning { p.choose(n) } }) {
            SlotMark(size: 24, tone: Theme.ink, lit: 0)
            VStack(alignment: .leading, spacing: Space.xs) {
                Text(BagProtocol.displayName(n.bleName)).font(TypeScale.body).foregroundColor(Theme.ink)
                Text(BagProtocol.signal(rssi: n.rssi)).font(TypeScale.caption).foregroundColor(Theme.muted)
            }
            Spacer(minLength: Space.s)
            if connecting { ProgressView().tint(Theme.muted) }
            else if yours { Text("Yours").font(TypeScale.caption).foregroundColor(Theme.faint) }
        }
        .disabled(p.phase == .connecting)
        .accessibilityLabel("\(BagProtocol.displayName(n.bleName)), \(BagProtocol.signal(rssi: n.rssi))\(yours ? ", yours" : "")")
        .accessibilityHint("Connects and makes its light blink.")
    }

    // MARK: 2 check the light

    @ViewBuilder private var checkPage: some View {
        BlinkingMark(tick: p.blink).padding(.top, Space.xxxl)
        ScreenTitle(title: "Is the light on your bag blinking?",
                    lead: "\(p.displayName) blinks its light three times.").padding(.top, Space.xxl)
    }

    // MARK: 3 name

    @ViewBuilder private var namePage: some View {
        ScreenTitle(title: "Name your bag").padding(.top, Space.xl)
        FormField(label: "Name", text: $name, hint: p.displayName).padding(.top, Space.xxl)
            .onAppear { if name.isEmpty { name = account.suggestedBagName } }
    }

    // MARK: 4 done

    @ViewBuilder private var donePage: some View {
        SlotMark(size: 96, lit: p.demo ? 0 : 1).padding(.leading, -12).padding(.top, Space.xxxl)
        ScreenTitle(title: p.demo ? "Demo bag added" : "Bag paired",
                    lead: p.demo ? "It counts as connected, so you can try Bag It without a bag."
                                 : "Your bag is ready. Drop something in and it shows up here.").padding(.top, Space.xxl)
            .onAppear { Haptics.notice(.success) }
    }

    // MARK: dock

    @ViewBuilder private var dock: some View {
        switch p.phase {
        case .scanning, .connecting:
            VStack(spacing: Space.xs) {
                // Settings can grant Bluetooth, not switch it on: when it's just off, scanning resumes by itself.
                if p.problem?.contains("Settings") == true {
                    Button("Open Settings", action: openAppSettings).buttonStyle(PillStyle())
                }
                if step != nil {
                    Button("Set up later") { onDone() }.buttonStyle(TextAction()).disabled(busy)
                }
                Button("No bag with you? Use a demo bag") { demo() }.buttonStyle(TextAction()).disabled(busy || p.phase == .connecting)
            }
            .padding(.horizontal, Space.margin).padding(.top, Space.m).padding(.bottom, Space.s)
            .background(Theme.bg.ignoresSafeArea(edges: .bottom))
        case .check:
            VStack(spacing: Space.s) {
                Button("Yes, that's my bag") { p.confirm() }.buttonStyle(PillStyle())
                Button("No, try another") { p.notMine() }.buttonStyle(PillStyle(primary: false))
            }
            .padding(.horizontal, Space.margin).padding(.top, Space.m).padding(.bottom, Space.s)
            .background(Theme.bg.ignoresSafeArea(edges: .bottom))
        case .name:
            FormDock(title: "Pair bag", busy: busy, error: error) { save() }
        case .done:
            FormDock(title: step == nil ? "Done" : "Continue") { onDone() }
        }
    }

    private func save() {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        busy = true; error = nil
        Task {
            do { try await p.pair(name: n.isEmpty ? account.suggestedBagName : n, account: account) } catch { self.error = userMessage(error) }
            busy = false
        }
    }

    private func demo() {
        busy = true; error = nil
        Task {
            do { try await p.useDemo(account: account) } catch { self.error = userMessage(error) }
            busy = false
        }
    }
}

/// The bag mark whose slot blinks three times each time HELLO goes out, in step with the bag's own light.
private struct BlinkingMark: View {
    let tick: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var lit = 0.0

    var body: some View {
        SlotMark(size: 96, lit: reduceMotion ? 1 : lit)
            .padding(.leading, -12)
            .onAppear { run() }
            .onChange(of: tick) { _ in run() }
    }

    private func run() {
        guard !reduceMotion else { return }
        for i in 0 ..< 3 {                                       // firmware: 180 ms on, 180 ms off, three times
            let on = Double(i) * 0.36
            DispatchQueue.main.asyncAfter(deadline: .now() + on) { withAnimation(.easeOut(duration: 0.06)) { lit = 1 } }
            DispatchQueue.main.asyncAfter(deadline: .now() + on + 0.18) { withAnimation(.easeIn(duration: 0.1)) { lit = 0 } }
        }
    }
}
