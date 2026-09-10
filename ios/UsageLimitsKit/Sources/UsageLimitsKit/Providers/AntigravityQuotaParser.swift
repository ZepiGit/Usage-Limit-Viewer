import Foundation

/// Preserves the server's shared-bucket grouping instead of duplicating limits per model.
public enum AntigravityQuotaParser: Sendable {
    // The shared parser contract includes `now`, but this provider reports absolute reset times.
    public static func parse(_ payload: [String: Any], now _: Date) -> [UsageWindow] {
        var windows: [UsageWindow] = []

        for (groupIndex, element) in JSONSupport.array(payload, "groups").enumerated() {
            guard let group = element as? [String: Any] else { continue }
            windows.append(contentsOf: windowsFor(group, groupIndex: groupIndex))
        }

        return windows
    }

    private static func windowsFor(
        _ group: [String: Any],
        groupIndex: Int
    ) -> [UsageWindow] {
        let groupName = JSONSupport.string(group, "display_name", "displayName")
        // Preserve source indices so malformed siblings do not change fallback IDs.
        let groupSlug = slug(groupName ?? "group-\(groupIndex)")

        let windows = JSONSupport.array(group, "buckets").enumerated().compactMap {
            bucketIndex, element -> UsageWindow? in
            guard let bucket = element as? [String: Any] else { return nil }
            return toWindow(
                bucket,
                groupName: groupName,
                groupSlug: groupSlug,
                bucketIndex: bucketIndex
            )
        }

        // Stable sorting preserves provider order for the known windows and equal labels.
        return windows.sorted { lhs, rhs in
            let leftRank = rank(lhs.category)
            let rightRank = rank(rhs.category)

            if leftRank != rightRank {
                return leftRank < rightRank
            }
            if leftRank == 2 {
                return lhs.label.lowercased() < rhs.label.lowercased()
            }
            return false
        }
    }

    private static func toWindow(
        _ bucket: [String: Any],
        groupName: String?,
        groupSlug: String,
        bucketIndex: Int
    ) -> UsageWindow? {
        // A missing fraction makes only this bucket unusable, not the entire group.
        guard let remainingFraction = JSONSupport.double(
            bucket, "remaining_fraction", "remainingFraction"
        ) else {
            return nil
        }

        let clamped = min(max(remainingFraction, 0.0), 1.0)
        let window = JSONSupport.string(bucket, "window")
        let (category, periodSeconds) = classify(window)
        let id = JSONSupport.string(bucket, "bucket_id", "bucketId")
            ?? "\(groupSlug)-\(window.map { slug($0) } ?? String(bucketIndex))"

        return UsageWindow(
            id: id,
            label: JSONSupport.string(bucket, "display_name", "displayName") ?? id,
            category: category,
            // The provider reports the fraction remaining; the model stores percent consumed.
            usedPercent: (1.0 - clamped) * 100.0,
            periodSeconds: periodSeconds,
            resetAt: JSONSupport.date(
                JSONSupport.string(bucket, "reset_time", "resetTime")
            ),
            exhausted: clamped <= 0.0,
            group: groupName
        )
    }

    private static func classify(_ window: String?) -> (WindowCategory, Int64?) {
        // These are free-form provider names; unknown windows remain visible, unclassified.
        switch window?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "5h", "five-hour", "five_hour":
            return (.fiveHour, 18_000)
        case "weekly", "week":
            return (.weekly, 604_800)
        default:
            return (.other, nil)
        }
    }

    private static func rank(_ category: WindowCategory) -> Int {
        switch category {
        case .fiveHour:
            return 0
        case .weekly:
            return 1
        default:
            return 2
        }
    }

    private static func slug(_ value: String) -> String {
        let result = value.lowercased()
            .replacingOccurrences(
                of: "[^a-z0-9]+",
                with: "-",
                options: .regularExpression
            )
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))

        return result.isEmpty ? "quota" : result
    }
}