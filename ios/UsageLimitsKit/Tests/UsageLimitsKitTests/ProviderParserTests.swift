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

    func testFableLimitSupersedesTheCodenamedKey() {
        // `iguana_necktie` is upstream's current key for the Fable weekly window. When
        // `limits[]` describes Fable, it is the better source — and rendering both would show
        // the same quota twice.
        let windows = ClaudeUsageParser.parse(payload("""
        {
          "iguana_necktie": { "utilization": 5.0, "resets_at": "2026-09-14T00:00:00Z" },
          "limits": [
            { "kind": "weekly_scoped", "percent": 12.0, "is_active": true,
              "scope": { "model": { "display_name": "Fable" } } }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].usedPercent, 12.0)
    }

    func testCodenamedKeySurvivesWhenLimitsHasNoFableEntry() {
        let windows = ClaudeUsageParser.parse(payload("""
        {
          "iguana_necktie": { "utilization": 5.0, "resets_at": "2026-09-14T00:00:00Z" },
          "limits": [
            { "kind": "weekly_scoped", "percent": 12.0,
              "scope": { "model": { "display_name": "Sonnet" } } }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].usedPercent, 5.0)
    }

    func testUnknownTopLevelKeysAreIgnored() {
        let windows = ClaudeUsageParser.parse(payload("""
        {
          "five_hour": { "utilization": 1.0 },
          "some_future_window": { "utilization": 99.0 }
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
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
