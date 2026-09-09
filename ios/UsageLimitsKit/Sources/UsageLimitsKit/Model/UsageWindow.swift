import Foundation

/// How long a quota window spans.
///
/// Derived from the duration the provider reports, never from the window's position in the
/// payload. That distinction is load-bearing: Codex puts a *monthly* window in the secondary
/// slot on team plans, so trusting position labels a month as a week.
public enum WindowCategory: String, Sendable, Codable {
    case fiveHour
    case weekly
    case monthly
    case other

    private static let fiveHourSeconds: Int64 = 5 * 60 * 60          // 18_000
    private static let weekSeconds: Int64 = 7 * 24 * 60 * 60         // 604_800
    private static let minMonthSeconds: Int64 = 28 * 24 * 60 * 60    // 2_419_200
    private static let maxMonthSeconds: Int64 = 31 * 24 * 60 * 60    // 2_678_400

    /// Months are a range, not a constant: calendar months differ in length and providers
    /// report the real period. Anything unrecognised stays `.other` so an unknown future
    /// window still renders rather than being forced into the wrong bucket.
    public static func from(periodSeconds: Int64?) -> WindowCategory {
        guard let seconds = periodSeconds else { return .other }
        switch seconds {
        case fiveHourSeconds: return .fiveHour
        case weekSeconds: return .weekly
        case minMonthSeconds...maxMonthSeconds: return .monthly
        default: return .other
        }
    }
}

/// One quota bar, normalised from whatever shape the provider used.
///
/// `usedPercent` is 0…100 **consumed**; `remainingPercent` is derived. Every provider reports
/// consumption while the UI reads "82 % remaining", and conflating the two is the single
/// easiest way to render a confidently inverted number.
public struct UsageWindow: Sendable, Codable, Equatable {
    public let id: String
    public let label: String
    public let category: WindowCategory
    public let usedPercent: Double?
    public let periodSeconds: Int64?
    public let resetAt: Date?
    public let exhausted: Bool
    /// Optional grouping key — an Antigravity quota group, or Codex "code review".
    public let group: String?

    public init(
        id: String,
        label: String,
        category: WindowCategory,
        usedPercent: Double?,
        periodSeconds: Int64?,
        resetAt: Date?,
        exhausted: Bool,
        group: String? = nil
    ) {
        self.id = id
        self.label = label
        self.category = category
        self.usedPercent = usedPercent
        self.periodSeconds = periodSeconds
        self.resetAt = resetAt
        self.exhausted = exhausted
        self.group = group
    }

    public var remainingPercent: Double? {
        usedPercent.map { min(max(100.0 - $0, 0.0), 100.0) }
    }

    public var severity: Severity {
        Severity.from(remainingPercent: remainingPercent, exhausted: exhausted)
    }
}
