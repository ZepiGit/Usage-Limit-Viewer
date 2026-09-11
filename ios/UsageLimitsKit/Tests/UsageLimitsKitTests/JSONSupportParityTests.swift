import XCTest
@testable import UsageLimitsKit

/// Where the shared JSON helpers had drifted from their Kotlin twins — found by a
/// field-by-field parity audit of the Claude parser, and each one a different number on
/// the two phones for one payload.
final class JSONSupportParityTests: XCTestCase {

    private func decode(_ json: String) -> [String: Any] {
        try! JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: Any]
    }

    func testABooleanIsNotANumber() {
        // `"percent": true` read as 1 % used on Swift and as no percentage on Android.
        XCTAssertNil(JSONSupport.double(decode(#"{"p": true}"#), "p"))
        XCTAssertNil(JSONSupport.double(decode(#"{"p": false}"#), "p"))
        XCTAssertEqual(JSONSupport.double(decode(#"{"p": 1}"#), "p"), 1.0)
        XCTAssertEqual(JSONSupport.double(decode(#"{"p": "2.5"}"#), "p"), 2.5)
    }

    func testANumberIsNotABoolean() {
        // `"has_claude_max": 1` yielded a "Max" plan on Swift and none on Android.
        XCTAssertNil(JSONSupport.bool(decode(#"{"b": 1}"#), "b"))
        XCTAssertNil(JSONSupport.bool(decode(#"{"b": 0}"#), "b"))
        XCTAssertEqual(JSONSupport.bool(decode(#"{"b": true}"#), "b"), true)
        XCTAssertEqual(JSONSupport.bool(decode(#"{"b": "false"}"#), "b"), false)
    }

    func testABareFractionalTimestampParsesLikeAndroid() {
        // "…T00:00:00.5" with no zone: Android trimmed and assumed UTC; Swift returned nil.
        let parsed = JSONSupport.date("2026-09-14T00:00:00.5")
        XCTAssertEqual(parsed?.timeIntervalSince1970, 1_789_344_000.5)
    }

    func testSixFractionalDigitsWithAZoneStillParse() {
        // What xAI actually sends.
        XCTAssertNotNil(JSONSupport.date("2026-09-03T18:32:26.743829+00:00"))
    }
    /// `Int64(_: Double)` TRAPS outside Int64's range where Kotlin's `toLong()` saturates, so
    /// the finiteness guard alone was not enough: "1e30" is finite. A trap inside the widget
    /// extension is a blank tile and no diagnostic, which is why this is a crash and not merely
    /// a wrong number. Against the unguarded version this test does not fail — it aborts.
    func testAnOutOfRangeIntegerIsRefusedRatherThanTrapping() {
        XCTAssertNil(JSONSupport.int64(["n": "1e30"], "n"))
        XCTAssertNil(JSONSupport.int64(["n": "-1e30"], "n"))
    }

    func testANonFiniteIntegerIsNoInteger() {
        XCTAssertNil(JSONSupport.int64(["n": "Infinity"], "n"))
        XCTAssertNil(JSONSupport.int64(["n": "NaN"], "n"))
    }

    /// The boundary itself, which is where a `<=` comparison would still trap: `Double(Int64.max)`
    /// rounds UP to 2^63 and is out of range.
    func testTheInt64BoundaryDoesNotTrap() {
        XCTAssertNil(JSONSupport.int64(["n": "9223372036854775808"], "n"))
        XCTAssertEqual(JSONSupport.int64(["n": "9223372036854775807"], "n"), Int64.max)
    }

    /// And an ordinary value still reads, so the guard costs nothing legitimate.
    func testAnOrdinaryOffsetStillReads() {
        XCTAssertEqual(JSONSupport.int64(["n": "18000"], "n"), 18_000)
        XCTAssertEqual(JSONSupport.int64(["n": "1.8e4"], "n"), 18_000)
    }

}
