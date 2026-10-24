// TripsView.swift: every walk-out receipt of the account (all its phones), newest first, grouped by day.
// A row opens the same receipt as Last trip, quietly (no peak).

import SwiftUI

struct TripsView: View {
    @EnvironmentObject var account: AccountModel
    @State private var open: Receipt?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(title: "Trips").padding(.top, Space.xl)
                if let trips = account.trips {
                    if trips.isEmpty {
                        EmptyState(title: "No trips yet", why: "Your receipts show up here after you walk out.").padding(.top, Space.xl)
                    } else {
                        ForEach(groups(trips), id: \.label) { g in
                            SectionLabel(text: g.label).padding(.top, Space.xxl)
                            ForEach(g.trips) { r in row(r) }
                        }
                    }
                } else if account.tripsError == nil {
                    HStack(spacing: Space.m) {
                        ProgressView().tint(Theme.muted)
                        Text("Loading your trips").font(TypeScale.caption).foregroundColor(Theme.muted)
                    }
                    .frame(minHeight: 56).padding(.top, Space.xxl)
                    .accessibilityElement(children: .combine)
                }
                if let e = account.tripsError {
                    HStack(alignment: .center, spacing: Space.m) {
                        Text("Couldn't load your trips. \(e)").font(TypeScale.caption).foregroundColor(Theme.bad)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: Space.s)
                        Button("Try again") { Task { await account.loadTrips() } }.buttonStyle(InlineAction()).padding(.trailing, -Space.s)
                    }
                    .padding(.top, Space.xl)
                }
            }
            .padding(.horizontal, Space.margin)
            .padding(.bottom, Space.xxl)
        }
        .refreshable { await account.loadTrips() }
        .background(Theme.bg.ignoresSafeArea())
        .task { await account.loadTrips() }
        .sheet(item: $open) { r in
            ReceiptScreen(receipt: r, live: false) { open = nil }.presentationDragIndicator(.visible)
        }
    }

    private func row(_ r: Receipt) -> some View {
        RowButton(action: { open = r }) {
            VStack(alignment: .leading, spacing: Space.xs) {
                Text(r.storeName).font(TypeScale.body).foregroundColor(Theme.ink).lineLimit(2)
                (Text(clockTime(r.paidAt) + " · " + items(r.itemCount)).foregroundColor(Theme.muted)
                 + Text(r.isPaid ? "" : " · Payment due").foregroundColor(Theme.bad))
                    .font(TypeScale.captionNum)
            }
            Spacer(minLength: Space.s)
            Text(money(r.totalCents)).font(TypeScale.bodyMono).foregroundColor(Theme.ink)
        }
        .accessibilityLabel("\(r.storeName), \(clockTime(r.paidAt)), \(items(r.itemCount)), \(money(r.totalCents))\(r.isPaid ? "" : ", payment due")")
        .accessibilityHint("Shows the receipt and exit pass.")
    }

    struct DayGroup { let label: String; let trips: [Receipt] }

    /// Today, Yesterday, then "Thursday, Oct 22".
    private func groups(_ trips: [Receipt]) -> [DayGroup] {
        var out: [DayGroup] = []
        for r in trips {
            let l = dayLabel(r.paidAt)
            if let i = out.firstIndex(where: { $0.label == l }) { out[i] = DayGroup(label: l, trips: out[i].trips + [r]) }
            else { out.append(DayGroup(label: l, trips: [r])) }
        }
        return out
    }
}

func dayLabel(_ d: Date) -> String {
    let cal = Calendar.current
    if cal.isDateInToday(d) { return "Today" }
    if cal.isDateInYesterday(d) { return "Yesterday" }
    let f = DateFormatter()
    f.setLocalizedDateFormatFromTemplate("EEEEMMMd")
    return f.string(from: d)
}

func clockTime(_ d: Date) -> String { DateFormatter.localizedString(from: d, dateStyle: .none, timeStyle: .short) }
