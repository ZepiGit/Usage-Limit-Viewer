import SwiftUI
import UsageLimitsKit

/// The screen the app opens on: what is left, and when it comes back.
///
/// Everything shown here is decided in `UsageLimitsKit` — which accounts lead, which two
/// windows each shows, what counts as stale. This file only lays them out, which is what keeps
/// the two platforms saying the same thing about the same account.
struct OverviewScreen: View {

    @EnvironmentObject private var store: UsageStore

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    SummaryCard(snapshot: store.glance, now: store.now)

                    if store.accounts.isEmpty {
                        EmptyStateCard()
                    } else {
                        ForEach(store.glance.accounts) { account in
                            AccountCard(account: account, now: store.now)
                        }
                    }
                }
                .padding(16)
            }
            .background(UsageColors.background)
            .navigationTitle("Overview")
            .refreshable { await store.refresh() }
        }
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
                    .foregroundStyle(SeverityPalette.text(snapshot.overallSeverity))
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
        let healthy = snapshot.accounts.filter {
            $0.severity(at: now, staleAfter: snapshot.staleAfter) == .healthy
        }.count
        return "\(healthy)/\(snapshot.accountCount)"
    }

    private var subtitle: String {
        let accounts = snapshot.accountCount == 1 ? "1 account" : "\(snapshot.accountCount) accounts"
        guard let next = snapshot.nextResetAt else { return accounts }
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
    let now: Date

    var body: some View {
        UsageCard {
            HStack(spacing: 10) {
                Circle()
                    .fill(SeverityPalette.accent(account.severity))
                    .frame(width: 8, height: 8)
                    // The dot is the fastest read on the card and says nothing to VoiceOver,
                    // so it carries the status in words.
                    .accessibilityLabel(SeverityPalette.label(account.severity))

                VStack(alignment: .leading, spacing: 2) {
                    Text(account.title)
                        .font(.headline)
                        .foregroundStyle(UsageColors.textPrimary)
                    if let subtitle = account.subtitle {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(UsageColors.textSecondary)
                    }
                }

                Spacer()

                StatusPill(severity: account.severity)
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

    var body: some View {
        Text(SeverityPalette.label(severity))
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
