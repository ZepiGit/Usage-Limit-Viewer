import SwiftUI
import UsageLimitsKit

/// Every connected subscription, and the way in to add another.
struct AccountsScreen: View {

    @EnvironmentObject private var store: UsageStore
    @State private var isAdding = false

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(store.glance.accounts) { account in
                        AccountSummaryCard(account: account, now: store.now)
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

    let account: GlanceAccount
    let now: Date

    var body: some View {
        UsageCard {
            HStack {
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
