import Foundation

public struct ResetCredit: Sendable, Codable, Equatable {
    public let id: String
    public let grantedAt: Date?
    public let expiresAt: Date?
    public let status: String

    public init(id: String, grantedAt: Date?, expiresAt: Date?, status: String) {
        self.id = id
        self.grantedAt = grantedAt
        self.expiresAt = expiresAt
        self.status = status
    }
}

public enum CodexUsageParser: Sendable {
    public static func parse(_ payload: [String: Any], now: Date) -> [UsageWindow] {
        var windows = windowsFor(
            JSONSupport.object(payload, "rate_limit", "rateLimit"),
            idPrefix: "codex",
            labelPrefix: nil,
            now: now
        )

        windows += windowsFor(
            JSONSupport.object(payload, "code_review_rate_limit", "codeReviewRateLimit"),
            idPrefix: "code-review",
            labelPrefix: "Code review",
            now: now,
            group: "Code review"
        )

        for (index, element) in JSONSupport.array(
            payload, "additional_rate_limits", "additionalRateLimits"
        ).enumerated() {
            guard let entry = element as? [String: Any] else { continue }

            // Extras nest their windows one level deeper. Missing them can hide the
            // account's only exhausted limit; flat entries remain a legacy fallback.
            let info = JSONSupport.object(entry, "rate_limit", "rateLimit") ?? entry
            let name = JSONSupport.string(
                entry,
                "name",
                "limit_name",
                "limitName",
                "metered_feature",
                "meteredFeature"
            ) ?? "Additional \(index + 1)"

            windows += windowsFor(
                info,
                idPrefix: "additional-\(slug(name))-\(index)",
                labelPrefix: name,
                now: now,
                group: name
            )
        }

        return windows
    }

    public static func parseResetCredits(_ payload: [String: Any]?) -> [ResetCredit] {
        JSONSupport.array(payload, "credits").compactMap { element -> ResetCredit? in
            guard let credit = element as? [String: Any] else { return nil }

            // Older responses omit the type. Explicitly unrelated or spent credits
            // must not be offered as usable Codex resets.
            let resetType = JSONSupport.string(credit, "reset_type", "resetType")
            guard resetType == nil || resetType == "codex_rate_limits",
                  let status = JSONSupport.string(credit, "status"),
                  status == "available",
                  let id = JSONSupport.string(credit, "id")
            else {
                return nil
            }

            return ResetCredit(
                id: id,
                grantedAt: JSONSupport.date(
                    JSONSupport.string(credit, "granted_at", "grantedAt")
                ) ?? JSONSupport.date(
                    epoch: JSONSupport.int64(credit, "granted_at", "grantedAt")
                ),
                expiresAt: JSONSupport.date(
                    JSONSupport.string(credit, "expires_at", "expiresAt")
                ) ?? JSONSupport.date(
                    epoch: JSONSupport.int64(credit, "expires_at", "expiresAt")
                ),
                status: status
            )
        }
    }

    public static func parseEmbeddedResetCredits(
        _ payload: [String: Any]
    ) -> [ResetCredit] {
        // This summary remains useful if the authoritative credits endpoint fails.
        parseResetCredits(
            JSONSupport.object(payload, "rate_limit_reset_credits", "rateLimitResetCredits")
        )
    }

    public static func availableCreditCount(_ payload: [String: Any]?) -> Int? {
        count(payload, "available_count", "availableCount")
    }

    /// How many of those credits can be spent against the limit that is currently reached.
    ///
    /// Production payloads carry `available_count` and `applicable_available_count` side by
    /// side and no `credits` array at all, so these are the only two numbers there are. They
    /// are kept apart rather than collapsed because one sample cannot settle what a zero here
    /// means: it may be "you hold credits but none apply to this limit", or simply "no limit is
    /// currently reached". Under the first reading, spending the held count offers a button the
    /// server will refuse; under the second, showing only the applicable count hides credits the
    /// user really holds. Carrying both is correct under either.
    public static func applicableCreditCount(_ payload: [String: Any]?) -> Int? {
        count(payload, "applicable_available_count", "applicableAvailableCount")
    }

    private static func count(_ payload: [String: Any]?, _ names: String...) -> Int? {
        guard let value = JSONSupport.double(payload, names[0], names[1]) else { return nil }

        // Match Kotlin Double.toInt(): truncate, saturate to signed 32-bit bounds,
        // and map NaN to zero rather than trapping on Swift's numeric conversion.
        if value.isNaN { return 0 }
        if value >= Double(Int32.max) { return Int(Int32.max) }
        if value <= Double(Int32.min) { return Int(Int32.min) }
        return Int(value.rounded(.towardZero))
    }

