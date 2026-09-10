import SwiftUI
import UsageLimitsKit

/// Every connected subscription, the way in to add another, and the one action that changes
/// anything at a provider.
struct AccountsScreen: View {

    @EnvironmentObject private var store: UsageStore
    @State private var isAdding = false

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    // Driven from `store.accounts` rather than the glance model, because this
                    // screen is the one place that needs more than the reduced view: the reset
                    // credits a card can spend do not travel in a glance.
                    ForEach(store.accounts, id: \.account.id) { usage in
                        AccountSummaryCard(usage: usage, now: store.now)
                    }

                    Button { isAdding = true } label: { AddAccountCard() }
                        .buttonStyle(.plain)
                }
                .padding(16)
            }
            .background(UsageColors.background)
            .navigationTitle("Accounts")
            .refreshable { await store.refresh() }
            .sheet(isPresented: $isAdding) { AddAccountSheet() }
        }
    }
}

private struct AccountSummaryCard: View {

    @EnvironmentObject private var store: UsageStore
    @State private var isConfirmingRedeem = false

    let usage: AccountUsage
    let now: Date

    private var snapshot: UsageSnapshot? { usage.snapshot }

    var body: some View {
        UsageCard {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(usage.account.label)
                        .font(.headline)
                        .foregroundStyle(UsageColors.textPrimary)
                    if let subtitle = usage.account.plan ?? usage.account.maskedEmail {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(UsageColors.textSecondary)
                    }
                }
                Spacer()
                StatusPill(severity: snapshot?.severity(at: now, staleAfter: store.settings.staleAfter) ?? .stale)
            }

            if let message = snapshot?.errorMessage {
                // The failure is shown on the account it belongs to, beside the numbers it
                // could not update — the one place a user can act on it.
                Text(verbatim: message)
                    .font(.footnote)
                    .foregroundStyle(SeverityPalette.text(.error))
            }

            ForEach(snapshot?.windows ?? [], id: \.id) { window in
                WindowRow(
                    row: GlanceRow(
                        label: window.label,
                        category: window.category,
                        remainingPercent: window.remainingPercent,
                        resetAt: window.resetAt,
                        severity: window.severity),
                    now: now)
            }

            resetCredits
        }
    }

    /// The credit balance, and the spend — but only when the provider says a credit applies.
    ///
    /// Held and applicable are different numbers and the distinction is the point: an account
    /// can hold three credits and be able to apply none of them, because none covers the limit
    /// currently in force. The balance is stated either way, so a user who has credits is not
    /// told they have none; the button appears only when one can actually be spent, because
    /// offering it otherwise takes a tap and gives back a refusal.
    @ViewBuilder
    private var resetCredits: some View {
        if let snapshot, snapshot.heldResetCredits > 0 {
            Divider().overlay(UsageColors.outline).padding(.vertical, 2)

            HStack {
                Text(snapshot.heldResetCredits == 1
                     ? "1 reset credit"
                     : "\(snapshot.heldResetCredits) reset credits")
                    .font(.footnote)
                    .foregroundStyle(UsageColors.textSecondary)
                Spacer()
                if snapshot.spendableResetCredits > 0 {
                    Button("Reset limit") { isConfirmingRedeem = true }
                        .buttonStyle(.bordered)
                        .tint(UsageColors.terracotta)
                } else {
                    Text("None applies to this limit")
                        .font(.caption)
                        .foregroundStyle(UsageColors.textTertiary)
                }
            }
            // A confirmation, because this is the only irreversible thing the app can do. The
            // wording names the cost before the consequence: a user who reads three words and
            // taps should still have read the part that matters.
            .confirmationDialog(
                "Use a reset credit?",
                isPresented: $isConfirmingRedeem,
                titleVisibility: .visible
            ) {
                Button("Use credit", role: .destructive) {
                    Task { await store.redeemResetCredit(accountID: usage.account.id) }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This spends 1 reset credit and cannot be undone. The rate limit resets immediately.")
            }
        }
    }
}

private struct AddAccountCard: View {
    var body: some View {
        UsageCard {
            Text("+ Add account")
                .font(.headline)
                .foregroundStyle(UsageColors.terracotta)
            // Sign-in happens in the user's own browser, on the provider's own page. Stated
            // here because the alternative — an embedded web view — is what credential-harvesting
            // apps do, and a user has no way to tell one from the other inside the app.
            Text("Opens the provider's normal sign-in page in your browser.")
                .font(.footnote)
                .foregroundStyle(UsageColors.textSecondary)
        }
    }
}
