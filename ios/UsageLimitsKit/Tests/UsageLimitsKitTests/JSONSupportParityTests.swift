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
}
