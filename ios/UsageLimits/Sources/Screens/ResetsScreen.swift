import SwiftUI
import UsageLimitsKit

/// Every limit that has a known reset time, soonest first.
///
/// The second half of the question this app exists to answer. A window whose reset the provider
/// does not state is absent rather than shown with a blank — a row with no time is a row that
/// cannot be acted on, and it would push a real one off the screen.
struct ResetsScreen: View {

    @EnvironmentObject private var store: UsageStore

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if store.upcomingResets.isEmpty {
                        UsageCard {
                            Text("Nothing scheduled")
                                .font(.headline)
                                .foregroundStyle(UsageColors.textPrimary)
                            Text("Reset times appear here once a provider reports one.")
                                .font(.subheadline)
                                .foregroundStyle(UsageColors.textSecondary)
                        }
                    } else {
                        ForEach(Array(store.upcomingResets.enumerated()), id: \.offset) { _, entry in
                            ResetRow(
                                accountLabel: entry.account.label,
                                window: entry.window,
                                now: store.now)
                        }
                    }
                }
                .padding(16)
            }
            .background(UsageColors.background)
            .navigationTitle("Resets")
            .refreshable { await store.refresh() }
        }
    }
}

private struct ResetRow: View {

    let accountLabel: String
    let window: UsageWindow
    let now: Date

    var body: some View {
        UsageCard {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(window.label)
                        .font(.headline)
                        .foregroundStyle(UsageColors.textPrimary)
                    Text(accountLabel)
                        .font(.footnote)
                        .foregroundStyle(UsageColors.textSecondary)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 3) {
                    Text(countdown)
                        .font(.headline)
                        .foregroundStyle(UsageColors.terracotta)
                        .monospacedDigit()
                    // Both forms, deliberately: the countdown is what the eye wants, and the
                    // wall-clock time is what survives being read a few minutes later.
                    if let resetAt = window.resetAt {
                        Text(Countdown.absolute(resetAt, now: now))
                            .font(.caption)
                            .foregroundStyle(UsageColors.textTertiary)
                    }
                }
            }

            UsageBar(remainingPercent: window.remainingPercent, severity: window.severity)
                .accessibilityLabel(barLabel)
        }
    }

    private var countdown: String {
        guard let resetAt = window.resetAt else { return "—" }
        return Countdown.format(until: resetAt, from: now)
    }

    private var barLabel: String {
        guard let remaining = window.remainingPercent else {
            return "\(window.label): remaining quota unknown"
        }
        return "\(window.label): \(Int(remaining.rounded())) per cent remaining"
    }
}
