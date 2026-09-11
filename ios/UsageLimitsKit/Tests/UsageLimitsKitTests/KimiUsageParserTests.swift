import XCTest
@testable import UsageLimitsKit

/// Kimi Code's usage response, and the trap in it.
///
/// The weekly block reports `used`; the rate-limit blocks report `remaining`. Neither carries
/// the other. A parser that understands one of the two shows an untouched weekly bar on an
/// account that has spent its entire week — a defect other clients shipped before fixing it.
///
/// Every case here is mirrored in `KimiUsageParserTest.kt`.
final class KimiUsageParserTests: XCTestCase {

    private func payload(_ text: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] ?? [:]
    }

    func testTheWeeklyWindowIsReadFromUsedWhichIsTheOnlyFieldItHas() {
        let windows = KimiUsageParser.parse(
            payload(#"{ "usage": { "limit": 7168, "used": 7168, "resetTime": 1800000000 } }"#))

        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].label, "Weekly")
        XCTAssertEqual(windows[0].category, .weekly)
        // The whole point: 100 %, not nil, not zero.
        XCTAssertEqual(windows[0].usedPercent ?? -1, 100, accuracy: 0.001)
        XCTAssertTrue(windows[0].exhausted)
    }

    func testARateLimitIsReadFromRemainingWhichIsTheOnlyFieldItHas() {
        let windows = KimiUsageParser.parse(payload(#"""
        { "limits": [
            { "name": "5h limit",
              "detail": { "limit": 100, "remaining": 25, "resetTime": 1800000000 } }
        ] }
        """#))

        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].label, "5h limit")
        XCTAssertEqual(windows[0].usedPercent ?? -1, 75, accuracy: 0.001)
    }

    func testBothKindsArriveTogetherAndBothAreRead() {
        let windows = KimiUsageParser.parse(payload(#"""
        {
          "usage": { "limit": 1000, "used": 400 },
          "limits": [ { "name": "5h limit", "detail": { "limit": 50, "remaining": 10 } } ]
        }
        """#))

        XCTAssertEqual(windows.count, 2)
        XCTAssertEqual(windows[0].usedPercent ?? -1, 40, accuracy: 0.001)
        XCTAssertEqual(windows[1].usedPercent ?? -1, 80, accuracy: 0.001)
    }

    func testAReportedUsedWinsOverADerivedOne() {
        let windows = KimiUsageParser.parse(
            payload(#"{ "usage": { "limit": 100, "used": 90, "remaining": 50 } }"#))
        XCTAssertEqual(windows.first?.usedPercent ?? -1, 90, accuracy: 0.001)
    }

    func testAWindowWithNoLimitIsDroppedRatherThanShownAsFull() {
        // Dividing by a zero limit is infinity, and a bar drawn from it reads as an exhausted
        // account that is not exhausted.
        XCTAssertTrue(KimiUsageParser.parse(payload(#"{ "usage": { "limit": 0, "used": 5 } }"#)).isEmpty)
        XCTAssertTrue(KimiUsageParser.parse(payload(#"{ "usage": { "used": 5 } }"#)).isEmpty)
    }

    func testTheWindowDurationIsReadNotAssumedFromPosition() {
        let windows = KimiUsageParser.parse(payload(#"""
        { "limits": [
            { "name": "Monthly", "windowSeconds": 2592000,
              "detail": { "limit": 10, "remaining": 5 } }
        ] }
        """#))
        XCTAssertEqual(windows.first?.periodSeconds, 2_592_000)
        XCTAssertNotEqual(windows.first?.category, .fiveHour)
    }

    func testAnUnnamedWindowIsLabelledFromItsDuration() {
        let windows = KimiUsageParser.parse(
            payload(#"{ "limits": [ { "detail": { "limit": 10, "remaining": 5 } } ] }"#))
        XCTAssertEqual(windows.first?.label, "5h limit")
        XCTAssertEqual(windows.first?.category, .fiveHour)
    }

    func testAResetInSecondsAndOneInMillisecondsMeanTheSameInstant() {
        let seconds = KimiUsageParser.parse(
            payload(#"{ "usage": { "limit": 10, "used": 1, "resetTime": 1800000000 } }"#)).first?.resetAt
        let millis = KimiUsageParser.parse(
            payload(#"{ "usage": { "limit": 10, "used": 1, "resetTime": 1800000000000 } }"#)).first?.resetAt

        XCTAssertNotNil(seconds)
        XCTAssertEqual(seconds?.timeIntervalSince1970 ?? 0, 1_800_000_000, accuracy: 0.001)
        XCTAssertEqual(seconds, millis)
    }

    func testAnEmptyResponseYieldsNothingRatherThanAnInventedWindow() {
        XCTAssertTrue(KimiUsageParser.parse(payload("{}")).isEmpty)
        XCTAssertTrue(KimiUsageParser.parse(payload(#"{ "limits": [] }"#)).isEmpty)
    }
}
