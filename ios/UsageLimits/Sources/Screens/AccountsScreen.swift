import SwiftUI
import UsageLimitsKit

/// Every connected subscription, the way in to add another, and the one action that changes
/// anything at a provider.
struct AccountsScreen: View {

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var store: UsageStore
    @State private var isAdding = false
    @State private var providerFilter: ProviderID?
    @State private var attentionOnly = false

    private var filteredAccounts: [AccountUsage] {
        store.accounts.filter { usage in
            (providerFilter == nil || usage.account.provider == providerFilter) &&
                (!attentionOnly || usage.snapshot?.connectionStatus == .reconnectRequired)
        }
    }

    private func filterButton(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.subheadline.weight(.medium)).padding(.horizontal, 14).padding(.vertical, 10)
                .foregroundStyle(selected ? UsageColors.background : UsageColors.textSecondary)
                .background(selected ? UsageColors.terracotta : UsageColors.surfaceElevated).clipShape(Capsule())
        }.buttonStyle(.plain)
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            filterButton("All", selected: providerFilter == nil && !attentionOnly) { providerFilter = nil; attentionOnly = false }
                            filterButton("Needs attention", selected: attentionOnly) { attentionOnly.toggle() }
                            ForEach(ProviderID.allCases, id: \.self) { provider in
                                if store.accounts.contains(where: { $0.account.provider == provider }) {
                                    filterButton(provider.displayName, selected: providerFilter == provider) {
                                        providerFilter = providerFilter == provider ? nil : provider
                                    }
                                }
                            }
                        }
                    }
                    // Driven from `store.accounts` rather than the glance model, because this
                    // screen is the one place that needs more than the reduced view: the reset
                    // credits a card can spend do not travel in a glance.
                    ForEach(filteredAccounts, id: \.account.id) { usage in
                        AccountSummaryCard(usage: usage, now: store.now).id(usage.account.id)
                    }

                    Button { isAdding = true } label: { AddAccountCard() }
                        .buttonStyle(.plain)
                }
                .animation(reduceMotion ? nil : Animation.easeInOut(duration: 0.24), value: store.accounts.map { $0.account.id })
                .padding(16)
                .readableWidth()
            }
            .accessibilityIdentifier("accounts-scroll")
            .background(UsageColors.background)
            .navigationTitle("Accounts")
            .marketingDemoLabel()
            .refreshable { await store.refresh() }
            .sheet(isPresented: $isAdding) { AddAccountSheet() }
            .task(id: store.focusedAccountID) {
                guard let id = store.focusedAccountID else { return }
                providerFilter = nil; attentionOnly = false
                await Task.yield()
                proxy.scrollTo(id, anchor: .center)
                store.focusedAccountID = nil
            }
            }
        }
    }
}

private struct AccountSummaryCard: View {
    @State private var reconnecting = false

    @EnvironmentObject private var store: UsageStore
    @State private var isConfirmingRedeem = false
    @State private var isConfirmingRemove = false

    let usage: AccountUsage
    let now: Date

    private var snapshot: UsageSnapshot? { usage.snapshot }

    private var notificationsEnabled: Bool {
        store.notificationsEnabled(accountID: usage.account.id)
    }

    var body: some View {
        UsageCard {
            HStack {
                ProviderBadge(provider: usage.account.provider)
                VStack(alignment: .leading, spacing: 2) {
                    Text(usage.account.label)
                        .font(.headline)
                        .foregroundStyle(UsageColors.textPrimary)
                    // Through the shared `planLabel`, so "claude_max_20x" reads as "Claude Max
                    // 20×" here exactly as it does on Android, rather than as the raw value
                    // one platform happened to print.
                    if let subtitle = planLabel(usage.account.plan) ?? usage.account.maskedEmail {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(UsageColors.textSecondary)
                    }
                }
                Spacer()
                // A muted account looks muted. Without this the only evidence of the choice is
                // inside a menu nobody opens twice, and silence then reads as the app failing
                // to notify rather than as the user having asked it not to.
                if !notificationsEnabled {
                    Image(systemName: "bell.slash.fill")
                        .foregroundStyle(UsageColors.textTertiary)
                        .accessibilityLabel("Notifications muted")
                }
                StatusPill(severity: snapshot?.severity(at: now, staleAfter: store.settings.staleAfter) ?? .stale, label: snapshot?.connectionStatus == .reconnectRequired ? "Reconnect" : nil)
                // Disconnecting has been possible in the container, and tested there, since
                // before anything on screen could ask for it — so a user could connect an
                // account and then had no way at all to disconnect it. Deleting the app was
                // the only route, and keychain items outlive that, so it was not even a good
                // one. A menu rather than a swipe: these cards are not a plain list, and an
                // action this consequential should not be discoverable only by accident.
                Menu {
                    if snapshot?.connectionStatus == .reconnectRequired {
                        Button("Reconnect account") { reconnecting = true }
                    }
                    // Muting lives in this menu rather than as a switch on the card, because
                    // it is a per-account preference and not a per-account fact: a row of
                    // toggles down the list would compete with the numbers the screen is for.
                    Button(notificationsEnabled ? "Mute notifications" : "Unmute notifications") {
                        store.setNotifications(
                            enabled: !notificationsEnabled, accountID: usage.account.id)
                    }
                    Button("Disconnect account", role: .destructive) {
                        isConfirmingRemove = true
                    }
                    .disabled(store.isRemoving)
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .foregroundStyle(UsageColors.textSecondary)
                }
                .accessibilityLabel("Account actions")
            }
            // The wording promises only what this app can actually do. Removing an account
            // deletes what THIS DEVICE stores; it does not revoke the grant at the provider,
            // and saying otherwise would leave someone believing they had cut off access they
            // had not.
            .sheet(isPresented: $reconnecting) { AddAccountSheet() }
        .confirmationDialog(
                "Disconnect \(usage.account.label)?",
                isPresented: $isConfirmingRemove,
                titleVisibility: .visible
            ) {
                Button("Disconnect", role: .destructive) {
                    Task { await store.removeAccount(accountID: usage.account.id) }
                }
                .disabled(store.isRemoving)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This removes the account from Usage Limits and deletes the sign-in stored on this device. It does not revoke access at the provider — do that in your account settings there.")
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
                // The store already ignores a second tap while one spend is in flight; the
                // button says so too, rather than looking willing and doing nothing.
                .disabled(store.isRedeeming)
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
