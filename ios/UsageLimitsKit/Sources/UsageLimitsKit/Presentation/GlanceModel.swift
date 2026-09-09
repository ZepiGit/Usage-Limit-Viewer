import Foundation

/// Which accounts a surface shows.
///
/// The same choice a home-screen widget offers, kept in the shared kit because the main screen
/// and the widget must agree: a user who sets a widget to "most critical" and then opens the
/// app to a differently ordered list has been shown two answers to one question.
public enum GlanceScope: String, Sendable, Codable {
    case allAccounts
    case mostCritical
    case account
    case provider
}

/// One bar.
public struct GlanceRow: Sendable, Equatable {
    public let label: String
    public let remainingPercent: Double?
    public let resetAt: Date?
    public let severity: Severity

    public init(label: String, remainingPercent: Double?, resetAt: Date?, severity: Severity) {
        self.label = label
        self.remainingPercent = remainingPercent
        self.resetAt = resetAt
        self.severity = severity
    }
}

/// One account block.
public struct GlanceAccount: Sendable, Equatable, Identifiable {
    public let id: String
    public let title: String
    public let subtitle: String?
    public let rows: [GlanceRow]
    public let severity: Severity

    /// The tightest number this block actually shows. Used only for ordering.
    var tightestRemaining: Double {
        rows.compactMap(\.remainingPercent).min() ?? .greatestFiniteMagnitude
    }
}

/// Everything a glance surface renders, already reduced from the cache.
///
/// Carries no tokens and no provider payloads, by construction: it is built only from
/// normalised snapshots. That is what makes it safe to hand to a widget process.
public struct GlanceSnapshot: Sendable, Equatable {
    public let accounts: [GlanceAccount]
    public let accountCount: Int
    public let updatedAt: Date?
    public let nextResetAt: Date?
    public let overallSeverity: Severity
    public let headlineShort: GlanceRow?
    public let headlineLong: GlanceRow?

    public static let empty = GlanceSnapshot(
        accounts: [], accountCount: 0, updatedAt: nil, nextResetAt: nil,
        overallSeverity: .stale, headlineShort: nil, headlineLong: nil)
}

/// An account and its latest snapshot, as the cache hands them over.
public struct AccountUsage: Sendable, Equatable {
    public let account: ProviderAccount
    public let snapshot: UsageSnapshot?

    public init(account: ProviderAccount, snapshot: UsageSnapshot?) {
        self.account = account
        self.snapshot = snapshot
    }
}

/// Reduces the cache to what one surface needs.
///
/// Pure functions over already-loaded data, so rendering never touches a provider or the
/// credential store, and so the decisions below are testable on any platform — which is the
/// whole reason this lives in the Foundation-only kit rather than in the SwiftUI layer.
///
/// This mirrors the Kotlin `WidgetDataBuilder` decision for decision. The two must agree: the
/// same accounts in the same state have to produce the same ordering and the same headline
/// numbers on both platforms, or the app is telling two users different things about one
/// subscription.
public enum GlanceModel {

    public static func build(
        _ all: [AccountUsage],
        now: Date,
        scope: GlanceScope,
        accountID: String? = nil,
        providerID: String? = nil
    ) -> GlanceSnapshot {
        let selected: [AccountUsage]
        switch scope {
        case .account:
            selected = all.filter { $0.account.id == accountID }
        case .provider:
            selected = all.filter { $0.account.provider.rawValue == providerID }
        case .allAccounts, .mostCritical:
            selected = all
        }

        guard !selected.isEmpty else { return .empty }

        let accounts = selected.map { glanceAccount($0, now: now) }
        let ordered = scope == .mostCritical ? sortedByUrgency(accounts) : accounts
        let allWindows = selected.flatMap { $0.snapshot?.windows ?? [] }

        return GlanceSnapshot(
            accounts: ordered,
            accountCount: selected.count,
            updatedAt: selected.compactMap { $0.snapshot?.fetchedAt }.max(),
            nextResetAt: allWindows.compactMap(\.resetAt).min(),
            overallSeverity: ordered.map(\.severity).max() ?? .stale,
            headlineShort: headline(allWindows, .fiveHour),
            // A monthly window stands in for the long horizon when a plan has no weekly one.
            headlineLong: headline(allWindows, .weekly) ?? headline(allWindows, .monthly))
    }

    /// Worst first, with the tightest number breaking a tie.
    ///
    /// The sort is stable on the urgency rank and then on the number, so two accounts in the
    /// same band never swap places between refreshes for no reason a user could see.
    private static func sortedByUrgency(_ accounts: [GlanceAccount]) -> [GlanceAccount] {
        accounts.enumerated()
            .sorted { lhs, rhs in
                let l = urgency(lhs.element.severity), r = urgency(rhs.element.severity)
                if l != r { return l < r }
                let lt = lhs.element.tightestRemaining, rt = rhs.element.tightestRemaining
                if lt != rt { return lt < rt }
                return lhs.offset < rhs.offset
            }
            .map(\.element)
    }

    /// Rank for the automatic scope, lowest first.
    ///
    /// Deliberately not the `Severity` case order. That enum runs best-to-worst so `max` finds
    /// an account's worst window, which places stale and error AFTER exhausted — so ranking by
    /// it led with an account the app merely failed to read, above one the user has genuinely
    /// run out on.
    ///
    /// A glance surface leads with limits that are real: exhausted first, then error and stale
    /// together (both mean "the app cannot currently tell you"), then the merely-getting-low.
    private static func urgency(_ severity: Severity) -> Int {
        switch severity {
        case .exhausted: return 0
        case .error, .stale: return 1
        case .low: return 2
        case .medium: return 3
        case .healthy: return 4
        }
    }

    /// The tightest window of a category — the number worth surfacing in one tile.
    private static func headline(
        _ windows: [UsageWindow],
        _ category: WindowCategory
    ) -> GlanceRow? {
        windows
            .filter { $0.category == category }
            .min { ($0.remainingPercent ?? .greatestFiniteMagnitude)
                 < ($1.remainingPercent ?? .greatestFiniteMagnitude) }
            .map(row)
    }

    private static func glanceAccount(_ usage: AccountUsage, now: Date) -> GlanceAccount {
        let windows = usage.snapshot?.windows ?? []

        // Two horizons, not every window an account reports: a tile has room for about two
        // rows before it stops being readable at a glance, which is the entire point of it.
        let rows = [
            headline(windows, .fiveHour),
            headline(windows, .weekly) ?? headline(windows, .monthly),
        ].compactMap { $0 }

        var title = usage.account.provider.displayName
        if let plan = usage.account.plan, !plan.trimmingCharacters(in: .whitespaces).isEmpty {
            title += " \(plan)"
        }

        return GlanceAccount(
            id: usage.account.id,
            title: title,
            // The masked form, never the raw address: a widget renders on a lock screen.
            subtitle: usage.account.maskedEmail,
            rows: rows,
            severity: usage.snapshot?.severity(at: now) ?? .stale)
    }

    private static func row(_ window: UsageWindow) -> GlanceRow {
        let label: String
        switch window.category {
        case .fiveHour: label = "5h limit"
        case .weekly: label = "Weekly"
        case .monthly: label = "Monthly"
        case .other: label = window.label
        }

        return GlanceRow(
            label: label,
            remainingPercent: window.remainingPercent,
            resetAt: window.resetAt,
            severity: window.severity)
    }
}
