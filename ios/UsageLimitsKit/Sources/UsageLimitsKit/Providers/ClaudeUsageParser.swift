import Foundation

/// Turns the Anthropic `/api/oauth/usage` payload into normalised windows.
///
/// The payload is flat: each window sits at the top level under its own key — there is no
/// wrapper object. Every window reports `utilization` (percent consumed) and `resets_at`.
///
/// Windows are read in the fixed order declared by `usageWindowKeys`; unknown top-level keys
/// are ignored, so Anthropic adding a window costs nothing and removing one costs a single
/// row.
public enum ClaudeUsageParser {

    /// ProviderEndpoints.Claude.USAGE_WINDOW_KEYS — the windows `/api/oauth/usage` may carry,
    /// in the fixed read order. The label is what the UI shows for the row.
    private static let usageWindowKeys: [(key: String, label: String)] = [
        ("five_hour", "5h limit"),
        ("seven_day", "Weekly"),
        ("seven_day_oauth_apps", "Weekly (OAuth apps)"),
        ("seven_day_opus", "Weekly (Opus)"),
        ("seven_day_sonnet", "Weekly (Sonnet)"),
        ("seven_day_cowork", "Weekly (Cowork)"),
        ("iguana_necktie", "Weekly (Fable)"),
    ]

    private static let fiveHourKey = "five_hour"
    private static let fiveHourSeconds: Int64 = 18_000
    private static let sevenDaySeconds: Int64 = 604_800

    /// Upstream's current key for the Fable weekly window — a codename, not a typo.
    private static let fableWindowKey = "iguana_necktie"

    public static func parse(_ payload: [String: Any], now: Date) -> [UsageWindow] {
        // `now` is part of the shared parser surface, but this payload carries absolute
        // `resets_at` timestamps, so nothing here needs the clock.

        // The `limits` array carries per-model weekly windows. When it describes Fable it is
        // the better source than the opaque `iguana_necktie` key, so it supersedes that key
        // rather than rendering the same quota twice.
        let fable = findFableLimit(payload)

        let windows = usageWindowKeys.compactMap { (key, label) -> UsageWindow? in
            if key == fableWindowKey && fable != nil { return nil }
            guard let window = JSONSupport.object(payload, key) else { return nil }

            // A window present but with no usable `utilization` is skipped rather than emitted
            // with a nil usedPercent: nil resolves to Severity.error and snapshot severity is
            // a MAX over the windows, so one renamed upstream field would paint the whole
            // account red while that same row sorts last in any "most critical" ordering.
            guard let used = JSONSupport.double(window, "utilization") else { return nil }

            return toWindow(
                id: key.replacingOccurrences(of: "_", with: "-"),
                label: label,
                key: key,
                usedPercent: used,
                resetsAt: JSONSupport.string(window, "resets_at", "resetsAt"))
        }

        // findFableLimit only yields entries that already carry a `percent`; re-checking here
        // keeps the same shape as the upstream guard so a nil can never emit a window.
        guard let limit = fable, let percent = JSONSupport.double(limit, "percent") else {
            return windows
        }

        return windows + [toWindow(
            id: "seven-day-fable",
            label: "Weekly (Fable)",
            key: fableWindowKey,
            usedPercent: percent,
            resetsAt: JSONSupport.string(limit, "resets_at", "resetsAt"))]
    }

    /// Reads the plan label the profile endpoint reports, for the account subtitle.
    public static func parsePlan(_ profile: [String: Any]) -> String? {
        guard let account = JSONSupport.object(profile, "account") else { return nil }
        if JSONSupport.bool(account, "has_claude_max", "hasClaudeMax") == true { return "Max" }
        if JSONSupport.bool(account, "has_claude_pro", "hasClaudePro") == true { return "Pro" }
        return nil
    }

    /// Finds the active weekly Fable entry in `limits`, if there is one.
    ///
    /// Prefers the entry marked active; several can be present when a plan is mid-transition.
    private static func findFableLimit(_ payload: [String: Any]) -> [String: Any]? {
        let candidates: [[String: Any]] = JSONSupport.array(payload, "limits").compactMap { element in
            // Non-object entries in `limits` describe other quota shapes; ignore them so a
            // changed payload costs one row, never the whole parse.
            guard let limit = element as? [String: Any] else { return nil }

            guard JSONSupport.string(limit, "kind")?.lowercased() == "weekly_scoped" else { return nil }

            let model = JSONSupport.object(JSONSupport.object(limit, "scope"), "model")
            let name = JSONSupport.string(model, "display_name", "displayName")?.lowercased()
            guard name == "fable" || name == "fable 5" else { return nil }

            // No percent means nothing renderable; skip rather than emit a nil.
            guard JSONSupport.double(limit, "percent") != nil else { return nil }
            return limit
        }

        return candidates.first { JSONSupport.bool($0, "is_active", "isActive") == true }
            ?? candidates.first
    }

    private static func toWindow(
        id: String,
        label: String,
        key: String,
        usedPercent: Double,
        resetsAt: String?
    ) -> UsageWindow {
        // Only `five_hour` is a rolling five-hour window; every other key Anthropic exposes is
        // a seven-day window, so the period is derived from the key rather than the payload,
        // which never states it.
        let periodSeconds = key == fiveHourKey ? fiveHourSeconds : sevenDaySeconds

        return UsageWindow(
            id: id,
            label: label,
            category: WindowCategory.from(periodSeconds: periodSeconds),
            usedPercent: usedPercent,
            periodSeconds: periodSeconds,
            resetAt: JSONSupport.date(resetsAt),
            exhausted: usedPercent >= 100.0)
    }
}