import Foundation

public enum XaiBillingParser: Sendable {
    private static let creditsWindowID = "xai-credits"
    private static let monthlyWindowID = "xai-monthly"
    private static let onDemandWindowID = "xai-on-demand"

    // A nominal 30-day month keeps calendar months of different lengths categorised alike.
    private static let billingPeriodSeconds: Int64 = 2_592_000

    // xAI supplies absolute period ends, so neither parser needs the caller's clock.
    public static func parseCredits(_ payload: [String: Any], now _: Date) -> [UsageWindow] {
        let body = config(payload)

        // An absent or invalid percentage must not turn into an assumed-zero credit bar.
        guard let usedPercent = JSONSupport.double(
            body, "creditUsagePercent", "credit_usage_percent"
        ), usedPercent.isFinite else {
            return []
        }

        let period = JSONSupport.object(body, "currentPeriod", "current_period")
        let start = date(period, "start")
        let end = date(period, "end")
        let periodType = JSONSupport.string(period, "type")?.lowercased()

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

    public static func parseBilling(_ payload: [String: Any], now _: Date) -> [UsageWindow] {
        let body = config(payload)
        let monthlyLimit = cents(body, "monthlyLimit", "monthly_limit")
        let used = cents(body, "used")

        guard monthlyLimit != nil || used != nil else {
            return []
        }

        let usedCents = used ?? 0
        let resetAt = date(body, "billingPeriodEnd", "billing_period_end")

        // Excess spend belongs to the on-demand row, not the included allowance.
        // A missing allowance stays unknown rather than fabricating a zero limit.
        let includedPercent = monthlyLimit.flatMap {
            percentOf(amount: min(usedCents, $0), total: $0)
        }

        var windows = [
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
            // Older responses expose only total spend. Deriving excess requires a known
            // allowance; treating an absent allowance as zero invents on-demand usage.
            let onDemandUsed = cents(body, "onDemandUsed", "on_demand_used")
                ?? monthlyLimit.map { max(0, usedCents - $0) }

            if let onDemandUsed {
                let onDemandPercent = percentOf(amount: onDemandUsed, total: onDemandCap)
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