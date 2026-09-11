import XCTest
@testable import UsageLimitsKit

/// A countdown is the second thing this app exists to answer, so the rounding is worth pinning.
final class CountdownTests: XCTestCase {

    func testTwoUnitsAtMost() {
        XCTAssertEqual(Countdown.format(seconds: 2 * 86_400 + 4 * 3_600 + 30 * 60), "2d 4h")
        XCTAssertEqual(Countdown.format(seconds: 3 * 3_600 + 12 * 60 + 45), "3h 12m")
        XCTAssertEqual(Countdown.format(seconds: 8 * 60), "8m")
    }

    func testAWholeUnitDropsTheEmptyRemainder() {
        XCTAssertEqual(Countdown.format(seconds: 2 * 86_400), "2d")
        XCTAssertEqual(Countdown.format(seconds: 3 * 3_600), "3h")
    }

    func testASubMinuteRemainderIsLessThanAMinuteNotAWholeOne() {
        // Two readings to avoid, and "<1m" is the only string that avoids both. "0m" reads as
        // "it has reset", which is the one thing not yet true and would send someone to a limit
        // they still cannot use. "1m" invents up to fifty-nine seconds the user does not have,
        // on a countdown whose whole job is to say when something comes back.
        //
        // It is also what the Kotlin twin has always said, and these two must agree: the same
        // account on a phone and a tablet cannot report different times.
        XCTAssertEqual(Countdown.format(seconds: 1), "<1m")
        XCTAssertEqual(Countdown.format(seconds: 59), "<1m")
        XCTAssertEqual(Countdown.format(seconds: 60), "1m")
    }

    func testAPassedResetReadsAsNow() {
        XCTAssertEqual(Countdown.format(seconds: 0), "now")
        XCTAssertEqual(Countdown.format(seconds: -120), "now")
    }

    func testFormattingAgainstADateMatchesTheSecondsForm() {
        let now = Date(timeIntervalSince1970: 1_757_000_000)
        XCTAssertEqual(Countdown.format(until: now.addingTimeInterval(3_600), from: now), "1h")
        // A reset already behind us never renders as a future duration.
        XCTAssertEqual(Countdown.format(until: now.addingTimeInterval(-1), from: now), "now")
    }

    func testAnAbsoluteTimeOmitsTheDateOnlyForToday() {
        // The date is noise for the common case and essential for every other one: "09:30"
        // alone cannot say whether a weekly limit returns this morning or next Tuesday.
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!

        let now = Date(timeIntervalSince1970: 1_757_000_000)
        let sameDay = Countdown.absolute(now.addingTimeInterval(3_600), now: now, calendar: calendar)
        let nextWeek = Countdown.absolute(
            now.addingTimeInterval(7 * 86_400), now: now, calendar: calendar)

        XCTAssertFalse(sameDay.isEmpty)
        XCTAssertTrue(nextWeek.count > sameDay.count)
    }
    /// The boundary itself. `-Double(Int.min) - 1` was meant as Int.max, but 2^63 - 1 is not a
    /// Double and rounds back up to 2^63, so a clamp to it admitted the one value it existed
    /// to stop and `Int(_:)` trapped there. Against that version this test aborts the process.
    func testAnIntervalAtTheIntBoundaryDoesNotTrap() {
        let epoch = Date(timeIntervalSince1970: 0)
        let far = Date(timeIntervalSince1970: Double(Int.max))
        XCTAssertEqual(Countdown.format(until: far, from: epoch), Countdown.format(seconds: Int.max))
        XCTAssertEqual(Countdown.format(until: epoch, from: far), "now")
    }

}
