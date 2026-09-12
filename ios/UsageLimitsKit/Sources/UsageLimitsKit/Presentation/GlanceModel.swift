import Foundation

/// Which accounts a surface shows.
///
/// The same choice a home-screen widget offers, kept in the shared kit because the main screen
/// and the widget must agree: a user who sets a widget to "most critical" and then opens the
/// app to a differently ordered list has been shown two answers to one question.
public enum GlanceScope: String, Sendable, Codable {
    case allAccounts
    case mostCritical
    case closestResets
    case custom
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

    /// The severity as it stood when the app wrote this, staleness included.
    public let severity: Severity

    /// The severity WITHOUT the staleness judgement — what the numbers themselves say.
    public let baseSeverity: Severity

    /// When the provider was last read, so freshness can be judged again later.
    ///
    /// The widget renders a timeline of several entries at different dates from one snapshot,
    /// and every one of them used to carry the severity computed at WRITE time. An account
    /// that was healthy at T0 therefore still read healthy at T0 plus a day if no background
    /// refresh had run in between: the tile aged without ever saying so, which is the one
    /// thing a quota display must never do. Carrying the fetch instant lets each entry decide
    /// for itself.
    ///
    /// Optional because a snapshot written by an earlier build has none, and such an account
    /// keeps exactly its previous behaviour rather than being declared stale on upgrade.
    public let fetchedAt: Date?
    public let providerID: String?
    public let iconAssetName: String?
    public let resetDates: [Date]
    public let connectionStatus: ConnectionStatus

    public init(
        id: String,
        title: String,
        subtitle: String?,
        rows: [GlanceRow],
        severity: Severity,
        baseSeverity: Severity? = nil,
        fetchedAt: Date? = nil,
        providerID: String? = nil,
        connectionStatus: ConnectionStatus = .unknown,
        resetDates: [Date]? = nil,
        iconAssetName: String? = nil
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.rows = rows
        self.severity = severity
        self.baseSeverity = baseSeverity ?? severity
        self.fetchedAt = fetchedAt
        self.providerID = providerID
        self.iconAssetName = iconAssetName
        self.connectionStatus = connectionStatus
        self.resetDates = resetDates ?? rows.compactMap(\.resetAt)
    }

    private enum CodingKeys: String, CodingKey {
        case id, title, subtitle, rows, severity, baseSeverity, fetchedAt, providerID, connectionStatus, resetDates, iconAssetName
    }

