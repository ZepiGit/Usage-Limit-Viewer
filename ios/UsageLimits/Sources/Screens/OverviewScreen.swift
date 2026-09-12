import SwiftUI
import UsageLimitsKit

/// The screen the app opens on: what is left, and when it comes back.
///
/// Everything shown here is decided in `UsageLimitsKit` — which accounts lead, which two
/// windows each shows, what counts as stale. This file only lays them out, which is what keeps
/// the two platforms saying the same thing about the same account.
struct OverviewScreen: View {

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var store: UsageStore

    /// Whether the list is in reordering mode.
    ///
    /// Owned here rather than taken from the environment, because leaving the screen should end
    /// it: an edit mode that persists across tabs leaves a user back on a list whose rows do not
    /// respond to a tap, with no memory of having asked for that.
    @State private var editMode: EditMode = .inactive

    var body: some View {
        NavigationStack {
            List {
                Section {
                    SummaryCard(snapshot: store.glance, now: store.now)
                        .plainRow()
                        // Never draggable: it is the headline, not one of the accounts, and a
                        // list that lets it be dropped between two cards makes the whole gesture
                        // read as broken.
                        .moveDisabled(true)
                }

                Section {
                    if store.accounts.isEmpty {
                        EmptyStateCard().plainRow()
                    } else {
                        ForEach(store.orderedAccounts) { account in
                            AccountCard(
                                account: account,
                                provider: store.accounts.first { $0.account.id == account.id }?.account.provider,
                                now: store.now,
                                tier: store.settings.showSubscriptionTier
                                    ? store.tierLabel(accountID: account.id) : nil,
                                renewal: store.settings.showRenewalTime
                                    ? store.renewalLabel(accountID: account.id, now: store.now)
                                    : nil)
                                .plainRow()
                        }
                        .onMove(perform: move)
                    }
                }
            }
            .animation(reduceMotion ? nil : Animation.easeInOut(duration: 0.24), value: store.orderedAccounts.map(\.id))
            .readableWidth()
            .listStyle(.plain)
            .environment(\.editMode, $editMode)
            .scrollContentBackground(.hidden)
            .background(UsageColors.background)
            .navigationTitle("Overview")
            .marketingDemoLabel()
            .toolbar {
                // `|| editMode.isEditing` is the half that matters. Gated on the count alone,
                // the button — and with it the only way OUT of reorder mode — vanished the
                // moment a second account was disconnected on another tab: a TabView child is
                // not torn down on a switch, so `editMode` came back still `.active`, the last
                // card stayed in drag mode, and pull-to-refresh was suppressed with no control
                // left to turn any of it off. Killing the app was the only exit.
                if store.accounts.count > 1 || editMode.isEditing {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(editMode.isEditing ? "Done" : "Reorder") {
                            withAnimation(reduceMotion ? nil : Animation.easeInOut(duration: 0.2)) { editMode = editMode.isEditing ? .inactive : .active }
                        }
                    }
                }
            }
            // And ended outright once there is nothing left to reorder, so the mode cannot
            // outlive its purpose even while the button is on screen.
            .onChange(of: store.accounts.count) { count in
                if count < 2 { editMode = .inactive }
            }
            // Suspended while reordering: a pull that starts on a row being dragged is a refresh
            // the user did not ask for, and it would replace the list under their finger.
            .refreshable { if !editMode.isEditing { await store.refresh() } }
        }
    }

    /// Hands the store the WHOLE new order rather than the pair that swapped.
    ///
    /// The repository renumbers every row it is given, so sending the complete list is what
    /// makes a partially-applied write impossible — and the list is short enough that there is
    /// nothing to save by sending less.
    private func move(from offsets: IndexSet, to destination: Int) {
        var ids = store.orderedAccounts.map(\.id)
        ids.move(fromOffsets: offsets, toOffset: destination)
        Task { await store.reorderAccounts(ids: ids) }
    }
}

private extension View {
    /// A row that looks like the card it contains rather than like a table row.
    ///
    /// The overview was a `ScrollView` of cards until reordering arrived. `List` is what gives
    /// the drag its native feel — the lift, the gap, the haptic — and none of that is worth
    /// reimplementing on a drag gesture; stripping the row chrome is what keeps the design the
    /// ScrollView had.
    func plainRow() -> some View {
        self
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
    }
}

/// The headline: one status word, the tightest short and long window, and the next reset.
private struct SummaryCard: View {

    let snapshot: GlanceSnapshot
    let now: Date

    var body: some View {
        UsageCard {
            HStack(alignment: .firstTextBaseline) {
                Text(healthyLabel)
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(snapshot.accounts.contains { $0.connectionStatus == .reconnectRequired } ? UsageColors.red : UsageColors.textPrimary)
                Spacer()
                Text(freshness)
                    .font(.footnote)
                    .foregroundStyle(UsageColors.textTertiary)
            }

            Text(subtitle)
                .font(.subheadline)
                .foregroundStyle(UsageColors.textSecondary)
        }
    }

