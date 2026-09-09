import Foundation

/// Turns the Anthropic `/api/oauth/usage` payload into normalised windows.
///
/// The payload carries the same quotas twice. Each window sits at the top level under its own
/// key — `five_hour`, `seven_day`, and a rotating cast of codenames — and the same figures also
/// appear in a `limits` array that is Anthropic's own presentation model, carrying `kind`,
/// `group`, `percent`, `resets_at` and, for per-model quotas, `scope.model.display_name`.
///
/// `limits` is preferred whenever it is present. It is labelled, already deduplicated, and it
/// is the only place some quotas appear at all: on a live account the top-level `iguana_necktie`
/// key was null while `limits` carried the Fable figure it is supposed to hold. Reading both
/// would double-count, so the flat keys are the fallback for a payload with no `limits` array
/// rather than a second source.
///
/// A third pass then sweeps up window-shaped keys neither list knows about, because a quota the
/// provider reports and the app hides is a wrong number with nothing visibly broken. That sweep
/// is deliberately narrow — see `discoverUnknownWindows`.
///
/// This mirrors the Kotlin `ClaudeUsageParser` decision for decision; the two must agree or the
/// same account reads differently on the two platforms.
public enum ClaudeUsageParser {

    /// ProviderEndpoints.Claude.usageWindowKeys — the flat keys, in the fixed read order.
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

    private static let fiveHourID = "five-hour"
    private static let sevenDayID = "seven-day"

    public static func parse(_ payload: [String: Any], now _: Date) -> [UsageWindow] {
        // `now` is part of the shared parser surface, but this payload carries absolute
        // `resets_at` timestamps, so nothing here needs the clock.
        let fromLimits = parseLimits(payload)
        let primary = fromLimits.isEmpty ? parseFlatWindows(payload) : fromLimits
        return primary + discoverUnknownWindows(payload, known: primary)
    }

    /// Reads the plan label the profile endpoint reports, for the account subtitle.
    public static func parsePlan(_ profile: [String: Any]) -> String? {
        guard let account = JSONSupport.object(profile, "account") else { return nil }
        if JSONSupport.bool(account, "has_claude_max", "hasClaudeMax") == true { return "Max" }
        if JSONSupport.bool(account, "has_claude_pro", "hasClaudePro") == true { return "Pro" }
        return nil
    }

    /// Maps the `limits` array one entry to one window.
    ///
    /// An entry whose `kind` is unrecognised is kept rather than dropped: `group` still says
    /// whether it is a session or a weekly quota, and a window with no known duration is more
    /// useful than a missing one. Entries are deduplicated by id, first occurrence winning.
    private static func parseLimits(_ payload: [String: Any]) -> [UsageWindow] {
        var seen = Set<String>()
        var windows: [UsageWindow] = []

        for (index, element) in JSONSupport.array(payload, "limits").enumerated() {
            // Non-object entries describe other quota shapes; ignore them so a changed payload
            // costs one row, never the whole parse.
            guard let limit = element as? [String: Any] else { continue }
            guard let percent = JSONSupport.double(limit, "percent") else { continue }

            let kind = JSONSupport.string(limit, "kind")
            let group = JSONSupport.string(limit, "group")
            let model = JSONSupport.string(
                JSONSupport.object(JSONSupport.object(limit, "scope"), "model"),
                "display_name", "displayName")

            let id: String
            switch kind {
            case "session": id = fiveHourID
            case "weekly_all": id = sevenDayID
            case "weekly_scoped": id = "\(sevenDayID)-\(slug(model ?? String(index)))"
            default: id = slug(kind ?? "limit-\(index)")
            }
            guard seen.insert(id).inserted else { continue }

            let periodSeconds: Int64?
            if kind == "session" || group == "session" {
                periodSeconds = fiveHourSeconds
            } else if kind?.hasPrefix("weekly") == true || group == "weekly" {
                periodSeconds = sevenDaySeconds
            } else {
                periodSeconds = nil
            }

            let label: String
            switch kind {
            case "session": label = "5h limit"
            case "weekly_all": label = "Weekly"
            case "weekly_scoped": label = model.map { "Weekly (\($0))" } ?? "Weekly (scoped)"
            default: label = humanize(kind ?? "Limit \(index + 1)")
            }

            windows.append(window(
                id: id,
                label: label,
                usedPercent: percent,
                periodSeconds: periodSeconds,
                resetsAt: JSONSupport.string(limit, "resets_at", "resetsAt")))
        }

        return windows
    }