    /// Lenient on the two new keys: a snapshot written before them must still decode, or every
    /// widget goes blank on the upgrade that introduced them.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decode(String.self, forKey: .title)
        subtitle = try c.decodeIfPresent(String.self, forKey: .subtitle)
        rows = try c.decode([GlanceRow].self, forKey: .rows)
        severity = try c.decode(Severity.self, forKey: .severity)
        baseSeverity = try c.decodeIfPresent(Severity.self, forKey: .baseSeverity) ?? severity
        fetchedAt = try c.decodeIfPresent(Date.self, forKey: .fetchedAt)
        providerID = try c.decodeIfPresent(String.self, forKey: .providerID)
        iconAssetName = try c.decodeIfPresent(String.self, forKey: .iconAssetName)
        connectionStatus = try c.decodeIfPresent(ConnectionStatus.self, forKey: .connectionStatus) ?? .unknown
        resetDates = try c.decodeIfPresent([Date].self, forKey: .resetDates) ?? rows.compactMap(\.resetAt)
    }

    /// The severity to show at `now`, aged from the fetch instant rather than frozen.
    ///
    /// An error stays an error: a snapshot that failed to parse does not become merely old.
    /// Without a fetch instant this is the stored value, unchanged.
    public func severity(at now: Date, staleAfter: TimeInterval) -> Severity {
        guard let fetchedAt else { return severity }
        if baseSeverity == .error { return .error }
        return now.timeIntervalSince(fetchedAt) >= staleAfter ? .stale : baseSeverity
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

    /// How old a reading may be before it reads as stale.
    ///
    /// Travels with the snapshot because the widget is a separate process that cannot see the
    /// user's sync interval, and a fixed hour is wrong at the three-hour interval the settings
    /// screen offers — every reading would be stale before the next arrived.
    public let staleAfter: TimeInterval

    public init(
        accounts: [GlanceAccount],
        accountCount: Int,
        updatedAt: Date?,
        nextResetAt: Date?,
        overallSeverity: Severity,
        headlineShort: GlanceRow?,
        headlineLong: GlanceRow?,
        staleAfter: TimeInterval = Severity.staleAfter
    ) {
        self.staleAfter = staleAfter
        self.accounts = accounts
        self.accountCount = accountCount
        self.updatedAt = updatedAt
        self.nextResetAt = nextResetAt
        self.overallSeverity = overallSeverity
        self.headlineShort = headlineShort
        self.headlineLong = headlineLong
    }

    private enum CodingKeys: String, CodingKey {
        case accounts, accountCount, updatedAt, nextResetAt, overallSeverity
        case headlineShort, headlineLong, staleAfter
    }

    /// Lenient on `staleAfter`, which older files do not have. The synthesised decoder
    /// requires every key, and this file is read by the widget process — a decode failure
    /// there is not an error anyone sees, it is a blank tile on every home screen at the
    /// moment of upgrade.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        accounts = try c.decode([GlanceAccount].self, forKey: .accounts)
        accountCount = try c.decode(Int.self, forKey: .accountCount)
        updatedAt = try c.decodeIfPresent(Date.self, forKey: .updatedAt)
        nextResetAt = try c.decodeIfPresent(Date.self, forKey: .nextResetAt)
        overallSeverity = try c.decode(Severity.self, forKey: .overallSeverity)
        headlineShort = try c.decodeIfPresent(GlanceRow.self, forKey: .headlineShort)
        headlineLong = try c.decodeIfPresent(GlanceRow.self, forKey: .headlineLong)
        staleAfter = try c.decodeIfPresent(TimeInterval.self, forKey: .staleAfter)
            ?? Severity.staleAfter
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
        providerID: String? = nil,
        staleAfter: TimeInterval = Severity.staleAfter,
        customAccountIDs: [String] = [],
        providerIcons: [String: String] = [:]
    ) -> GlanceSnapshot {
        let selected: [AccountUsage]
        switch scope {
        case .account:
            selected = all.filter { $0.account.id == accountID }
        case .provider:
            selected = all.filter { $0.account.provider.rawValue == providerID }
        case .custom:
            var seen = Set<String>()
            selected = customAccountIDs.filter { seen.insert($0).inserted }.compactMap { id in all.first { $0.account.id == id } }
        case .allAccounts, .mostCritical, .closestResets:
            selected = all
        }

        guard !selected.isEmpty else { return .empty }

        let accounts = selected.map { glanceAccount($0, now: now, staleAfter: staleAfter, providerIcons: providerIcons) }
        let ordered: [GlanceAccount]
        if scope == .mostCritical { ordered = sortedByUrgency(accounts) }
        else if scope == .closestResets {
            let indexed = accounts.enumerated().map { index, account in
                let next = selected.first { $0.account.id == account.id }?.snapshot?.windows
                    .compactMap(\.resetAt).filter { $0 > now }.min() ?? .distantFuture
                return (index, account, next)
            }
            ordered = indexed.sorted { $0.2 == $1.2 ? $0.0 < $1.0 : $0.2 < $1.2 }.map { $0.1 }
        } else { ordered = accounts }

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
            // And one still AHEAD. A snapshot keeps a window's reset instant until the next
            // fetch replaces it, so once one had passed the minimum was anchored in the past:
            // the summary read "next reset now" for hours while the real next rollover was
            // never shown. The Android view model already drops passed instants.
            nextResetAt: lead?.resetDates.filter { $0 > now }.min(),
            // Deliberately NOT the leading account's severity. This answers "is anything wrong
            // anywhere", which is a different question from "what should I look at first" —
            // and it is the only thing that still surfaces a broken account once the ordering
            // below stops letting rowless accounts occupy a two-slot widget.
            overallSeverity: ordered.map(\.severity).max() ?? .stale,
            headlineShort: lead?.rows.first { $0.category == .fiveHour },
            headlineLong: lead?.rows.first { $0.category != .fiveHour },
            staleAfter: staleAfter)
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
        // Saturating, never trapping. `Int(_:)` in Swift traps on a value outside Int's range
        // where Kotlin's `.toInt()` saturates, and this runs inside a widget extension where a
        // trap is a blank tile and no diagnostic. `remainingPercent` already refuses non-finite
        // values; this is the second lock on the same door.
        guard remaining.isFinite else { return Int.max }
        let banded = remaining / 5
        guard banded < Double(Int.max) else { return Int.max }
        guard banded > Double(Int.min) else { return Int.min }
        return Int(banded)
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

    /// The one long-horizon row, chosen by how much it needs attention rather than by category.
    ///
    /// This was `weekly ?? monthly ?? other`, which reads as a sensible preference and is not
    /// one: `??` short-circuits on the mere EXISTENCE of a weekly window, so an account with a
    /// healthy weekly limit and an exhausted monthly one showed the weekly and the monthly was
    /// never considered. A user whose monthly quota had run out saw a full bar on the tile.
    ///
    /// `urgency` is the ranking, the same one that orders the accounts themselves, so the row
    /// and the list cannot disagree. It also settles what an unreadable window is worth: `.error`
    /// ranks BELOW `.exhausted`, so a window whose percentage could not be read does not
    /// displace one known to be empty. Ties go to the lower remaining percentage, then to the
    /// better-understood category — which keeps `.other`, whose duration no provider documents,
    /// a last resort among equals rather than one that can never appear.
    ///
    /// Kotlin's `WidgetDataBuilder.longHeadline` is the same rule; the two must agree.
    private static func longHeadline(_ windows: [UsageWindow]) -> GlanceRow? {
        windows
            .filter { $0.category != .fiveHour }
            .min { lhs, rhs in
                let lu = urgency(lhs.severity), ru = urgency(rhs.severity)
                if lu != ru { return lu < ru }
                let lp = lhs.remainingPercent ?? .greatestFiniteMagnitude
                let rp = rhs.remainingPercent ?? .greatestFiniteMagnitude
                if lp != rp { return lp < rp }
                return longCategoryRank(lhs.category) < longCategoryRank(rhs.category)
            }
            .map(row)
    }

    private static func longCategoryRank(_ category: WindowCategory) -> Int {
        switch category {
        case .weekly: return 0
        case .monthly: return 1
        default: return 2
        }
    }

    private static func glanceAccount(
        _ usage: AccountUsage, now: Date, staleAfter: TimeInterval, providerIcons: [String: String]
    ) -> GlanceAccount {
        let windows = usage.snapshot?.windows ?? []

        // Two horizons, not every window an account reports: a tile has room for about two
        // rows before it stops being readable at a glance, which is the entire point of it.
        let rows = [
            headline(windows, .fiveHour),
            longHeadline(windows),
        ].compactMap { $0 }

        var title = usage.account.provider.displayName
        // `whitespacesAndNewlines`, not `whitespaces`: the latter is space and tab only, so a
        // plan of "\n" survived as a plan and appended a blank line to a title in a
        // fixed-height tile. Kotlin's `isNotBlank` counts newlines, and the two must agree.
        if let plan = usage.account.plan,
           !plan.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, plan.caseInsensitiveCompare(title) != .orderedSame {
            title = plan.lowercased().hasPrefix(title.lowercased() + " ") ? plan : title + " \(plan)"
        }

        return GlanceAccount(
            id: usage.account.id,
            title: title,
            // The masked form, never the raw address: a widget renders on a lock screen.
            subtitle: usage.account.maskedEmail,
            rows: rows,
            severity: usage.snapshot?.severity(at: now, staleAfter: staleAfter) ?? .stale,
            // The unaged judgement and the instant it was made, so a widget rendering this
            // hours later can age it itself instead of repeating a verdict from write time.
            baseSeverity: usage.snapshot?.severity ?? .stale,
            fetchedAt: usage.snapshot?.fetchedAt, providerID: usage.account.provider.rawValue,
            connectionStatus: usage.snapshot?.connectionStatus ?? .unknown,
            resetDates: usage.snapshot?.windows.compactMap(\.resetAt),
            iconAssetName: ProviderIconCatalog.selected(for: usage.account.provider, id: providerIcons[usage.account.provider.rawValue]).assetName)
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
