import Foundation

public enum XaiBillingParser: Sendable {
    private static let creditsWindowID = "xai-credits"
    private static let monthlyWindowID = "xai-monthly"
    private static let onDemandWindowID = "xai-on-demand"

    // A nominal 30-day month keeps calendar months of different lengths categorised alike.
    private static let billingPeriodSeconds: Int64 = 2_592_000

    // `now` decides whether the reported period is the CURRENT one, which is what makes an
    // absent percentage readable as zero — see below.
    public static func parseCredits(_ payload: [String: Any], now: Date) -> [UsageWindow] {
        let body = config(payload)

        // Two spellings of the period: nested `currentPeriod{type,start,end}` in the credit
        // view, and the flat `usagePeriodType/Start/End` trio the unified-billing view carries
        // beside its monthly figures.
        let period = JSONSupport.object(body, "currentPeriod", "current_period")
        let start = date(period, "start") ?? date(body, "usagePeriodStart", "usage_period_start")
        let end = date(period, "end") ?? date(body, "usagePeriodEnd", "usage_period_end")
        let periodType = (JSONSupport.string(period, "type")
            ?? JSONSupport.string(body, "usagePeriodType", "usage_period_type"))?.lowercased()

        // An ABSENT percentage is a zero when the period is live, and unknown otherwise.
        //
        // xAI's billing message is proto3, and `credit_usage_percent` is an implicit-presence
        // float there: a value of exactly zero is not written to the wire at all. So the one
        // week in which the user has spent nothing — the first week, the week after a reset —
        // arrives with the field missing, and reading that as "no credit window" is how the
        // weekly row vanished from the app the moment it read 100 % remaining. The provider's
        // own web client reads the omitted scalar as zero; so does this, but only when the
        // payload proves it describes the period that contains now. A period in the past, or
        // none at all, is a payload this parser does not understand, and that stays a missing
        // row rather than an invented bar.
        let reported = JSONSupport.double(body, "creditUsagePercent", "credit_usage_percent")
            .flatMap { $0.isFinite ? $0 : nil }
        let periodIsLive: Bool = {
            guard let start, let end else { return false }
            return start <= now && now <= end
        }()
        guard let usedPercent = reported ?? (periodIsLive ? 0.0 : nil) else {
            return []
        }

        var periodSeconds: Int64?
        if let start, let end {
            let span = end.timeIntervalSince(start)
            if span.isFinite, span > 0 {
                // Exact conversion avoids trapping on an out-of-range upstream timestamp.
                periodSeconds = Int64(exactly: span.rounded(.towardZero))
            }
        }

        // Measured length wins; the type preserves weekly/monthly classification when
        // timestamps are missing or unusable.
        if periodSeconds == nil {
            if periodType?.contains("week") == true {
                periodSeconds = 604_800
            } else if periodType?.contains("month") == true {
                periodSeconds = billingPeriodSeconds
            }
        }

        let category = WindowCategory.from(periodSeconds: periodSeconds)
        let label: String
        switch category {
        case .weekly:
            label = "Weekly credits"
        case .monthly:
            label = "Monthly credits"
        default:
            label = "Credits"
        }

        return [
            UsageWindow(
                id: creditsWindowID,
                label: label,
                category: category,
                usedPercent: usedPercent,
                periodSeconds: periodSeconds,
                resetAt: end,
                exhausted: isExhausted(usedPercent)
            )
        ]
    }

