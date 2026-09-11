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

    /// The flat keys, in the fixed read order — the endpoints table itself, not a copy of it.
    /// Two tables that have to say the same thing eventually will not.
    private static var usageWindowKeys: [(key: String, label: String)] {
        ProviderEndpoints.Claude.usageWindowKeys
    }

    private static let fiveHourKey = "five_hour"
    private static let fiveHourSeconds: Int64 = 18_000
    private static let sevenDaySeconds: Int64 = 604_800

    private static let fiveHourID = "five-hour"
    private static let sevenDayID = "seven-day"

    /// Top-level objects that look like windows but are not.
    ///
    /// The discovery gate asks whether a key declares `resets_at`, and `extra_usage` is one
    /// field away from passing it — it already carries a `utilization`. It is a CREDIT BALANCE:
    /// folding it in would grade an account by money spent, and spending credits would trip an
    /// exhaustion alert. Named explicitly rather than inferred, because the shape that
    /// distinguishes them today is one upstream field away from being ambiguous.
    private static let nonWindowKeys: Set<String> = [
        "extra_usage", "spend", "seven_day_breakdown", "limits", "organization", "account",
    ]

    /// A label is a row on a phone, and a server-supplied key has no length limit.
    private static let maximumLabelLength = 48

    /// A `utilization` of 21.0 means 21 % consumed, not 21 % of one.
    ///
    /// Worth stating because the field is named `utilization` rather than `percent`, and a
    /// fractional reading would put every fallback number out by a factor of a hundred while
    /// still looking like a number. A live account settles it: `five_hour.utilization` was 21.0
    /// and the `limits` entry for the same quota reported `percent: 21`.
    public static func parse(_ payload: [String: Any], now _: Date) -> [UsageWindow] {
        // `now` is part of the shared parser surface, but this payload carries absolute
        // `resets_at` timestamps, so nothing here needs the clock.
        let primary = parseLimits(payload)

        // Everything `limits` did not already account for. `limits` is upstream's own view and
        // has been complete on every payload seen, but "has been complete" is not a guarantee:
        // a partial one would silently hide live windows, and treating it as all-or-nothing
        // made that unrecoverable.
        let supplemental = (parseFlatWindows(payload) + discoverUnknownWindows(payload))
            .filter { candidate in
                // Same id is the same quota by construction; so is the same reset instant, when
                // one is stated. Two windows that both report no reset are not thereby the same
                // window, so an absent instant never merges anything.
                !primary.contains { $0.id == candidate.id }
                    && !(candidate.resetAt != nil
                         && primary.contains { $0.resetAt == candidate.resetAt })
            }

        let windows = primary + supplemental.filter { ($0.usedPercent ?? 0) > 0 }
        if !windows.isEmpty { return windows }

        // Untouched codename slots are noise on a screen meant to be read in three seconds —
        // unless they are all an account has. Dropping them unconditionally turned a healthy,
        // completely unused account into zero windows, which resolves to `.error`: the app
        // reporting a fault where the real answer was "nothing used yet".
        return supplemental
    }

    /// Reads the plan label the profile endpoint reports, for the account subtitle.
    ///
    /// `organization.rate_limit_tier` is preferred over the `has_claude_max` boolean because the
    /// boolean cannot tell a Max 5× subscription from a Max 20× one — a fivefold difference in
    /// the very quantity this app exists to show. A live profile reports
    /// `default_claude_max_5x`, so the multiplier is right there; the booleans are the fallback
    /// for a payload that omits the tier.
    public static func parsePlan(_ profile: [String: Any]) -> String? {
        let organization = JSONSupport.object(profile, "organization")
        if let plan = planFromTier(
            JSONSupport.string(organization, "rate_limit_tier", "rateLimitTier")) {
            return plan
        }

        guard let account = JSONSupport.object(profile, "account") else { return nil }
        if JSONSupport.bool(account, "has_claude_max", "hasClaudeMax") == true { return "Max" }
        if JSONSupport.bool(account, "has_claude_pro", "hasClaudePro") == true { return "Pro" }
        return nil
    }

    /// Turns `default_claude_max_5x` into `Max 5×`.
    ///
    /// Read structurally rather than from a table of known tiers: Anthropic adds tiers, and a
    /// table would render a new one as no plan at all. An unrecognised tier still yields
    /// something readable, which beats a blank subtitle.
    private static func planFromTier(_ tier: String?) -> String? {
        var raw = tier?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        for prefix in ["default_", "claude_"] where raw.hasPrefix(prefix) {
            raw.removeFirst(prefix.count)
        }
        guard !raw.isEmpty else { return nil }

        var multiplier: String?
        // The separator must actually be there. Without the `_` check, a tier of "claude_20x"
        // strips to "20x", the "last component" is the whole string, and removing its length
        // PLUS the separator takes four characters from a three-character string — a
        // precondition failure, which is to say a crash, from a value the provider chose.
        //
        // Requiring the separator also matches the Kotlin regex, which demands a `_` before the
        // digits: a bare "20x" is a tier name, not a multiplier, and reads as one.
        if let last = raw.split(separator: "_").last,
           raw.hasSuffix("_\(last)"),
           last.hasSuffix("x"),
           case let digits = String(last.dropLast()),
           !digits.isEmpty,
           // ASCII digits only. `isNumber` accepts every Unicode numeric category, so an
           // Arabic-Indic or superscript digit became a multiplier on iOS and not on Android —
           // and "Max ²×" is noise either way. Tier ids are ASCII.
           digits.allSatisfy({ $0.isASCII && $0.isNumber }) {
            multiplier = digits
            raw.removeLast(last.count + 1)
        }

        let name = raw.split(separator: "_")
            .map { $0.prefix(1).uppercased() + $0.dropFirst() }
            .joined(separator: " ")
        guard !name.isEmpty else { return nil }

        return multiplier.map { "\(name) \($0)×" } ?? name
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

            // Identity prefers the model's own id: two display names that differ only in
            // punctuation ("Sonnet 4.5" and "sonnet-4.5") slug identically, and the loser was
            // silently dropped — the user then read one model's figure as the other's.
            let modelKey = JSONSupport.string(
                JSONSupport.object(JSONSupport.object(limit, "scope"), "model"), "id") ?? model

            let baseID: String
            switch kind {
            case "session": baseID = fiveHourID
            case "weekly_all": baseID = sevenDayID
            case "weekly_scoped": baseID = "\(sevenDayID)-\(slug(modelKey ?? "scoped"))"
            default: baseID = slug(kind ?? "limit")
            }
            // A collision suffixes rather than drops. Array position is deliberately not part
            // of identity: reordering `limits` would then rename every window, detaching
            // anything keyed by it — a notification's deduplication record, for one.
            var id = baseID
            var suffix = 2
            while !seen.insert(id).inserted {
                id = "\(baseID)-\(suffix)"
                suffix += 1
            }

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
            default: label = String(humanize(kind ?? "Limit \(index + 1)")
                .prefix(maximumLabelLength))
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
    private static func discoverUnknownWindows(_ payload: [String: Any]) -> [UsageWindow] {
        let knownKeys = Set(usageWindowKeys.map(\.key))

        return payload.compactMap { (key, value) -> UsageWindow? in
            guard !knownKeys.contains(key), !nonWindowKeys.contains(key) else { return nil }
            guard let entry = value as? [String: Any] else { return nil }
            guard entry.keys.contains("resets_at") || entry.keys.contains("resetsAt") else {
                return nil
            }
            guard let used = JSONSupport.double(entry, "utilization") else { return nil }

            let id = key
                .replacingOccurrences(of: "_", with: "-")
                .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
            // Blank, not merely empty — and tested on the ID, which is what Kotlin's `isBlank`
            // tests. Testing the key instead let " -" through: the key is not all whitespace,
            // but its id is " ", and a window whose id is a single space was a real row on iOS
            // and no row on Android.
            guard !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }

            return window(
                id: id,
                label: String(humanize(key).prefix(maximumLabelLength)),
                usedPercent: used,
                // The duration of a window nobody has documented is unknown, and `.other` says
                // so. It cannot be inferred from the time left before its reset either: a
                // weekly window observed two hours before it rolls over would infer two hours,
                // which is a confidently wrong category rather than an honest unknown.
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
            // Blank parts dropped, not just empty ones: `split` omits empty subsequences but
            // keeps " ", so "nimbus_ _quill" read "Nimbus   Quill" here and "Nimbus Quill" on
            // Android.
            .filter { !$0.allSatisfy(\.isWhitespace) }
            .map { $0.prefix(1).uppercased() + $0.dropFirst() }
            .joined(separator: " ")
    }

    private static func slug(_ value: String) -> String {
        let lowered = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        // ASCII only, matching the Kotlin `[^a-z0-9]+` regex. These slugs are identity keys —
        // they dedupe windows and they are embedded in notification keys — so the two platforms
        // keying "claude-é-4" differently would mean the same quota tracked under two names.
        let mapped = lowered.map { character -> Character in
            character.isASCII && (character.isLetter || character.isNumber) ? character : "-"
        }
        let collapsed = String(mapped)
            .split(separator: "-", omittingEmptySubsequences: true)
            .joined(separator: "-")
        return collapsed.isEmpty ? "window" : collapsed
    }
}
