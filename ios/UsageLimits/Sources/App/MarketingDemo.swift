import Foundation
import SwiftUI
import UsageLimitsKit

#if DEBUG
/// In-memory fixtures for real simulator screenshots. No account or credential is persisted.
enum MarketingDemo {
    static var isEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains("-marketing-demo")
    }

    // A fixed clock keeps relative and absolute reset labels consistent across every capture.
    static let now = Date(timeIntervalSince1970: 1_789_206_060) // 2026-09-12 09:41 UTC

    static let accounts: [AccountUsage] = [
        account(.codex, plan: "Plus", windows: [
            window("5-hour", used: 28, duration: 18_000, resetIn: 8_100),
            window("Weekly", used: 56, duration: 604_800, resetIn: 273_600)
        ]),
        account(.claude, plan: "Pro", windows: [
            window("5-hour", used: 82, duration: 18_000, resetIn: 2_520),
            window("Weekly", used: 39, duration: 604_800, resetIn: 367_200)
        ]),
        account(.antigravity, plan: "Pro", windows: [
            window("5-hour", used: 14, duration: 18_000, resetIn: 11_400),
            window("Weekly", used: 47, duration: 604_800, resetIn: 201_600)
        ]),
        account(.xai, plan: "SuperGrok", windows: [
            window("Weekly", used: 33, duration: 604_800, resetIn: 108_000),
            window("Monthly", used: 18, duration: 2_592_000, resetIn: 1_555_200)
        ]),
        account(.kimi, plan: "Code", windows: [
            window("5-hour", used: 42, duration: 18_000, resetIn: 5_100),
            window("Weekly", used: 24, duration: 604_800, resetIn: 432_000)
        ])
    ]

    private static func account(
        _ provider: ProviderID, plan: String, windows: [UsageWindow]
    ) -> AccountUsage {
        let id = "demo-\(provider.rawValue)"
        return AccountUsage(
            account: ProviderAccount(
                id: id, provider: provider, externalAccountID: id, email: nil,
                displayName: provider.displayName, plan: plan,
                credentialReference: "", createdAt: now, lastSuccessfulSync: now),
            snapshot: UsageSnapshot(
                accountID: id, fetchedAt: now, status: .ok, windows: windows))
    }

    private static func window(
        _ label: String, used: Double, duration: Int64, resetIn: TimeInterval
    ) -> UsageWindow {
        UsageWindow(
            id: label, label: label, category: .from(periodSeconds: duration),
            usedPercent: used, periodSeconds: duration,
            resetAt: now.addingTimeInterval(resetIn), exhausted: false)
    }
}
#endif

extension UsageStore {
    var isMarketingDemo: Bool {
        #if DEBUG
        MarketingDemo.isEnabled
        #else
        false
        #endif
    }
}

extension View {
    @ViewBuilder
    func marketingDemoLabel() -> some View {
        #if DEBUG
        if MarketingDemo.isEnabled {
            toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Text("Demo data")
                        .font(.caption)
                        .foregroundStyle(UsageColors.textSecondary)
                        .accessibilityIdentifier("marketing-demo-label")
                }
            }
        } else {
            self
        }
        #else
        self
        #endif
    }
}
