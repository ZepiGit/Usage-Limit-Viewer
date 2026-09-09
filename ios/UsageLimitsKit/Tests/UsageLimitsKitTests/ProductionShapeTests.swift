import XCTest
@testable import UsageLimitsKit

/// Pins real production payload shapes, rather than invented ones, to prevent
/// regressions in numeric bridging, null handling and window categorisation.
final class ProductionShapeTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func payload(_ json: String) -> [String: Any] {
        (try! JSONSerialization.jsonObject(with: json.data(using: .utf8)!)) as! [String: Any]
    }

    private func percent(_ windows: [UsageWindow], _ id: String) throws -> Double {
        try XCTUnwrap(XCTUnwrap(windows.first { $0.id == id }).usedPercent)
    }

    // Pins production primary-slot weekly limits and numeric resets taking precedence over offsets.
    func testCodexWeeklyPrimaryWindowWithEpochResetAndNullSecondary() throws {
        let input = payload("""
        { "plan_type": "plus",
          "rate_limit": { "allowed": false, "limit_reached": true,
            "primary_window": { "used_percent": 100, "limit_window_seconds": 604800,
                                "reset_after_seconds": 469200, "reset_at": 1789457449 },
            "secondary_window": null },
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "rate_limit_reset_credits": { "available_count": 0, "applicable_available_count": 0 } }
        """)

        let windows = CodexUsageParser.parse(input, now: now)

        XCTAssertEqual(windows.count, 1, "Null secondary and additional limits must not create windows.")
        let window = try XCTUnwrap(windows.first)
        XCTAssertEqual(window.category, .weekly)
        XCTAssertEqual(window.periodSeconds, 604800)
        XCTAssertEqual(try percent(windows, window.id), 100, accuracy: 0.001)
        XCTAssertTrue(window.exhausted)
        XCTAssertEqual(window.resetAt, Date(timeIntervalSince1970: 1789457449))
        XCTAssertEqual(CodexUsageParser.parsePlan(input), "plus")
        // The counts sit under `rate_limit_reset_credits`, and production sends no `credits`
        // array at all — a UI gated on the row list would report "none" to a holder.
        let credits = try XCTUnwrap(input["rate_limit_reset_credits"] as? [String: Any])
        XCTAssertEqual(CodexUsageParser.availableCreditCount(credits), 0)
        XCTAssertEqual(CodexUsageParser.applicableCreditCount(credits), 0)
        XCTAssertTrue(CodexUsageParser.parseEmbeddedResetCredits(input).isEmpty)
    }

    // Pins production integer limits-array percents alongside flat windows, nulls and a bare Boolean.
    func testClaudeFlatWindowsAndIntegerScopedLimits() throws {
        let input = payload("""
        { "five_hour": { "utilization": 21.0, "resets_at": "2026-09-10T01:29:59.073269+00:00",
                         "limit_dollars": null, "locked_reason": null },
          "seven_day": { "utilization": 3.0, "resets_at": "2026-09-16T19:59:59.073289+00:00",
                         "limit_dollars": null, "locked_reason": null },
          "seven_day_opus": null,
          "iguana_necktie": null,
          "member_dashboard_available": false,
          "limits": [
            {"kind":"session","group":"session","percent":21,"severity":"normal",
             "resets_at":"2026-09-10T01:29:59.073269+00:00","scope":null,"is_active":true},
            {"kind":"weekly_all","group":"weekly","percent":3,"severity":"normal",
             "resets_at":"2026-09-16T19:59:59.073289+00:00","scope":null,"is_active":false},
            {"kind":"weekly_scoped","group":"weekly","percent":0,"severity":"normal",
             "resets_at":"2026-09-16T20:00:00+00:00",
             "scope":{"model":{"id":null,"display_name":"Fable"}},"is_active":false} ] }
        """)

        let windows = ClaudeUsageParser.parse(input, now: now)

        XCTAssertEqual(windows.count, 3, "Nulls and metadata must not create windows or duplicate flat limits.")

        let fiveHour = try XCTUnwrap(windows.first { $0.category == .fiveHour })
        XCTAssertEqual(fiveHour.category, .fiveHour)
        XCTAssertEqual(try percent(windows, fiveHour.id), 21, accuracy: 0.001)

        let sevenDay = try XCTUnwrap(windows.first {
            $0.category == .weekly && $0.id != "seven-day-fable"
        })
        XCTAssertEqual(sevenDay.category, .weekly)
        XCTAssertEqual(try percent(windows, sevenDay.id), 3, accuracy: 0.001)

        XCTAssertEqual(try percent(windows, "seven-day-fable"), 0, accuracy: 0.001)
        XCTAssertFalse(windows.contains {
            $0.id == "seven-day-opus" || $0.id == "seven_day_opus"
        })
        XCTAssertFalse(windows.contains {
            $0.id == "iguana-necktie" || $0.id == "iguana_necktie"
        })
        XCTAssertFalse(windows.contains {
            $0.id == "member-dashboard-available" || $0.id == "member_dashboard_available"
        })
    }

    // Pins production remaining-fraction inversion, integer one bridging and groups without ideType.
    func testAntigravityGroupedRemainingFractionsIncludingIntegerOne() throws {
        let input = payload("""
        { "groups": [
           { "displayName": "Gemini Models",
             "buckets": [
               { "bucketId": "gemini-weekly", "displayName": "Weekly Limit Remaining",
                 "window": "weekly", "resetTime": "2026-09-10T18:54:44Z",
                 "remainingFraction": 0.5309938 },
               { "bucketId": "gemini-5h", "displayName": "Five Hour Limit Remaining",
                 "window": "5h", "resetTime": "2026-09-10T01:26:47Z",
                 "remainingFraction": 0.9803985 } ] },
           { "displayName": "Claude and GPT models",
             "buckets": [
               { "bucketId": "3p-weekly", "window": "weekly",
                 "resetTime": "2026-09-16T21:14:41Z", "remainingFraction": 1 },
               { "bucketId": "3p-5h", "window": "5h",
                 "resetTime": "2026-09-10T02:14:41Z", "remainingFraction": 1 } ] } ] }
        """)

        let windows = AntigravityQuotaParser.parse(input, now: now)

        XCTAssertEqual(windows.count, 4)
        XCTAssertEqual(
            Set(windows.map(\.id)),
            Set(["gemini-weekly", "gemini-5h", "3p-weekly", "3p-5h"])
        )
        XCTAssertEqual(try percent(windows, "gemini-weekly"), 46.9, accuracy: 0.01)
        XCTAssertEqual(try percent(windows, "gemini-5h"), 1.96, accuracy: 0.01)
        XCTAssertEqual(try percent(windows, "3p-weekly"), 0, accuracy: 0.001)
        XCTAssertEqual(try percent(windows, "3p-5h"), 0, accuracy: 0.001)

        let geminiWeekly = try XCTUnwrap(windows.first { $0.id == "gemini-weekly" })
        let geminiFiveHour = try XCTUnwrap(windows.first { $0.id == "gemini-5h" })
        let thirdPartyWeekly = try XCTUnwrap(windows.first { $0.id == "3p-weekly" })
        let thirdPartyFiveHour = try XCTUnwrap(windows.first { $0.id == "3p-5h" })

        XCTAssertNotNil(geminiWeekly.group)
        XCTAssertNotNil(geminiFiveHour.group)
        XCTAssertNotNil(thirdPartyWeekly.group)
        XCTAssertNotNil(thirdPartyFiveHour.group)
        XCTAssertNotEqual(geminiWeekly.group, thirdPartyWeekly.group)
        XCTAssertEqual(geminiWeekly.group, geminiFiveHour.group)
        XCTAssertEqual(thirdPartyWeekly.group, thirdPartyFiveHour.group)
        XCTAssertFalse(windows.contains { $0.exhausted })
    }

    // Pins the production protobuf period spelling and wrapped zero caps in the credits endpoint.
    func testXaiCreditsWithEnumWeeklyPeriod() throws {
        let input = payload("""
        { "config": {
            "currentPeriod": { "type": "USAGE_PERIOD_TYPE_WEEKLY",
                               "start": "2026-09-03T18:32:26.743829+00:00",
                               "end": "2026-09-10T18:32:26.743829+00:00" },
            "creditUsagePercent": 64.0,
            "onDemandCap": { "val": 0 }, "onDemandUsed": { "val": 0 },
            "isUnifiedBillingUser": true, "prepaidBalance": { "val": 659 },
            "billingPeriodStart": "2026-09-03T18:32:26.743829+00:00",
            "billingPeriodEnd": "2026-09-10T18:32:26.743829+00:00" } }
        """)

        let windows = XaiBillingParser.parseCredits(input, now: now)

        XCTAssertEqual(windows.count, 1)
        let window = try XCTUnwrap(windows.first)
        XCTAssertEqual(try percent(windows, window.id), 64, accuracy: 0.001)
        XCTAssertEqual(window.category, .weekly)
        XCTAssertFalse(windows.contains { $0.id == "xai-on-demand" })
    }

    // Pins enum-only categorisation so a current-period span cannot mask an unrecognised production type.
    func testXaiCreditsWithEnumWeeklyPeriodWithoutStartOrEnd() throws {
        let input = payload("""
        { "config": {
            "currentPeriod": { "type": "USAGE_PERIOD_TYPE_WEEKLY" },
            "creditUsagePercent": 64.0,
            "onDemandCap": { "val": 0 }, "onDemandUsed": { "val": 0 },
            "isUnifiedBillingUser": true, "prepaidBalance": { "val": 659 },
            "billingPeriodStart": "2026-09-03T18:32:26.743829+00:00",
            "billingPeriodEnd": "2026-09-10T18:32:26.743829+00:00" } }
        """)

        let windows = XaiBillingParser.parseCredits(input, now: now)

        XCTAssertEqual(windows.count, 1)
        let window = try XCTUnwrap(windows.first)
        XCTAssertEqual(try percent(windows, window.id), 64, accuracy: 0.001)
        XCTAssertEqual(window.category, .weekly)
        XCTAssertFalse(windows.contains { $0.id == "xai-on-demand" })
    }

    // Pins production wrapped-cent arithmetic and ignores billing history without inventing a zero-cap window.
    func testXaiBillingWithWrappedCentsAndUnknownHistory() throws {
        let input = payload("""
        { "config": { "monthlyLimit": { "val": 1000 }, "used": { "val": 341 },
            "onDemandCap": { "val": 0 },
            "billingPeriodStart": "2026-09-01T00:00:00+00:00",
            "billingPeriodEnd": "2026-10-01T00:00:00+00:00",
            "history": [ { "billingCycle": { "year": 2026, "month": 8 },
                           "includedUsed": { "val": 0 }, "totalUsed": { "val": 0 } } ] } }
        """)

        let windows = XaiBillingParser.parseBilling(input, now: now)

        XCTAssertEqual(windows.count, 1, "Unknown history must not produce usage windows.")
        XCTAssertEqual(try percent(windows, "xai-monthly"), 34.1, accuracy: 0.001)
        XCTAssertFalse(windows.contains { $0.id == "xai-on-demand" })
    }
}