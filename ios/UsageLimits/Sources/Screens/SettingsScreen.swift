import SwiftUI
import UsageLimitsKit

/// Refresh cadence and which alerts are wanted.
///
/// The two low-quota tiers are separate switches rather than one adjustable threshold, because
/// the point of the second is that an account already warned at 18 % should still say something
/// at 8 % — a single slider cannot express "warn once, then escalate once".
struct SettingsScreen: View {

    @EnvironmentObject private var store: UsageStore

    private let intervals = [15, 30, 60, 180]

    var body: some View {
        NavigationStack {
            Form {
                Section("Refresh") {
                    Picker("Every", selection: $store.settings.syncIntervalMinutes) {
                        ForEach(intervals, id: \.self) { minutes in
                            Text(label(for: minutes)).tag(minutes)
                        }
                    }
                    Text(stalenessNote)
                        .font(.footnote)
                        .foregroundStyle(UsageColors.textSecondary)
                }

                Section("Notifications") {
                    Toggle("Below 20% left", isOn: $store.settings.notifications.notifyBelow20Percent)
                    Toggle("Below 10% left", isOn: $store.settings.notifications.notifyBelow10Percent)
                    Toggle("Limit exhausted", isOn: $store.settings.notifications.notifyOnExhausted)
                    Toggle("Reset approaching", isOn: $store.settings.notifications.notifyOnResetApproaching)
                    Toggle(
                        "Reset credit expiring",
                        isOn: $store.settings.notifications.notifyOnResetCreditExpiring)
                    Toggle("Sign-in expired", isOn: $store.settings.notifications.notifyOnAuthExpired)
                }

                Section("Display") {
                    Toggle(
                        "Show subscription tier",
                        isOn: $store.settings.showSubscriptionTier)
                    Toggle("Show renewal time", isOn: $store.settings.showRenewalTime)
                    Text(
                        "The renewal line names when the longest allowance comes back. It is "
                        + "shown on the overview only — a widget has room for the number you "
                        + "are about to run out of, not a second date competing with it.")
                        .font(.footnote)
                        .foregroundStyle(UsageColors.textSecondary)
                }

                Section("Privacy") {
                    Text(
                        "Tokens are stored in the keychain and excluded from backups and device "
                        + "transfer. Moving to a new phone means signing in again.")
                        .font(.footnote)
                        .foregroundStyle(UsageColors.textSecondary)
                }
            }
            .readableWidth()
            .scrollContentBackground(.hidden)
            .background(UsageColors.background)
            .navigationTitle("Settings")
        }
    }

    private func label(for minutes: Int) -> String {
        minutes < 60 ? "\(minutes) minutes" : "\(minutes / 60) hours"
    }

    /// Says out loud what the interval implies, because the two are linked and the link is not
    /// obvious: pick a three-hour refresh and data three hours old is normal, not suspect.
    private var stalenessNote: String {
        let minutes = Int(store.settings.staleAfter / 60)
        let text = minutes < 120 ? "\(minutes) minutes" : "\(minutes / 60) hours"
        return "Numbers older than \(text) are marked stale."
    }
}
