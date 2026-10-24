// AccountModel.swift: the shopper's Bag It account on this phone (module v5): sign up / log in, the wallet of
// saved cards, paired bags, trips, and the setup steps that follow signing in.
//
// Foundation only (plus Combine on iOS), so tools/verify-linux.sh drives it against the live database.
// The phone keeps its own anonymous identity token (Keychain); signing in links that identity to the account.
// Cached here: the last AccountState (name, email, card brands / last four, bags) for a fast signed-in launch.
// Never stored: the password, a card number or a CVC.

import Foundation
import Combine

enum SetupStep: String, Codable, CaseIterable {
    case permissions, pair, card
}

@MainActor
final class AccountModel: ObservableObject {
    static let shared = AccountModel(api: ShopModel.shared.api, shop: ShopModel.shared)

    /// nil = not known yet (first launch, nothing cached). Signed out = `.signedOut`.
    @Published private(set) var state: AccountState?
    /// Remaining setup steps after signing in, in order; empty = go to the tabs.
    @Published private(set) var setup: [SetupStep] = []
    /// The whole setup this time (for the step bar), fixed when it starts.
    @Published private(set) var setupPlan: [SetupStep] = []
    @Published private(set) var trips: [Receipt]?            // nil = not loaded yet
    @Published private(set) var tripsError: String?
    @Published private(set) var tripsLoading = false

    let api: SpacetimeClient
    private weak var shop: ShopModel?
    private let defaults: UserDefaults
    /// Bumped by every sign-in, sign-up, sign-out and delete, so a slow refresh can't undo them.
    private var epoch = 0
    private static let cacheKey = "account_cache"
    private static let setupKey = "setup_steps"
    private static let planKey = "setup_plan"

    var signedIn: Bool { state?.signedIn == true }

    init(api: SpacetimeClient, shop: ShopModel?, defaults: UserDefaults = .standard) {
        self.api = api
        self.shop = shop
        self.defaults = defaults
        if let d = defaults.data(forKey: Self.cacheKey), let s = try? JSONDecoder().decode(AccountState.self, from: d), s.signedIn {
            state = s
            setup = Self.steps(defaults.stringArray(forKey: Self.setupKey))
            setupPlan = Self.steps(defaults.stringArray(forKey: Self.planKey))
            shop?.accountChanged(s)
        }
    }

    private static func steps(_ raw: [String]?) -> [SetupStep] { (raw ?? []).compactMap(SetupStep.init(rawValue:)) }

    /// Signed-in launch: the cached state is already on screen; this brings it up to date. A failure keeps the cache.
    func refresh() async {
        let e = epoch
        guard let s = try? await api.account(), e == epoch else { return }
        apply(s)
    }

    // MARK: sign up / log in / out

    func signUp(name: String, email: String, password: String) async throws {
        epoch += 1
        let s = try await api.signUp(name: name, email: email, password: password)
        beginSetup(for: s)
        apply(s)
        shop?.resendBag()
    }

    func signIn(email: String, password: String) async throws {
        epoch += 1
        let s = try await api.signIn(email: email, password: password)
        beginSetup(for: s)
        apply(s)
        shop?.resendBag()
    }

    // Mid-trip the module refuses account changes ("You're in the middle of a trip. ..."): the call throws, nothing
    // here changes (state, cache, token) and the screen shows that message. Only a confirmed reply is applied.
    func signOut() async throws {
        epoch += 1
        let s = try await api.signOut()
        apply(s)
    }

    func deleteAccount(password: String) async throws {
        epoch += 1
        let s = try await api.deleteAccount(password: password)
        apply(s)
    }

    /// Sign-up and log-in share one rule: ask only for what this phone and account are missing.
    private func beginSetup(for s: AccountState) {
        var steps: [SetupStep] = []
        if !defaults.bool(forKey: "onboarded") { steps.append(.permissions) }
        if s.bags.isEmpty { steps.append(.pair) }
        if s.cards.isEmpty { steps.append(.card) }
        setupPlan = steps
        setup = steps
        saveSetup()
    }

    /// The current setup step is done (or skipped).
    func finishStep(_ step: SetupStep) {
        setup.removeAll { $0 == step }
        if setup.isEmpty { setupPlan = [] }
        saveSetup()
    }

    private func saveSetup() {
        defaults.set(setup.map(\.rawValue), forKey: Self.setupKey)
        defaults.set(setupPlan.map(\.rawValue), forKey: Self.planKey)
    }

    // MARK: wallet

    func addCard(_ draft: CardDraft, nickname: String, makeDefault: Bool) async throws {
        apply(try await api.addCard(brand: draft.brand, last4: draft.last4, expMonth: draft.expMonth, expYear: draft.expYear,
                                    nickname: nickname.trimmingCharacters(in: .whitespacesAndNewlines), makeDefault: makeDefault))
    }
    func removeCard(_ id: UInt64) async throws { apply(try await api.removeCard(id: id)) }
    func setDefaultCard(_ id: UInt64) async throws { apply(try await api.setDefaultCard(id: id)) }

    /// Which card pays if `card` is removed: the module promotes the oldest remaining card.
    func successor(removing card: CardInfo) -> CardInfo? {
        guard card.isDefault else { return state?.defaultCard }
        return state?.cards.filter { $0.id != card.id }.min { $0.id < $1.id }
    }

    // MARK: bags

    func pairBag(bagId: String, name: String) async throws { apply(try await api.pairBag(bagId: bagId, name: name)) }
    func renameBag(_ id: UInt64, name: String) async throws { apply(try await api.renameBag(id: id, name: name)) }
    func unpairBag(_ id: UInt64) async throws { apply(try await api.unpairBag(id: id)) }
    func setName(_ name: String) async throws { apply(try await api.setName(name)) }

    /// "<first name>'s bag", or "My bag".
    var suggestedBagName: String {
        let first = state?.firstName ?? ""
        return first.isEmpty ? "My bag" : "\(first)'s bag"
    }

    // MARK: trips

    func loadTrips() async {
        guard signedIn, !tripsLoading else { return }
        tripsLoading = true
        defer { tripsLoading = false }
        let e = epoch
        do {
            let t = try await api.trips(limit: 50)
            guard e == epoch, signedIn else { return }
            trips = t
            tripsError = nil
        } catch {
            tripsError = userMessage(error)
        }
    }

    // MARK: state

    private func apply(_ s: AccountState) {
        let was = state
        state = s
        if s.signedIn {
            if let d = try? JSONEncoder().encode(s) { defaults.set(d, forKey: Self.cacheKey) }
        } else {
            defaults.removeObject(forKey: Self.cacheKey)
            setup = []
            setupPlan = []
            saveSetup()
            trips = nil
            tripsError = nil
        }
        if was != s { shop?.accountChanged(s) }
    }
}