    public static func parsePlan(_ payload: [String: Any]) -> String? {
        JSONSupport.string(payload, "plan_type", "planType")
    }

    private static func windowsFor(
        _ limitInfo: [String: Any]?,
        idPrefix: String,
        labelPrefix: String?,
        now: Date,
        group: String? = nil
    ) -> [UsageWindow] {
        guard let limitInfo else { return [] }

        let windows = [
            JSONSupport.object(limitInfo, "primary_window", "primaryWindow"),
            JSONSupport.object(limitInfo, "secondary_window", "secondaryWindow")
        ]

        // Exhaustion belongs to the family, not necessarily to either child window.
        let limitReached =
            JSONSupport.bool(limitInfo, "limit_reached", "limitReached") == true ||
            JSONSupport.bool(limitInfo, "allowed") == false

        let classified = classify(windows)
        var result: [UsageWindow] = []

        if let index = classified.short, let window = windows[index] {
            result.append(
                toWindow(
                    window,
                    id: "\(idPrefix)-short",
                    labelPrefix: labelPrefix,
                    limitReached: limitReached,
                    now: now,
                    group: group
                )
            )
        }

        if let index = classified.long, let window = windows[index] {
            result.append(
                toWindow(
                    window,
                    id: "\(idPrefix)-long",
                    labelPrefix: labelPrefix,
                    limitReached: limitReached,
                    now: now,
                    group: group
                )
            )
        }

        return result
    }

    private static func classify(
        _ windows: [[String: Any]?]
    ) -> (short: Int?, long: Int?) {
        var shortIndex: Int?
        var longIndex: Int?

        // Slot indices preserve identity without comparing dictionary contents.
        // Team plans can place a monthly limit in the nominally weekly slot.
        for (index, candidate) in windows.enumerated() {
            guard let window = candidate else { continue }

            switch WindowCategory.from(periodSeconds: periodSeconds(window)) {
            case .fiveHour:
                if shortIndex == nil { shortIndex = index }
            case .weekly, .monthly:
                if longIndex == nil { longIndex = index }
            case .other:
                break
            }
        }

        // Position is only meaningful for legacy windows with no duration field.
        // A present but malformed or unfamiliar duration is not an omitted field.
        if shortIndex == nil,
           let primary = windows[0],
           longIndex != 0,
           // Absence of a DURATION, not of a key. `JSONSerialization` turns an explicit JSON
           // null into `NSNull`, so the key is present and a key test blocks the fallback for a
           // payload that carries no duration information at all — the exact case the fallback
           // exists for.
           periodSeconds(primary) == nil {
            shortIndex = 0
        }

        if longIndex == nil,
           let secondary = windows[1],
           shortIndex != 1,
           periodSeconds(secondary) == nil {
            longIndex = 1
        }

        return (shortIndex, longIndex)
    }

    private static func toWindow(
        _ window: [String: Any],
        id: String,
        labelPrefix: String?,
        limitReached: Bool,
        now: Date,
        group: String?
    ) -> UsageWindow {
        let period = periodSeconds(window)
        let category = WindowCategory.from(periodSeconds: period)

        // Spent families sometimes omit the percentage; unknown would understate usage.
        let usedPercent = JSONSupport.double(window, "used_percent", "usedPercent")
            ?? (limitReached ? 100.0 : nil)

        let absoluteReset = JSONSupport.date(
            JSONSupport.string(window, "reset_at", "resetAt")
        ) ?? JSONSupport.date(
            epoch: JSONSupport.int64(window, "reset_at", "resetAt")
        )

        let resetAt = absoluteReset ?? JSONSupport.int64(
            window, "reset_after_seconds", "resetAfterSeconds"
        ).map { now.addingTimeInterval(TimeInterval($0)) }

        return UsageWindow(
            id: id,
            label: label(category: category, prefix: labelPrefix),
            category: category,
            usedPercent: usedPercent,
            periodSeconds: period,
            resetAt: resetAt,
            exhausted: limitReached || (usedPercent.map { $0 >= 100.0 } ?? false),
            group: group
        )
    }

    private static func periodSeconds(_ window: [String: Any]?) -> Int64? {
        JSONSupport.int64(window, "limit_window_seconds", "limitWindowSeconds")
    }

    private static func label(category: WindowCategory, prefix: String?) -> String {
        let base: String
        switch category {
        case .fiveHour:
            base = "5h limit"
        case .weekly:
            base = "Weekly"
        case .monthly:
            base = "Monthly"
        case .other:
            base = "Limit"
        }

        return prefix.map { "\($0) · \(base)" } ?? base
    }

    private static func slug(_ value: String) -> String {
        let result = value.lowercased()
            .replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        return result.isEmpty ? "limit" : result
    }
}