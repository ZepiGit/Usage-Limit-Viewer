import XCTest
@testable import UsageLimitsKit

/// The Codex fixtures, ported from the Android suite.
///
/// These are not fresh tests — they are the exact payload shapes that caught real defects on
/// the Android side, and they are ported deliberately. A port is most likely to fail in the
/// same places the original did, and the original failed here: windows read by payload
/// position instead of declared duration, and `additional_rate_limits` entries whose windows
/// nest one level deeper than the top-level limits.
///
/// Every value is synthetic.
final class CodexUsageParserTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func payload(_ json: String) -> [String: Any] {
        let data = json.data(using: .utf8)!
        return (try! JSONSerialization.jsonObject(with: data)) as! [String: Any]
    }

    // MARK: - Classification by duration, not position

    func testSecondaryMonthlyWindowClassifiesAsMonthly() {
        // The defect this pins: team plans put a MONTHLY window in the secondary slot. Reading
        // by position labels a month as a week — a wrong number that looks entirely plausible.
        let windows = CodexUsageParser.parse(payload("""
        {
          "rate_limit": {
            "primary_window":   { "used_percent": 30.0, "limit_window_seconds": 18000 },
            "secondary_window": { "used_percent": 60.0, "limit_window_seconds": 2592000 }
          }
        }
        """), now: now)

        XCTAssertEqual(windows.count, 2)
        XCTAssertEqual(windows.first { $0.category == .fiveHour }?.usedPercent, 30.0)
        XCTAssertEqual(windows.first { $0.category == .monthly }?.usedPercent, 60.0)
        XCTAssertNil(windows.first { $0.category == .weekly })
    }

    func testSecondaryWeeklyWindowClassifiesAsWeekly() {
        let windows = CodexUsageParser.parse(payload("""
        {
          "rate_limit": {
            "primary_window":   { "used_percent": 10.0, "limit_window_seconds": 18000 },
            "secondary_window": { "used_percent": 20.0, "limit_window_seconds": 604800 }
          }
        }
        """), now: now)

        XCTAssertEqual(windows.first { $0.category == .weekly }?.usedPercent, 20.0)
    }

    func testWindowsInReversedSlotsStillClassifyByDuration() {
        // The five-hour window arriving in the SECONDARY slot must still read as five-hour.
        let windows = CodexUsageParser.parse(payload("""
        {
          "rate_limit": {
            "primary_window":   { "used_percent": 70.0, "limit_window_seconds": 604800 },
            "secondary_window": { "used_percent": 15.0, "limit_window_seconds": 18000 }
          }
        }
        """), now: now)

        XCTAssertEqual(windows.first { $0.category == .fiveHour }?.usedPercent, 15.0)
        XCTAssertEqual(windows.first { $0.category == .weekly }?.usedPercent, 70.0)
    }

    // MARK: - additional_rate_limits nesting

    func testAdditionalRateLimitWindowsNestedUnderRateLimit() {
        // Reading these off the entry itself finds nothing, which drops every extra metered
        // feature silently — and because account severity is a max over the windows that DID
        // parse, an account whose only spent limit is an additional one reads green.
        let windows = CodexUsageParser.parse(payload("""
        {
          "additional_rate_limits": [
            {
              "metered_feature": "sora",
              "rate_limit": {
                "limit_reached": false,
                "primary_window":   { "used_percent": 30.0, "limit_window_seconds": 18000 },
                "secondary_window": { "used_percent": 60.0, "limit_window_seconds": 604800 }
              }
            }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.count, 2)
        XCTAssertTrue(windows.allSatisfy { $0.group == "sora" })
        XCTAssertEqual(windows.first { $0.category == .fiveHour }?.usedPercent, 30.0)
    }

    func testExhaustedAdditionalLimitIsNotHiddenFromSeverity() {
        let windows = CodexUsageParser.parse(payload("""
        {
          "additional_rate_limits": [
            {
              "limit_name": "deep research",
              "rate_limit": {
                "limit_reached": true,
                "primary_window": { "limit_window_seconds": 18000 }
              }
            }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
        XCTAssertTrue(windows[0].exhausted)
        XCTAssertEqual(windows[0].usedPercent, 100.0)
        XCTAssertEqual(windows[0].severity, .exhausted)
    }

    func testFlatAdditionalRateLimitEntryStillParses() {
        let windows = CodexUsageParser.parse(payload("""
        {
          "additional_rate_limits": [
            {
              "limit_name": "legacy",
              "primary_window": { "used_percent": 10.0, "limit_window_seconds": 18000 }
            }
          ]
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].usedPercent, 10.0)
    }

    // MARK: - Exhaustion, resets, spellings

    func testLimitReachedForcesFullConsumption() {
        // A spent family reports no percentage; treating that as unknown would show an empty
        // bar for a limit the user has actually hit.
        let windows = CodexUsageParser.parse(payload("""
        {
          "rate_limit": {
            "limit_reached": true,
            "primary_window": { "limit_window_seconds": 18000 }
          }
        }
        """), now: now)

        XCTAssertEqual(windows[0].usedPercent, 100.0)
        XCTAssertTrue(windows[0].exhausted)
    }

    func testAllowedFalseAlsoMeansExhausted() {
        let windows = CodexUsageParser.parse(payload("""
        {
          "rate_limit": {
            "allowed": false,
            "primary_window": { "limit_window_seconds": 18000 }
          }
        }
        """), now: now)

        XCTAssertTrue(windows[0].exhausted)
    }

    func testResetAfterSecondsBecomesAnAbsoluteInstant() throws {
        let windows = CodexUsageParser.parse(payload("""
        {
          "rate_limit": {
            "primary_window": {
              "used_percent": 40.0,
              "limit_window_seconds": 18000,
              "reset_after_seconds": 3600
            }
          }
        }
        """), now: now)

        let resetAt = try XCTUnwrap(windows[0].resetAt)
        XCTAssertEqual(resetAt.timeIntervalSince1970,
                       now.addingTimeInterval(3600).timeIntervalSince1970,
                       accuracy: 1.0)
    }

    func testAbsoluteResetAtWinsOverRelativeOffset() {
        let windows = CodexUsageParser.parse(payload("""
        {
          "rate_limit": {
            "primary_window": {
              "used_percent": 40.0,
              "limit_window_seconds": 18000,
              "reset_at": "2026-09-14T00:00:00Z",
              "reset_after_seconds": 60
            }
          }
        }
        """), now: now)

        XCTAssertEqual(windows[0].resetAt, Date(timeIntervalSince1970: 1_789_344_000))
    }

    func testCamelCaseSpellingsParseIdentically() {
        // Upstream serves both spellings depending on the endpoint.
        let windows = CodexUsageParser.parse(payload("""
        {
          "rateLimit": {
            "limitReached": false,
            "primaryWindow": { "usedPercent": 25.0, "limitWindowSeconds": 18000 }
          }
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].usedPercent, 25.0)
        XCTAssertEqual(windows[0].category, .fiveHour)
    }

    func testEmptyPayloadYieldsNoWindows() {
        XCTAssertTrue(CodexUsageParser.parse(payload("{}"), now: now).isEmpty)
    }

    func testUnknownFieldsAreIgnored() {
        let windows = CodexUsageParser.parse(payload("""
        {
          "some_new_field": { "nested": true },
          "rate_limit": {
            "primary_window": { "used_percent": 5.0, "limit_window_seconds": 18000 }
          }
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
    }

    // MARK: - Reset credits

    func testOnlyAvailableCodexCreditsAreReturned() {
        // Offering a spent credit, or one for an unrelated reset type, would put a button on
        // screen that cannot work.
        let credits = CodexUsageParser.parseResetCredits(payload("""
        {
          "available_count": 1,
          "credits": [
            { "id": "a", "reset_type": "codex_rate_limits", "status": "available",
              "granted_at": "2026-09-01T00:00:00Z", "expires_at": "2026-10-01T00:00:00Z" },
            { "id": "b", "reset_type": "codex_rate_limits", "status": "consumed",
              "expires_at": "2026-10-01T00:00:00Z" },
            { "id": "c", "reset_type": "something_else", "status": "available",
              "expires_at": "2026-10-01T00:00:00Z" }
          ]
        }
        """))

        XCTAssertEqual(credits.map(\.id), ["a"])
    }

    func testCreditWithNoResetTypeIsKept() {
        // An absent reset_type means "not filtered out", not "wrong type".
        let credits = CodexUsageParser.parseResetCredits(payload("""
        { "credits": [ { "id": "a", "status": "available" } ] }
        """))

        XCTAssertEqual(credits.map(\.id), ["a"])
    }

    func testAvailableCountIsReadFromThePayload() {
        // Authoritative over credits.count: the list can be truncated while the count is exact.
        let count = CodexUsageParser.availableCreditCount(payload("""
        { "available_count": 3, "credits": [] }
        """))

        XCTAssertEqual(count, 3)
    }

    func testEmbeddedResetCreditsAreReadFromTheUsagePayload() {
        let credits = CodexUsageParser.parseEmbeddedResetCredits(payload("""
        {
          "rate_limit_reset_credits": {
            "available_count": 1,
            "credits": [ { "id": "z", "reset_type": "codex_rate_limits", "status": "available" } ]
          }
        }
        """))

        XCTAssertEqual(credits.map(\.id), ["z"])
    }

    func testPlanTypeIsRead() {
        XCTAssertEqual(CodexUsageParser.parsePlan(payload(#"{"plan_type": "plus"}"#)), "plus")
    }

    // MARK: - When position may stand in for a duration

    func testAStatedButUnfamiliarDurationIsNotForcedIntoTheSessionSlot() {
        // A daily limit is neither five-hourly nor weekly nor monthly. Reading it by POSITION
        // labels it the session limit and reports a day's quota as five hours' — the same class
        // of error as reading a week as five hours, which a live payload caught once already.
        let windows = CodexUsageParser.parse(payload(#"""
        {"rate_limit": {"primary_window": {"limit_window_seconds": 86400, "used_percent": 40}}}
        """#), now: now)

        XCTAssertTrue(
            windows.allSatisfy { $0.id != "codex-short" },
            "a stated duration must not be overridden by position")
    }

    func testALegacyWindowWithNoDurationStillFallsBackToPosition() {
        // The case the fallback exists for, and it must keep working.
        let windows = CodexUsageParser.parse(payload(#"""
        {"rate_limit": {"primary_window": {"used_percent": 40}}}
        """#), now: now)

        XCTAssertEqual(windows.first?.id, "codex-short")
    }

    func testAnExplicitNullDurationCountsAsNoDuration() {
        // `JSONSerialization` turns an explicit JSON null into `NSNull`, so a key test sees the
        // key as PRESENT and blocks the fallback — for a payload that carries no duration
        // information at all, which is exactly what the fallback is for.
        let windows = CodexUsageParser.parse(payload(#"""
        {"rate_limit": {"primary_window": {"limit_window_seconds": null, "used_percent": 40}}}
        """#), now: now)

        XCTAssertEqual(windows.first?.id, "codex-short")
    }
}
