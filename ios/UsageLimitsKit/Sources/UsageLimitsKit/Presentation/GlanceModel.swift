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
public struct GlanceRow: Sendable, Equatable, Codable {
    public let label: String
    /// Carried so a headline can be chosen by horizon rather than by position in `rows`.
    public let category: WindowCategory
    public let remainingPercent: Double?
    public let resetAt: Date?
    public let severity: Severity

    public init(
        label: String,
        category: WindowCategory,
        remainingPercent: Double?,
        resetAt: Date?,
        severity: Severity
    ) {
        self.label = label
        self.category = category
        self.remainingPercent = remainingPercent
        self.resetAt = resetAt
        self.severity = severity
    }
}

/// One account block.
public struct GlanceAccount: Sendable, Equatable, Identifiable, Codable {
    public let id: String
    public let title: String
    public let subtitle: String?
    public let rows: [GlanceRow]
    public let severity: Severity

    public init(
        id: String,
        title: String,
        subtitle: String?,
        rows: [GlanceRow],
        severity: Severity
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.rows = rows
        self.severity = severity
    }

    /// The tightest number this block actually shows. Used only for ordering.
    var tightestRemaining: Double {
        rows.compactMap(\.remainingPercent).min() ?? .greatestFiniteMagnitude
    }
}

/// Everything a glance surface renders, already reduced from the cache.
///
/// Carries no tokens and no provider payloads, by construction: it is built only from
/// normalised snapshots. That is what makes it safe to hand to a widget process.
public struct GlanceSnapshot: Sendable, Equatable, Codable {
    public let accounts: [GlanceAccount]
    public let accountCount: Int
    public let updatedAt: Date?
    public let nextResetAt: Date?
    public let overallSeverity: Severity
    public let headlineShort: GlanceRow?
    public let headlineLong: GlanceRow?

    public init(
        accounts: [GlanceAccount],
        accountCount: Int,
        updatedAt: Date?,
        nextResetAt: Date?,
        overallSeverity: Severity,
        headlineShort: GlanceRow?,
        headlineLong: GlanceRow?
    ) {
        self.accounts = accounts
        self.accountCount = accountCount
        self.updatedAt = updatedAt
        self.nextResetAt = nextResetAt
        self.overallSeverity = overallSeverity
        self.headlineShort = headlineShort
        self.headlineLong = headlineLong
    }

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

        // The headline belongs to ONE account — the one leading the list — not to a pool.
        //
        // Reducing across every account produced a number attributed to nobody. On a tile
        // showing two of six accounts, "5h: 3 %" could be the sixth account's window, so the
        // user read a figure they could not locate. Worse, `weekly ?? monthly` applied to the
        // pool meant any one account having a weekly window suppressed every monthly one, so
        // the headline could read 80 % while a visible row read 3 %.
        let lead = ordered.first

        return GlanceSnapshot(
            accounts: ordered,
            accountCount: selected.count,
            updatedAt: selected.compactMap { $0.snapshot?.fetchedAt }.max(),
            // The soonest reset OF THE LEADING ACCOUNT. Taken across all accounts it paired the
            // headline state with an unrelated account's clock: "0 % left · resets in 12m",
            // where the twelve minutes belonged to a healthy account's five-hour window. And
            // because healthy five-hour windows reset constantly, that was the common case.
            nextResetAt: lead?.rows.compactMap(\.resetAt).min(),
            // Deliberately NOT the leading account's severity. This answers "is anything wrong
            // anywhere", which is a different question from "what should I look at first" —
            // and it is the only thing that still surfaces a broken account once the ordering
            // below stops letting rowless accounts occupy a two-slot widget.
            overallSeverity: ordered.map(\.severity).max() ?? .stale,
            headlineShort: lead?.rows.first { $0.category == .fiveHour },
            headlineLong: lead?.rows.first { $0.category != .fiveHour })
    }

    /// Worst first, with the tightest number breaking a tie — coarsely.
    ///
    /// The number is compared in five-point bands rather than exactly. Two healthy accounts
    /// drifting 47.2 to 46.8 and 46.9 to 47.1 swapped places on every refresh, which on a home
    /// screen means the reader re-scans from scratch each time and stops trusting the position
    /// of anything. Within a band the original order holds, so a tile only moves when something
    /// meaningful changed.
    private static func sortedByUrgency(_ accounts: [GlanceAccount]) -> [GlanceAccount] {
        accounts.enumerated()
            .sorted { lhs, rhs in
                let l = urgency(lhs.element.severity), r = urgency(rhs.element.severity)
                if l != r { return l < r }
                let lb = band(lhs.element.tightestRemaining)
                let rb = band(rhs.element.tightestRemaining)
                if lb != rb { return lb < rb }
                return lhs.offset < rhs.offset
            }
            .map(\.element)
    }

    /// Five-point buckets, so sub-point drift cannot reorder a home screen.
    private static func band(_ remaining: Double) -> Int {
        remaining >= .greatestFiniteMagnitude ? Int.max : Int(remaining / 5)
    }

    /// Rank for the automatic scope, lowest first.
    ///
    /// Deliberately not the `Severity` case order. That enum runs best-to-worst so `max` finds
    /// an account's worst window, which places stale and error AFTER exhausted — so ranking by
    /// it led with an account the app merely failed to read, above one the user has genuinely
    /// run out on.
    ///
    /// Error and stale then sort LAST, below healthy, which reads wrong until you count the
    /// slots. Both states are rowless — a never-fetched or failed account has no windows to
    /// show — so ranking them highly filled a two-tile widget with blank cards and pushed an
    /// account at 3 % off the screen entirely. A blank tile answers nothing; the account with a
    /// real number does. That an account is unreadable still reaches the user through
    /// `overallSeverity` and through the app's own list, neither of which is slot-limited.
    private static func urgency(_ severity: Severity) -> Int {
        switch severity {
        case .exhausted: return 0
        case .low: return 1
        case .medium: return 2
        case .healthy: return 3
        case .error: return 4
        case .stale: return 5
        }
    }

    /// The tightest window of a category — the number worth surfacing in one row.
    ///
    /// An unknown percentage sorts as MOST urgent, not least. It resolves to `.error`, so
    /// treating it as the largest possible number made the one window the app could not read
    /// the last one it would ever show — and when every window of a category was unknown, the
    /// comparison never fired and an arbitrary one was kept.
    private static func headline(
        _ windows: [UsageWindow],
        _ category: WindowCategory
    ) -> GlanceRow? {
        windows
            .filter { $0.category == category }
            .min { ($0.remainingPercent ?? -1) < ($1.remainingPercent ?? -1) }
            .map(row)
    }

    private static func glanceAccount(_ usage: AccountUsage, now: Date) -> GlanceAccount {
        let windows = usage.snapshot?.windows ?? []

        // Two horizons, not every window an account reports: a tile has room for about two
        // rows before it stops being readable at a glance, which is the entire point of it.
        //
        // `.other` is the last resort for the long slot. A window whose duration no provider
        // documents still counts against the account, and leaving the slot empty would hide a
        // limit the app knows is being consumed while showing a healthier one beside it.
        let rows = [
            headline(windows, .fiveHour),
            headline(windows, .weekly) ?? headline(windows, .monthly) ?? headline(windows, .other),
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
            category: window.category,
            remainingPercent: window.remainingPercent,
            resetAt: window.resetAt,
            severity: window.severity)
    }
}
