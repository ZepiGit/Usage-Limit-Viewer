import XCTest
@testable import UsageLimitsKit

final class LoopbackSignInRaceTests: XCTestCase {
    // The Network-framework error is unavailable on Linux. The resolver must preserve
    // the original error regardless of its type, so use a synthetic port error here.
    private enum Failure: Error, Equatable {
        case portUnavailable
        case browserUnavailable
    }

    func testPortFailureSurvivesBrowserEndingWithoutACallback() throws {
        var race = LoopbackSignInRace()
        XCTAssertNil(try race.receive(.failure(Failure.portUnavailable), taskIsCancelled: false))

        XCTAssertThrowsError(try race.receive(.failure(CancellationError()), taskIsCancelled: false)) {
            XCTAssertEqual($0 as? Failure, .portUnavailable,
                           "Codex needs the original bind failure to start its device fallback")
        }
    }

    func testBrowserCanStillSucceedAfterTheListenerFails() throws {
        var race = LoopbackSignInRace()
        XCTAssertNil(try race.receive(.failure(Failure.portUnavailable), taskIsCancelled: false))

        XCTAssertEqual(try race.receive(.success("synthetic-code"), taskIsCancelled: false),
                       "synthetic-code")
    }

    func testBrowserCancellationWithoutAnEarlierFailureEndsTheAttempt() {
        var race = LoopbackSignInRace()
        XCTAssertThrowsError(try race.receive(.failure(CancellationError()), taskIsCancelled: false)) {
            XCTAssertTrue($0 is CancellationError)
        }
    }

    func testCancellingTheParentOverridesTheSavedPortFailure() throws {
        var race = LoopbackSignInRace()
        XCTAssertNil(try race.receive(.failure(Failure.portUnavailable), taskIsCancelled: false))

        XCTAssertThrowsError(try race.receive(.failure(CancellationError()), taskIsCancelled: true)) {
            XCTAssertTrue($0 is CancellationError, "A closed add-account sheet must not start a fallback")
        }
    }

    func testCancellingTheParentRejectsAnAlreadyQueuedCode() {
        var race = LoopbackSignInRace()
        XCTAssertThrowsError(try race.receive(.success("synthetic-code"), taskIsCancelled: true)) {
            XCTAssertTrue($0 is CancellationError)
        }
    }

    func testAListenerCodeCanWinAfterABrowserFailure() throws {
        var race = LoopbackSignInRace()
        XCTAssertNil(try race.receive(.failure(Failure.browserUnavailable), taskIsCancelled: false))

        XCTAssertEqual(try race.receive(.success("synthetic-code"), taskIsCancelled: false),
                       "synthetic-code")
    }

    func testExhaustedPathsRetainTheirError() throws {
        var race = LoopbackSignInRace()
        XCTAssertNil(try race.receive(.failure(Failure.browserUnavailable), taskIsCancelled: false))
        XCTAssertNil(try race.receive(.failure(Failure.portUnavailable), taskIsCancelled: false))

        XCTAssertEqual(race.terminalError as? Failure, .portUnavailable)
    }
}
