import XCTest
@testable import UsageLimitsKit

/// xAI fixtures, ported from the Android suite.
///
/// This parser has the worst history of the four, and the fixtures reflect it: the original
/// returned ZERO windows against a real payload while every hand-written test passed, because
/// the tests were written in the shape the code expected rather than the shape xAI sends.
/// The envelope and cent-object cases below are that bug, pinned.
///
/// Every value is synthetic.
final class XaiBillingParserTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func payload(_ json: String) -> [String: Any] {
        (try! JSONSerialization.jsonObject(with: json.data(using: .utf8)!)) as! [String: Any]
    }

    /// Percentage of the named window, unwrapped.
    ///
    /// `windows.first { ... }?.usedPercent` is a `Double?` twice over, and the `accuracy:`
    /// overload of `XCTAssertEqual` takes a non-optional. Unwrapping here keeps every
    /// assertion below reading as the single fact it is checking, and a missing window fails
    /// with "expected non-nil" rather than a type error.
    private func percent(_ windows: [UsageWindow], _ id: String) throws -> Double {
        try XCTUnwrap(XCTUnwrap(windows.first { $0.id == id }).usedPercent)
    }

    // MARK: - The config envelope

    func testCreditsWrappedInConfigAreParsed() {
        // Both billing responses wrap their body in `config`. Reading the root yields nil for
        // every field — and since the caller only errors when BOTH endpoints fail, not when
        // both come back empty, the account recorded OK with zero windows, which the model
        // reads as .error. A permanent red pill from two HTTP 200s.
        let windows = XaiBillingParser.parseCredits(payload("""
        {
          "config": {
            "creditUsagePercent": 34.0,
            "currentPeriod": { "type": "weekly",
                               "start": "2026-09-01T00:00:00Z",
                               "end": "2026-09-08T00:00:00Z" }
          }
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].usedPercent, 34.0)
        XCTAssertEqual(windows[0].category, .weekly)
    }

    func testBillingCentsWrappedAsValObjectsAreParsed() throws {
        // Money arrives either bare or as {"val": n}. Reading only the bare form drops the
        // whole billing view.
        let windows = XaiBillingParser.parseBilling(payload("""
        {
          "config": {
            "monthlyLimit": { "val": 10000 },
            "used": { "val": 4200 },
            "onDemandCap": { "val": 5000 },
            "onDemandUsed": { "val": 1000 },
            "billingPeriodEnd": "2026-10-01T00:00:00Z"
          }
        }
        """), now: now)

        XCTAssertEqual(try percent(windows, "xai-monthly"), 42.0, accuracy: 0.001)
        XCTAssertEqual(try percent(windows, "xai-on-demand"), 20.0, accuracy: 0.001)
    }

    func testFlatPayloadWithoutTheEnvelopeStillParses() throws {
        let windows = XaiBillingParser.parseBilling(payload("""
        { "monthlyLimit": 10000, "used": 2500 }
        """), now: now)

        XCTAssertEqual(try percent(windows, "xai-monthly"), 25.0, accuracy: 0.001)
    }

    // MARK: - Not inventing numbers

    func testMissingMonthlyLimitDoesNotInventAnOnDemandFigure() {
        // With the limit defaulting to zero, ALL spend became overage: used=4000 against a
        // 5000 cap rendered "80% of on-demand consumed", a figure the provider never sent.
        let windows = XaiBillingParser.parseBilling(payload("""
        { "config": { "used": 4000, "onDemandCap": 5000 } }
        """), now: now)

        // The included window may appear, but with an UNKNOWN percentage, never a computed one.
        XCTAssertNil(windows.first { $0.id == "xai-monthly" }?.usedPercent ?? nil)
        // And no on-demand figure can be derived without a limit to subtract.
        XCTAssertNil(windows.first { $0.id == "xai-on-demand" })
    }

    func testExplicitOnDemandUsedIsHonouredWithoutALimit() throws {
        let windows = XaiBillingParser.parseBilling(payload("""
        { "config": { "used": 4000, "onDemandCap": 5000, "onDemandUsed": 500 } }
        """), now: now)

        XCTAssertEqual(try percent(windows, "xai-on-demand"), 10.0, accuracy: 0.001)
    }

    func testOverLimitSpendClampsTheIncludedWindow() throws {
        // Spend past the allowance is on-demand spend, so the included bar stops at 100 %.
        // Without the clamp an overspending account reads "140 % used".
        let windows = XaiBillingParser.parseBilling(payload("""
        { "config": { "monthlyLimit": 10000, "used": 14000 } }
        """), now: now)

        let monthly = windows.first { $0.id == "xai-monthly" }
        XCTAssertEqual(try percent(windows, "xai-monthly"), 100.0, accuracy: 0.001)
        XCTAssertTrue(monthly?.exhausted ?? false)
    }

    func testZeroMonthlyLimitYieldsNilRatherThanDividingByZero() {
        let windows = XaiBillingParser.parseBilling(payload("""
        { "config": { "monthlyLimit": 0, "used": 500 } }
        """), now: now)

        let monthly = windows.first { $0.id == "xai-monthly" }
        XCTAssertNotNil(monthly)
        XCTAssertNil(monthly?.usedPercent ?? nil)
    }

    func testOnDemandOmittedWhenCapIsZeroOrAbsent() {
        let zeroCap = XaiBillingParser.parseBilling(payload("""
        { "config": { "monthlyLimit": 10000, "used": 500, "onDemandCap": 0 } }
        """), now: now)
        XCTAssertNil(zeroCap.first { $0.id == "xai-on-demand" })

        let noCap = XaiBillingParser.parseBilling(payload("""
        { "config": { "monthlyLimit": 10000, "used": 500 } }
        """), now: now)
        XCTAssertNil(noCap.first { $0.id == "xai-on-demand" })
    }

    // MARK: - Period type

    func testPeriodTypeClassifiesWhenStampsAreAbsent() {
        // A weekly period with no stamps would otherwise classify as .other, and the widget
        // selects its long-horizon headline by .weekly — so xAI's only ready-made percentage
        // would vanish from the home screen.
        let windows = XaiBillingParser.parseCredits(payload("""
        { "config": { "creditUsagePercent": 10.0, "currentPeriod": { "type": "weekly" } } }
        """), now: now)

        XCTAssertEqual(windows[0].category, .weekly)
        XCTAssertEqual(windows[0].periodSeconds, 604_800)
    }

    func testMeasuredSpanWinsOverTheReportedType() {
        // The span is evidence; the type is a label. A fortnight must not read as a week just
        // because the payload says "weekly".
        let windows = XaiBillingParser.parseCredits(payload("""
        {
          "config": {
            "creditUsagePercent": 10.0,
            "currentPeriod": { "type": "weekly",
                               "start": "2026-09-01T00:00:00Z",
                               "end": "2026-10-01T00:00:00Z" }
          }
        }
        """), now: now)

        XCTAssertEqual(windows[0].category, .monthly)
    }

    func testSnakeCaseSpellingsParseIdentically() throws {
        let windows = XaiBillingParser.parseBilling(payload("""
        {
          "config": {
            "monthly_limit": 10000, "used": 3000,
            "on_demand_cap": 5000, "on_demand_used": 250,
            "billing_period_end": "2026-10-01T00:00:00Z"
          }
        }
        """), now: now)

        XCTAssertEqual(try percent(windows, "xai-monthly"), 30.0, accuracy: 0.001)
        XCTAssertEqual(try percent(windows, "xai-on-demand"), 5.0, accuracy: 0.001)
    }

    // MARK: - Merge and empties

    func testMergePutsCreditsFirstAndDropsDuplicateIDs() {
        let credits = XaiBillingParser.parseCredits(payload("""
        { "config": { "creditUsagePercent": 20.0 } }
        """), now: now)
        let billing = XaiBillingParser.parseBilling(payload("""
        { "config": { "monthlyLimit": 10000, "used": 1000 } }
        """), now: now)

        let merged = XaiBillingParser.merge(credits, billing)
        XCTAssertEqual(merged.first?.id, "xai-credits")
        XCTAssertEqual(Set(merged.map(\.id)).count, merged.count)
    }

    func testEmptyPayloadsYieldEmptyLists() {
        XCTAssertTrue(XaiBillingParser.parseCredits(payload("{}"), now: now).isEmpty)
        XCTAssertTrue(XaiBillingParser.parseBilling(payload("{}"), now: now).isEmpty)
        XCTAssertTrue(XaiBillingParser.merge([], []).isEmpty)
    }
}
