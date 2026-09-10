import XCTest
@testable import UsageLimitsKit

/// The project id is what every Antigravity quota read is addressed by, and the iOS login
/// never resolved one: the profile was built with an empty attribute map, so every account
/// added on iOS authenticated and then failed each refresh with "project_id attribute is
/// required". These pin the reader that now fills it, against both shapes upstream is known
/// to emit — the same two Android has handled all along.
final class AntigravityProjectResolutionTests: XCTestCase {

    func testProjectIdArrivesAsABareString() {
        let payload: [String: Any] = ["cloudaicompanionProject": "projects/abc-123"]
        XCTAssertEqual(LoopbackLogin.projectID(in: payload), "projects/abc-123")
    }

    func testProjectIdArrivesAsAnObjectCarryingAnId() {
        // The shape that fails a string-only reader with the id in plain view.
        let payload: [String: Any] = ["cloudaicompanionProject": ["id": "abc-123", "name": "x"]]
        XCTAssertEqual(LoopbackLogin.projectID(in: payload), "abc-123")
    }

    func testAlternateKeysAreRead() {
        XCTAssertEqual(LoopbackLogin.projectID(in: ["project_id": "p1"]), "p1")
        XCTAssertEqual(LoopbackLogin.projectID(in: ["project": ["projectId": "p2"]]), "p2")
    }

    func testEmptyValuesDoNotCount() {
        // An empty id would be stored and then rejected at fetch time as missing — the exact
        // failure this is meant to end, one step later.
        XCTAssertNil(LoopbackLogin.projectID(in: ["cloudaicompanionProject": ""]))
        XCTAssertNil(LoopbackLogin.projectID(in: ["cloudaicompanionProject": ["id": ""]]))
        XCTAssertNil(LoopbackLogin.projectID(in: [:]))
    }

    func testTierNamePrefersNameThenId() {
        XCTAssertEqual(LoopbackLogin.tierName(in: ["currentTier": ["name": "Pro", "id": "pro-tier"]]), "Pro")
        XCTAssertEqual(LoopbackLogin.tierName(in: ["paid_tier": ["id": "pro-tier"]]), "pro-tier")
        XCTAssertNil(LoopbackLogin.tierName(in: ["currentTier": ["name": ""]]))
    }
}
