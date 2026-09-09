import Foundation

/// The single scale the whole app colours by.
///
/// Order runs best to worst so `max()` picks the worst state across an account's windows.
/// Mirrors the Android `Severity` enum deliberately: both platforms must classify identically,
/// or the same account reads differently on a phone and a tablet.
public enum Severity: Int, Comparable, Sendable, Codable {
    case healthy = 0
    case medium = 1
    case low = 2
    case exhausted = 3
    case stale = 4
    case error = 5

    public static func < (lhs: Severity, rhs: Severity) -> Bool { lhs.rawValue < rhs.rawValue }

    /// Central thresholds — the one place these numbers are defined.
    public static let healthyAbove = 50.0
    public static let mediumAbove = 20.0

    /// Beyond this a snapshot is shown as stale rather than current.
    public static let staleAfter: TimeInterval = 60 * 60

    public static func from(remainingPercent: Double?, exhausted: Bool = false) -> Severity {
        if exhausted { return .exhausted }
        guard let remaining = remainingPercent else { return .error }
        if remaining <= 0 { return .exhausted }
        if remaining <= mediumAbove { return .low }
        if remaining <= healthyAbove { return .medium }
        return .healthy
    }
}