    public static func parseBilling(_ payload: [String: Any], now: Date) -> [UsageWindow] {
        let body = config(payload)

        // The unified-billing shape of this view carries the weekly figures too —
        // `creditUsagePercent` with the flat `usagePeriod*` trio — so the weekly row is read
        // from here as well. It costs nothing when both views answer (merge keeps the credit
        // view's copy) and keeps the row when the credit view alone stops answering.
        let weekly = parseCredits(payload, now: now)

        let monthlyLimit = cents(body, "monthlyLimit", "monthly_limit")
        // Every name the production shape carries for spend — see the Kotlin twin for why
        // reading `used` alone rendered a 90 %-spent account as untouched.
        let used = cents(body, "used")
            ?? cents(body, "includedUsed", "included_used")
            ?? cents(body, "totalUsed", "total_used")

        guard monthlyLimit != nil || used != nil else {
            return weekly
        }

        let resetAt = date(body, "billingPeriodEnd", "billing_period_end")

        // Excess spend belongs to the on-demand row. Absent spend stays UNKNOWN: a limit with
        // no spend figure is a window whose percentage the app does not know, not one it has
        // decided is untouched.
        let includedPercent = monthlyLimit.flatMap { limit in
            used.flatMap { percentOf(amount: min($0, limit), total: limit) }
        }

        var windows = weekly + [
            UsageWindow(
                id: monthlyWindowID,
                label: "Monthly included",
                category: .monthly,
                usedPercent: includedPercent,
                periodSeconds: billingPeriodSeconds,
                resetAt: resetAt,
                exhausted: isExhausted(includedPercent)
            )
        ]

        // Without a positive cap, there is no on-demand facility worth showing.
        let onDemandCap = cents(body, "onDemandCap", "on_demand_cap") ?? 0
        if onDemandCap > 0 {
            // Deriving overage needs BOTH a known allowance and known spend; with either
            // missing the row is shown with an unknown percentage rather than omitted.
            let onDemandUsed = cents(body, "onDemandUsed", "on_demand_used")
                ?? monthlyLimit.flatMap { limit in used.map { max(0, $0 - limit) } }
            let onDemandPercent = onDemandUsed.flatMap { percentOf(amount: $0, total: onDemandCap) }

            windows.append(
                UsageWindow(
                    id: onDemandWindowID,
                    label: "On-demand",
                    category: .monthly,
                    usedPercent: onDemandPercent,
                    periodSeconds: billingPeriodSeconds,
                    resetAt: resetAt,
                    exhausted: isExhausted(onDemandPercent)
                )
            )
        }

        return windows
    }

    public static func merge(
        _ credits: [UsageWindow],
        _ billing: [UsageWindow]
    ) -> [UsageWindow] {
        // First occurrence wins so directly reported credits retain precedence if IDs
        // ever collide. Independent endpoint failures need not discard the other view.
        var seen = Set<String>()
        return (credits + billing).filter { seen.insert($0.id).inserted }
    }

    private static func config(_ payload: [String: Any]) -> [String: Any] {
        // Both endpoints wrap their bodies. Flat legacy responses remain valid, but
        // reading only the root silently loses every row from successful HTTP responses.
        JSONSupport.object(payload, "config") ?? payload
    }

    private static func cents(
        _ source: [String: Any]?,
        _ name: String,
        _ alternate: String? = nil
    ) -> Double? {
        // Money may be bare or wrapped in {"val": ...}. Ratios use cents throughout,
        // so no currency conversion or display-unit assumption is necessary.
        let alternateName = alternate ?? name
        if let value = JSONSupport.double(source, name, alternateName) {
            return value
        }
        let wrapped = JSONSupport.object(source, name, alternateName)
        return JSONSupport.double(wrapped, "val")
    }

    private static func date(
        _ source: [String: Any]?,
        _ name: String,
        _ alternate: String? = nil
    ) -> Date? {
        let alternateName = alternate ?? name
        return JSONSupport.date(JSONSupport.string(source, name, alternateName))
            ?? JSONSupport.date(epoch: JSONSupport.int64(source, name, alternateName))
    }

    private static func percentOf(amount: Double, total: Double) -> Double? {
        // Guard the denominator and the result: even valid finite amounts can overflow
        // during percentage calculation, and non-finite bars have no useful meaning.
        guard total > 0 else {
            return nil
        }
        let percentage = amount / total * 100
        return percentage.isFinite ? percentage : nil
    }

    private static func isExhausted(_ usedPercent: Double?) -> Bool {
        guard let usedPercent else {
            return false
        }
        return usedPercent >= 100
    }
}