import Foundation

/// Reads Kimi Code's `GET /coding/v1/usages`.
///
/// The response carries two kinds of window, and — this is the part worth knowing — they report
/// consumption in OPPOSITE terms:
///
/// - the weekly block under `usage` has `limit` and `used`, and never `remaining`
/// - each entry under `limits[].detail` has `limit` and `remaining`, and never `used`
///
/// Reading only `remaining` therefore computes nothing for the weekly window, so an account that
/// has spent its whole week renders as an untouched bar. That is a real defect other clients
/// shipped and later fixed; this parser takes either field and derives the percentage from
/// whichever one is present. Its Kotlin twin does the same, and both pin it in tests.
public enum KimiUsageParser {

    private static let fiveHoursSeconds: Int64 = 5 * 60 * 60
    private static let weekSeconds: Int64 = 7 * 24 * 60 * 60

    /// Epoch seconds for any date this decade are about 1.8e9; epoch millis about 1.8e12.
    /// Anything below this is seconds.
    private static let secondsCutoff: Int64 = 100_000_000_000

    public static func parse(_ payload: [String: Any]) -> [UsageWindow] {
        var windows: [UsageWindow] = []
        if let weekly = weeklyWindow(payload) { windows.append(weekly) }
        windows.append(contentsOf: rateLimitWindows(payload))
        return windows
    }

    /// The membership's weekly request allowance, which Kimi states as `used`.
    private static func weeklyWindow(_ payload: [String: Any]) -> UsageWindow? {
        guard let usage = JSONSupport.object(payload, "usage") else { return nil }
        guard let percent = usedPercent(
            limit: JSONSupport.double(usage, "limit", "total"),
            used: JSONSupport.double(usage, "used"),
            remaining: JSONSupport.double(usage, "remaining")
        ) else { return nil }

        return UsageWindow(
            id: "kimi-weekly",
            label: "Weekly",
            category: .weekly,
            usedPercent: percent,
            periodSeconds: weekSeconds,
            resetAt: resetInstant(usage),
            exhausted: percent >= 100
        )
    }

    /// The rolling rate limits, which is where the five-hour window lives.
    ///
    /// The duration is read from the entry rather than assumed from its position. Classifying by
    /// position is how a monthly bucket ends up labelled as a week.
    private static func rateLimitWindows(_ payload: [String: Any]) -> [UsageWindow] {
        JSONSupport.array(payload, "limits").enumerated().compactMap { index, element in
            guard let entry = element as? [String: Any] else { return nil }
            let detail = JSONSupport.object(entry, "detail") ?? entry

            guard let percent = usedPercent(
                limit: JSONSupport.double(detail, "limit", "total"),
                used: JSONSupport.double(detail, "used"),
                remaining: JSONSupport.double(detail, "remaining")
            ) else { return nil }

            let seconds = JSONSupport.int64(entry, "windowSeconds", "window_seconds")
                ?? JSONSupport.int64(detail, "windowSeconds", "window_seconds")
                ?? fiveHoursSeconds
            let label = JSONSupport.string(entry, "name", "label")
                ?? JSONSupport.string(detail, "name", "label")
                ?? defaultLabel(seconds)

            return UsageWindow(
                id: "kimi-limit-\(index)",
                label: label,
                category: WindowCategory.from(periodSeconds: seconds),
                usedPercent: percent,
                periodSeconds: seconds,
                resetAt: resetInstant(detail) ?? resetInstant(entry),
                exhausted: percent >= 100
            )
        }
    }

    /// Consumption as a percentage, from whichever of the two fields the block carries.
    ///
    /// `used` wins when both are present: the weekly block states it directly, and a derived
    /// number should never override a reported one.
    private static func usedPercent(limit: Double?, used: Double?, remaining: Double?) -> Double? {
        // A zero limit is not a full bar — it is a provider that stated no limit, and dividing
        // by it gives infinity or NaN.
        guard let limit, limit > 0 else { return nil }
        let consumed: Double
        if let used {
            consumed = used
        } else if let remaining {
            consumed = limit - remaining
        } else {
            return nil
        }
        return min(max(consumed / limit * 100, 0), 100)
    }

    /// The reset instant, in whichever unit it arrived.
    private static func resetInstant(_ object: [String: Any]?) -> Date? {
        guard let object,
              let raw = JSONSupport.int64(object, "resetTime", "reset_time", "resetAt", "reset_at"),
              raw > 0
        else { return nil }
        let millis = raw < secondsCutoff ? raw * 1000 : raw
        return Date(timeIntervalSince1970: Double(millis) / 1000)
    }

    private static func defaultLabel(_ seconds: Int64) -> String {
        switch seconds {
        case fiveHoursSeconds: return "5h limit"
        case weekSeconds: return "Weekly"
        default: return "Rate limit"
        }
    }
}