    /// Reads the flat top-level window keys, for a payload that carries no `limits` array.
    ///
    /// A key whose value is JSON null, or whose `utilization` is missing, is skipped rather
    /// than emitted with a nil usedPercent: nil resolves to Severity.error and snapshot
    /// severity is a MAX over the windows, so one unused bucket would paint the whole account
    /// red while that same row sorts last in any "most critical" ordering.
    private static func parseFlatWindows(_ payload: [String: Any]) -> [UsageWindow] {
        usageWindowKeys.compactMap { (key, label) -> UsageWindow? in
            guard let entry = JSONSupport.object(payload, key) else { return nil }
            guard let used = JSONSupport.double(entry, "utilization") else { return nil }

            return window(
                id: key.replacingOccurrences(of: "_", with: "-"),
                label: label,
                usedPercent: used,
                periodSeconds: key == fiveHourKey ? fiveHourSeconds : sevenDaySeconds,
                resetsAt: JSONSupport.string(entry, "resets_at", "resetsAt"))
        }
    }

    /// Sweeps up live quotas under keys nothing else knows about.
    ///
    /// Anthropic ships new windows under rotating codenames — a live account carried eight the
    /// registry had never seen — so a fixed key list silently loses a quota the moment one is
    /// introduced, and the number on screen goes quietly wrong.
    ///
    /// The sweep is narrow on purpose, because the obvious rule is wrong. "Any object with a
    /// numeric `utilization`" also matches `extra_usage`, which is a credit balance rather than
    /// a rate-limit window; folding it into a MAX-over-windows severity would mis-grade the
    /// account. A window declares `resets_at` even when that value is null, and a balance never
    /// does, so that key is the discriminator.
    ///
    /// Only windows with something actually consumed are promoted. The unused codename slots
    /// are placeholders, and a screen meant to be read in three seconds does not need a row
    /// reading "Nimbus Quill — 0 % used".
    ///
    /// Overlap is tested by id, which cannot catch a codename key that `limits` also reports
    /// under a different `kind` — that would render one quota twice. The trade is deliberate:
    /// a duplicated row is visible and obviously wrong, while a hidden quota is invisible and
    /// makes the number on screen wrong with nothing to notice.
    private static func discoverUnknownWindows(
        _ payload: [String: Any],
        known: [UsageWindow]
    ) -> [UsageWindow] {
        let knownKeys = Set(usageWindowKeys.map(\.key))
        let knownIDs = Set(known.map(\.id))

        return payload.compactMap { (key, value) -> UsageWindow? in
            guard !knownKeys.contains(key) else { return nil }
            guard let entry = value as? [String: Any] else { return nil }
            guard entry.keys.contains("resets_at") || entry.keys.contains("resetsAt") else {
                return nil
            }
            guard let used = JSONSupport.double(entry, "utilization"), used > 0 else { return nil }

            let id = key.replacingOccurrences(of: "_", with: "-")
            guard !knownIDs.contains(id) else { return nil }

            return window(
                id: id,
                label: humanize(key),
                usedPercent: used,
                // The duration of a window nobody has documented is unknown, and `.other` says
                // so. Guessing seven days would put a wrong countdown on the Resets screen.
                periodSeconds: nil,
                resetsAt: JSONSupport.string(entry, "resets_at", "resetsAt"))
        }
        // A dictionary has no order, so sorting is what makes the output reproducible at all.
        .sorted { $0.id < $1.id }
    }

    private static func window(
        id: String,
        label: String,
        usedPercent: Double,
        periodSeconds: Int64?,
        resetsAt: String?
    ) -> UsageWindow {
        UsageWindow(
            id: id,
            label: label,
            category: WindowCategory.from(periodSeconds: periodSeconds),
            usedPercent: usedPercent,
            periodSeconds: periodSeconds,
            resetAt: JSONSupport.date(resetsAt),
            exhausted: usedPercent >= 100.0)
    }

    /// `nimbus_quill` reads as "Nimbus Quill" — the only label an undocumented key can have.
    private static func humanize(_ key: String) -> String {
        key.split(whereSeparator: { $0 == "_" || $0 == "-" })
            .map { $0.prefix(1).uppercased() + $0.dropFirst() }
            .joined(separator: " ")
    }

    private static func slug(_ value: String) -> String {
        let lowered = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let mapped = lowered.map { character -> Character in
            character.isLetter || character.isNumber ? character : "-"
        }
        let collapsed = String(mapped)
            .split(separator: "-", omittingEmptySubsequences: true)
            .joined(separator: "-")
        return collapsed.isEmpty ? "window" : collapsed
    }
}
