import XCTest
@testable import UsageLimitsKit

/// The subscription tier, spelled the same way whichever provider supplied it — and the same
/// way its Kotlin twin spells it.
///
/// Anthropic hands back `default_claude_max_5x` and OpenAI hands back a bare `plus`. The Codex
/// path used to pass its value straight through, so one account read "OpenAI Codex plus" while
/// the Claude beside it read "Claude Max 5×".
///
/// Every case here is mirrored in `PlanLabelTest.kt`. Two apps that disagree about how to
/// spell a plan is the failure this pins.
final class PlanLabelTests: XCTestCase {

    func testABareLowercaseTierIsCapitalised() {
        // The Codex case, and the reason this exists.
        XCTAssertEqual(planLabel("plus"), "Plus")
        XCTAssertEqual(planLabel("pro"), "Pro")
        XCTAssertEqual(planLabel("free"), "Free")
    }

    func testNothingInMeansNothingOutRatherThanAnEmptyBadge() {
        XCTAssertNil(planLabel(nil))
        XCTAssertNil(planLabel(""))
        XCTAssertNil(planLabel("   "))
        XCTAssertNil(planLabel("__"))
    }

    func testSeparatorsBecomeSpacesAndDoNotDoubleUp() {
        XCTAssertEqual(planLabel("team_plus"), "Team Plus")
        XCTAssertEqual(planLabel("team__plus"), "Team Plus")
        // NOT a separator: splitting on a hyphen here and not on Android is how the two apps
        // start printing different things for the same account.
        XCTAssertEqual(planLabel("team-plus"), "Team-plus")
    }

    func testATrailingMultiplierIsAMultiplier() {
        XCTAssertEqual(planLabel("max_5x"), "Max 5×")
        XCTAssertEqual(planLabel("max_20x"), "Max 20×")
    }

    func testATierThatIsNothingButAMultiplierIsAName() {
        // Reading `20x` as a multiplier leaves no tier for it to multiply. `claude_20x` strips
        // to exactly this, and it is the case that first broke the shared formatter on Android.
        XCTAssertEqual(planLabel("20x"), "20x")
        XCTAssertEqual(planLabel("5x"), "5x")
    }

    func testATierNobodyHasSeenYetStillReadsAsWords() {
        XCTAssertEqual(planLabel("ultra_business"), "Ultra Business")
        XCTAssertEqual(planLabel("SCHOLAR"), "Scholar")
    }

    func testANonAsciiDigitIsNotAMultiplier() {
        // `isNumber` accepts every Unicode numeric category. Accepting one here and not on
        // Android would be a divergence, and "Max ²×" is noise either way.
        XCTAssertEqual(planLabel("max_٥x"), "Max ٥x")
    }
}
