import XCTest
@testable import UsageLimitsKit

/// Claude and Antigravity fixtures, ported from the Android suite.
///
/// Both carry a defect the Android side actually shipped: Claude emitting a row for a window
/// with no `utilization` (which reddened a whole account), and the direction of Antigravity's
/// `remainingFraction`, which is REMAINING where `usedPercent` is CONSUMED.
///
/// Every value is synthetic.
final class ClaudeUsageParserTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func payload(_ json: String) -> [String: Any] {
        (try! JSONSerialization.jsonObject(with: json.data(using: .utf8)!)) as! [String: Any]
    }

    func testFiveHourAndWeeklyKeysClassifyByKeyNotPayload() {
        // Anthropic never states the period, so it is derived from the key: only `five_hour`
        // is a rolling window, everything else it exposes is seven days.
        let windows = ClaudeUsageParser.parse(payload("""
        {
          "five_hour": { "utilization": 42.5, "resets_at": "2026-09-09T17:30:00Z" },
          "seven_day": { "utilization": 47.0, "resets_at": "2026-09-14T00:00:00Z" }
        }
        """), now: now)

        XCTAssertEqual(windows.count, 2)
        let fiveHour = windows.first { $0.category == .fiveHour }
        XCTAssertEqual(fiveHour?.usedPercent, 42.5)
        XCTAssertEqual(fiveHour?.periodSeconds, 18_000)
        let weekly = windows.first { $0.category == .weekly }
        XCTAssertEqual(weekly?.periodSeconds, 604_800)
    }

    func testUtilizationIsConsumedNotRemaining() {
        // The direction that matters: 47 consumed is 53 remaining, not the other way round.
        let windows = ClaudeUsageParser.parse(payload("""
        { "seven_day": { "utilization": 47.0, "resets_at": null } }
        """), now: now)

        XCTAssertEqual(windows[0].usedPercent, 47.0)
        XCTAssertEqual(windows[0].remainingPercent, 53.0)
    }

    func testWindowWithNoUtilizationIsSkippedEntirely() {
        // A nil usedPercent resolves to .error, and snapshot severity is a MAX over the
        // windows — so emitting this row would mark the whole account failed over one
        // renamed upstream field, while that same row sorts last in any "most critical" order.
        let windows = ClaudeUsageParser.parse(payload("""
        {
          "five_hour": { "utilization": 10.0, "resets_at": null },
          "seven_day": { "resets_at": "2026-09-14T00:00:00Z" }
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].category, .fiveHour)
        XCTAssertTrue(windows.allSatisfy { $0.usedPercent != nil })
        XCTAssertFalse(windows.contains { $0.severity == .error })
    }

    func testNullResetsAtYieldsNoDate() {
        let windows = ClaudeUsageParser.parse(payload("""
        { "five_hour": { "utilization": 10.0, "resets_at": null } }
        """), now: now)

        XCTAssertNil(windows[0].resetAt)
    }

    func testLimitsSupersedeTheFlatKeysEntirely() {
        // `limits` is Anthropic's own presentation model and, on a live account, the ONLY
        // place the Fable figure appears — the `iguana_necktie` key it belongs under was null.
        // Reading both lists would render every quota twice.
        let windows = ClaudeUsageParser.parse(payload("""
        {
          "five_hour": { "utilization": 21.0, "resets_at": "2026-09-10T01:29:59Z" },
          "seven_day": { "utilization": 3.0,  "resets_at": "2026-09-16T19:59:59Z" },
          "iguana_necktie": null,
          "limits": [
            { "kind": "session",       "group": "session", "percent": 21 },
            { "kind": "weekly_all",    "group": "weekly",  "percent": 3 },
            { "kind": "weekly_scoped", "group": "weekly",  "percent": 0,
              "scope": { "model": { "display_name": "Fable" } } }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.map(\.id), ["five-hour", "seven-day", "seven-day-fable"])
        XCTAssertEqual(windows.map(\.label), ["5h limit", "Weekly", "Weekly (Fable)"])
        // Production sends integer percents, not decimals.
        XCTAssertEqual(windows[0].usedPercent, 21.0)
        XCTAssertEqual(windows[0].periodSeconds, 18_000)
        XCTAssertEqual(windows[1].periodSeconds, 604_800)
    }

    func testAnUnrecognisedLimitKindIsKeptWithTheDurationItsGroupImplies() {
        // A new `kind` must cost a label, not a whole quota.
        let windows = ClaudeUsageParser.parse(payload("""
        { "limits": [
            { "kind": "weekly_cowork",   "group": "weekly", "percent": 44 },
            { "kind": "brand_new_thing", "group": "something_else", "percent": 12 } ] }
        """), now: now)

        let cowork = windows.first { $0.id == "weekly-cowork" }
        XCTAssertEqual(cowork?.periodSeconds, 604_800)
        XCTAssertEqual(cowork?.label, "Weekly Cowork")
        // No group we know: `.other` states the duration is unknown rather than guessing.
        let unknown = windows.first { $0.id == "brand-new-thing" }
        XCTAssertNil(unknown?.periodSeconds ?? nil)
        XCTAssertEqual(unknown?.category, .other)
    }

    func testTwoScopedLimitsForDifferentModelsDoNotCollide() {
        let windows = ClaudeUsageParser.parse(payload("""
        { "limits": [
            { "kind": "weekly_scoped", "group": "weekly", "percent": 10,
              "scope": { "model": { "display_name": "Fable" } } },
            { "kind": "weekly_scoped", "group": "weekly", "percent": 20,
              "scope": { "model": { "display_name": "Opus" } } } ] }
        """), now: now)

        XCTAssertEqual(windows.map(\.id), ["seven-day-fable", "seven-day-opus"])
        XCTAssertEqual(windows.map(\.label), ["Weekly (Fable)", "Weekly (Opus)"])
    }

    func testAnEmptyLimitsArrayFallsBackToTheFlatKeys() {
        // An array that yields nothing is no better than an absent one, and silently rendering
        // an empty screen would be the worst of the three outcomes.
        let windows = ClaudeUsageParser.parse(payload("""
        {
          "iguana_necktie": { "utilization": 5.0, "resets_at": "2026-09-14T00:00:00Z" },
          "limits": [ { "kind": "weekly_scoped", "percent": "unavailable",
                        "scope": { "model": { "display_name": "Fable" } } } ]
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].id, "iguana-necktie")
        XCTAssertEqual(windows[0].usedPercent, 5.0)
    }

    func testAnUndocumentedKeyWithRealConsumptionIsSurfaced() {
        // Anthropic ships new windows under rotating codenames; a fixed key list loses the
        // quota the moment one appears, and the number on screen goes quietly wrong.
        let windows = ClaudeUsageParser.parse(payload("""
        {
          "five_hour": { "utilization": 3.0, "resets_at": "2026-09-09T17:30:00Z" },
          "thirty_day_quokka": { "utilization": 99.0, "resets_at": "2026-10-01T00:00:00Z" }
        }
        """), now: now)

        XCTAssertEqual(Set(windows.map(\.id)), ["five-hour", "thirty-day-quokka"])
        let found = windows.first { $0.id == "thirty-day-quokka" }
        XCTAssertEqual(found?.label, "Thirty Day Quokka")
        XCTAssertEqual(found?.usedPercent, 99.0)
        XCTAssertNil(found?.periodSeconds ?? nil)
        XCTAssertEqual(found?.category, .other)
    }

    func testAnUntouchedCodenameSlotIsNotSurfaced() {
        // `nimbus_quill` sits at 0 % on a live account. A screen meant to be read in three
        // seconds does not need a row for a placeholder nobody is consuming.
        let windows = ClaudeUsageParser.parse(payload("""
        {
          "five_hour": { "utilization": 3.0, "resets_at": "2026-09-09T17:30:00Z" },
          "nimbus_quill": { "utilization": 0.0, "resets_at": null, "locked_reason": null }
        }
        """), now: now)

        XCTAssertEqual(windows.map(\.id), ["five-hour"])
    }

    func testACreditBalanceIsNotMistakenForAUsageWindow() {
        // `extra_usage` carries a `utilization` too, but it is a credit balance, not a rate
        // limit. Account severity is a MAX over the windows, so folding it in would grade an
        // account by money spent rather than quota consumed. It declares no `resets_at`, and
        // that is the discriminator.
        let windows = ClaudeUsageParser.parse(payload("""
        {
          "five_hour": { "utilization": 3.0, "resets_at": "2026-09-09T17:30:00Z" },
          "extra_usage": { "is_enabled": false, "monthly_limit": null, "utilization": 87.0 }
        }
        """), now: now)

        XCTAssertEqual(windows.map(\.id), ["five-hour"])
    }

    func testAScalarOrNonWindowObjectIsNeverPromoted() {
        let windows = ClaudeUsageParser.parse(payload("""
        {
          "five_hour": { "utilization": 1.0, "resets_at": null },
          "organization": { "uuid": "11111111-2222-3333-4444-555555555555" },
          "member_dashboard_available": false,
          "unexpected_scalar": 7
        }
        """), now: now)

        XCTAssertEqual(windows.map(\.id), ["five-hour"])
    }

    func testAnExplicitlyNullWindowKeyYieldsNoRow() {
        // Live payloads null out every window the plan does not grant. A nil usedPercent
        // resolves to .error and account severity is a MAX, so emitting these would mark a
        // perfectly healthy account as failed.
        let windows = ClaudeUsageParser.parse(payload("""
        {
          "five_hour": { "utilization": 42.5, "resets_at": "2026-09-09T17:30:00Z" },
          "seven_day": null,
          "seven_day_opus": null
        }
        """), now: now)

        XCTAssertEqual(windows.map(\.id), ["five-hour"])
        XCTAssertFalse(windows.contains { $0.severity == .error })
    }

    func testEmptyPayloadYieldsNoWindows() {
        XCTAssertTrue(ClaudeUsageParser.parse(payload("{}"), now: now).isEmpty)
    }

    func testExhaustionAtFullUtilization() {
        let windows = ClaudeUsageParser.parse(payload("""
        { "five_hour": { "utilization": 100.0 } }
        """), now: now)

        XCTAssertTrue(windows[0].exhausted)
        XCTAssertEqual(windows[0].severity, .exhausted)
    }

    func testPlanIsReadFromTheProfile() {
        XCTAssertEqual(
            ClaudeUsageParser.parsePlan(payload(#"{"account":{"has_claude_max":true}}"#)), "Max")
        XCTAssertEqual(
            ClaudeUsageParser.parsePlan(payload(#"{"account":{"has_claude_pro":true}}"#)), "Pro")
        XCTAssertNil(ClaudeUsageParser.parsePlan(payload(#"{"account":{}}"#)))
    }

    func testPlanLabelKeepsTheTierMultiplier() {
        // Captured live: the profile reports `default_claude_max_5x` while `has_claude_max` is
        // merely true. Max 5× versus Max 20× is a fivefold difference in the very number this
        // app exists to show.
        XCTAssertEqual(
            ClaudeUsageParser.parsePlan(payload(
                #"{"account":{"has_claude_max":true},"#
                + #""organization":{"rate_limit_tier":"default_claude_max_5x"}}"#)),
            "Max 5×")
    }

    func testAnUnrecognisedTierStillYieldsSomethingReadable() {
        // Anthropic adds tiers. A lookup table would render a new one as no plan at all, which
        // reads as "not subscribed" rather than "not recognised".
        XCTAssertEqual(
            ClaudeUsageParser.parsePlan(payload(
                #"{"organization":{"rate_limit_tier":"default_claude_team_premium_20x"}}"#)),
            "Team Premium 20×")
        XCTAssertEqual(
            ClaudeUsageParser.parsePlan(payload(
                #"{"organization":{"rate_limit_tier":"default_claude_pro"}}"#)),
            "Pro")
    }
}

final class AntigravityQuotaParserTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func payload(_ json: String) -> [String: Any] {
        (try! JSONSerialization.jsonObject(with: json.data(using: .utf8)!)) as! [String: Any]
    }

    func testRemainingFractionConvertsToConsumedPercent() {
        // The whole point of this parser: remainingFraction is 0...1 REMAINING while
        // usedPercent is 0...100 CONSUMED. Getting it backwards inverts every bar on screen.
        let windows = AntigravityQuotaParser.parse(payload("""
        {
          "groups": [
            { "displayName": "Gemini Pro",
              "buckets": [
                { "bucketId": "gemini-5h", "displayName": "5h limit", "window": "5h",
                  "remainingFraction": 0.53, "resetTime": "2026-09-09T17:30:00Z" }
              ] }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].usedPercent!, 47.0, accuracy: 0.001)
        XCTAssertEqual(windows[0].remainingPercent!, 53.0, accuracy: 0.001)
    }

    func testWindowSpellingsMapToCategories() {
        let windows = AntigravityQuotaParser.parse(payload("""
        {
          "groups": [
            { "displayName": "G",
              "buckets": [
                { "bucketId": "a", "window": "5h", "remainingFraction": 1.0 },
                { "bucketId": "b", "window": "weekly", "remainingFraction": 1.0 }
              ] }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.first { $0.id == "a" }?.category, .fiveHour)
        XCTAssertEqual(windows.first { $0.id == "a" }?.periodSeconds, 18_000)
        XCTAssertEqual(windows.first { $0.id == "b" }?.category, .weekly)
        XCTAssertEqual(windows.first { $0.id == "b" }?.periodSeconds, 604_800)
    }

    func testGroupNameIsCarriedOnEveryWindow() {
        // This is what lets the UI show one card per quota group and render a shared bucket
        // exactly once, instead of a row per model.
        let windows = AntigravityQuotaParser.parse(payload("""
        {
          "groups": [
            { "displayName": "Claude / GPT",
              "buckets": [ { "bucketId": "a", "window": "5h", "remainingFraction": 0.5 } ] }
          ]
        }
        """), now: now)

        XCTAssertTrue(windows.allSatisfy { $0.group == "Claude / GPT" })
    }

    func testSnakeCaseSpellingsParseIdentically() {
        let windows = AntigravityQuotaParser.parse(payload("""
        {
          "groups": [
            { "display_name": "G",
              "buckets": [
                { "bucket_id": "a", "display_name": "5h limit", "window": "five_hour",
                  "remaining_fraction": 0.25, "reset_time": "2026-09-09T17:30:00Z" }
              ] }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].usedPercent!, 75.0, accuracy: 0.001)
        XCTAssertEqual(windows[0].category, .fiveHour)
        XCTAssertNotNil(windows[0].resetAt)
    }

    func testBucketWithoutRemainingFractionIsSkipped() {
        let windows = AntigravityQuotaParser.parse(payload("""
        {
          "groups": [
            { "displayName": "G",
              "buckets": [
                { "bucketId": "a", "window": "5h" },
                { "bucketId": "b", "window": "weekly", "remainingFraction": 0.5 }
              ] }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.map(\.id), ["b"])
    }

    func testGroupWithNoUsableBucketsIsDropped() {
        let windows = AntigravityQuotaParser.parse(payload("""
        {
          "groups": [
            { "displayName": "Empty", "buckets": [ { "bucketId": "a", "window": "5h" } ] },
            { "displayName": "Real",
              "buckets": [ { "bucketId": "b", "window": "5h", "remainingFraction": 0.1 } ] }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.map(\.group), ["Real"])
    }

    func testOutOfRangeFractionsAreClamped() {
        let windows = AntigravityQuotaParser.parse(payload("""
        {
          "groups": [
            { "displayName": "G",
              "buckets": [
                { "bucketId": "over",  "window": "5h",     "remainingFraction": 1.4 },
                { "bucketId": "under", "window": "weekly", "remainingFraction": -0.2 }
              ] }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.first { $0.id == "over" }?.usedPercent, 0.0)
        XCTAssertEqual(windows.first { $0.id == "under" }?.usedPercent, 100.0)
        XCTAssertTrue(windows.first { $0.id == "under" }?.exhausted ?? false)
    }

    func testFiveHourSortsBeforeWeeklyWithinAGroup() {
        let windows = AntigravityQuotaParser.parse(payload("""
        {
          "groups": [
            { "displayName": "G",
              "buckets": [
                { "bucketId": "wk", "window": "weekly", "remainingFraction": 0.5 },
                { "bucketId": "5h", "window": "5h",     "remainingFraction": 0.5 }
              ] }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.map(\.id), ["5h", "wk"])
    }

    func testEmptyPayloadYieldsNoWindows() {
        XCTAssertTrue(AntigravityQuotaParser.parse(payload("{}"), now: now).isEmpty)
    }
}
