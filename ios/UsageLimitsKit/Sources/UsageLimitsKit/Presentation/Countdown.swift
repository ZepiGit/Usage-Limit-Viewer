import Foundation

/// Renders "how long until this resets".
///
/// In the kit rather than in the SwiftUI layer for the same reason the glance ordering is: it
/// is a decision about what the user is told, it has edge cases worth pinning, and neither
/// platform's rendering code is where a rounding rule should live.
public enum Countdown {

    /// Whole units only, and never more than two: `2d 4h`, `3h 12m`, `8m`.
    ///
    /// Seconds are dropped deliberately. A number read at a glance gains nothing from them, and
    /// a ticking seconds field invites the reader to trust a figure that is only refreshed
    /// every half hour.
    ///
    /// A sub-minute remainder rounds UP to `1m` rather than down to `0m`: zero reads as "it has
    /// reset", which is the one thing that is not yet true.
    public static func format(until date: Date, from now: Date) -> String {
        // Clamped, not converted. `Int(_: Double)` TRAPS outside Int's range, and this is
        // called on raw parser output from four screens and the widget — so a provider
        // reporting an absurd reset instant crashed the process rather than printing an absurd
        // countdown. Kotlin's `toInt()` saturates, which is also why only this side could.
        let interval = date.timeIntervalSince(now)
        guard interval.isFinite else { return "now" }
        let clamped = min(max(interval, Double(Int.min)), -Double(Int.min) - 1)
        return format(seconds: Int(clamped))
    }

    public static func format(seconds: Int) -> String {
        guard seconds > 0 else { return "now" }

        let days = seconds / 86_400
        let hours = (seconds % 86_400) / 3_600
        let minutes = (seconds % 3_600) / 60

        if days > 0 { return hours > 0 ? "\(days)d \(hours)h" : "\(days)d" }
        if hours > 0 { return minutes > 0 ? "\(hours)h \(minutes)m" : "\(hours)h" }
        // "<1m", not "1m". Rounding the last fifty-nine seconds up to a minute invents time
        // the user does not have, on a countdown whose entire job is to say when something
        // comes back — and it disagreed with the Kotlin twin, which has always said "<1m".
        return minutes > 0 ? "\(minutes)m" : "<1m"
    }

    /// The absolute local time, for a surface that cannot tick.
    ///
    /// A widget recomposes only when its worker updates it, so a relative countdown rendered
    /// half an hour ago is not merely stale — it can be wrong in the one direction that
    /// matters, because the limit may already have reset. An absolute time stays true however
    /// old the render is, so that is what a widget shows.
    ///
    /// The date is omitted when the reset is today, which is the common case and the one where
    /// a date would only add noise.
    public static func absolute(_ date: Date, now: Date, calendar: Calendar = .current) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = .current
        formatter.dateStyle = calendar.isDate(date, inSameDayAs: now) ? .none : .short
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}