    /// Healthy over total, coloured by the fleet's worst severity.
    ///
    /// This card used to lead with the severity WORD — "Exhausted" — printed in the colour that
    /// already said so, and then repeat the two tightest windows underneath. Both went: the
    /// account cards below carry every window with its own bar, so the summary was restating the
    /// first card. What is left is the pair of numbers that cannot be read off anything else,
    /// how many accounts are fine and when the next limit rolls over.
    ///
    /// Counted through `severity(at:staleAfter:)` rather than the stored value, so an account
    /// whose numbers went stale while this screen was open stops counting as healthy.
    private var healthyLabel: String {
        let connected = snapshot.accounts.filter { $0.connectionStatus == .connected }.count
        return "\(connected)/\(snapshot.accountCount) connected"
    }

    private var subtitle: String {
        let accounts = snapshot.accountCount == 1 ? "1 account" : "\(snapshot.accountCount) accounts"
        guard let next = snapshot.accounts.flatMap(\.resetDates).filter({ $0 > now }).min() else { return accounts }
        return "\(accounts) · next reset \(Countdown.format(until: next, from: now))"
    }

    /// The stamp on the data, not its age.
    ///
    /// This screen does re-render on a clock, so an age would be honest here — but naming the
    /// instant matches the widget, and one wording across both surfaces is worth more than the
    /// marginal readability of "12 minutes ago".
    private var freshness: String {
        guard let updated = snapshot.updatedAt else { return "Never updated" }
        return "As of \(Countdown.absolute(updated, now: now))"
    }
}

/// One account: who it is, how it is doing, and its two horizons.
private struct AccountCard: View {

    let account: GlanceAccount
    let provider: ProviderID?
    let now: Date
    /// The plan the provider reports — Plus, Pro, Max — or nil when it is switched off or the
    /// provider never said.
    let tier: String?
    /// When the long allowance comes back, when the user has asked to see it.
    let renewal: String?

    var body: some View {
        UsageCard {
            HStack(spacing: 10) {
                if let provider { ProviderBadge(provider: provider) }
                Circle()
                    .fill(SeverityPalette.accent(account.severity))
                    .frame(width: 8, height: 8)
                    // The dot is the fastest read on the card and says nothing to VoiceOver,
                    // so it carries the status in words.
                    .accessibilityLabel(SeverityPalette.label(account.severity))

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(provider?.displayName ?? account.title)
                            .font(.headline)
                            .foregroundStyle(UsageColors.textPrimary)
                        if let tier {
                            Text(tier)
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(UsageColors.textSecondary)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(UsageColors.surfaceElevated)
                                .clipShape(Capsule())
                        }
                    }
                    if let subtitle = account.subtitle {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(UsageColors.textSecondary)
                    }
                    if let renewal {
                        Text(renewal)
                            .font(.caption)
                            .foregroundStyle(UsageColors.textTertiary)
                            .lineLimit(1)
                    }
                }

                Spacer()

                StatusPill(severity: account.severity, label: account.connectionStatus == .reconnectRequired ? "Reconnect" : nil)
            }

            ForEach(Array(account.rows.enumerated()), id: \.offset) { _, row in
                WindowRow(row: row, now: now)
            }
        }
    }
}

/// One quota window: its name, what is left, a bar, and when it returns.
struct WindowRow: View {

    let row: GlanceRow
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(row.label)
                    .font(.subheadline)
                    .foregroundStyle(UsageColors.textSecondary)
                Spacer()
                Text(remainingText)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(UsageColors.textPrimary)
                    .monospacedDigit()
            }

            UsageBar(remainingPercent: row.remainingPercent, severity: row.severity)
                .accessibilityLabel("\(row.label): \(remainingText) remaining")

            if let resetAt = row.resetAt {
                Text("Resets in \(Countdown.format(until: resetAt, from: now))")
                    .font(.caption)
                    .foregroundStyle(UsageColors.textTertiary)
            }
        }
        .padding(.vertical, 2)
    }

    /// An em dash, never a zero.
    ///
    /// A window whose percentage the provider did not report is unknown, and "0%" would state
    /// something it never said — in the one direction that would make a user stop working.
    private var remainingText: String {
        guard let remaining = row.remainingPercent else { return "—" }
        return "\(Int(remaining.rounded()))%"
    }
}

/// The status word, on its severity's tint.
struct StatusPill: View {

    let severity: Severity
    var label: String? = nil

    var body: some View {
        Text(label ?? SeverityPalette.label(severity))
            .font(.caption.weight(.semibold))
            .foregroundStyle(SeverityPalette.text(severity))
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(SeverityPalette.container(severity))
            .clipShape(Capsule())
    }
}

private struct EmptyStateCard: View {
    var body: some View {
        UsageCard {
            Text("No accounts yet")
                .font(.headline)
                .foregroundStyle(UsageColors.textPrimary)
            Text("Add a subscription to see how much usage is left and when it resets.")
                .font(.subheadline)
                .foregroundStyle(UsageColors.textSecondary)
        }
    }
}
