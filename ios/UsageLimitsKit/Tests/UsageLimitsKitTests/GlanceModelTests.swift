import XCTest
@testable import UsageLimitsKit

/// Ported from the Android `WidgetDataBuilderTest`, deliberately.
///
/// These are not fresh tests. They are the ordering rules the Android side got wrong once and
/// then pinned, and the value of porting them is that the two platforms have to agree: the same
/// accounts in the same state must produce the same order and the same headline numbers, or the
/// app tells one user two different things about one subscription.
///
/// Every value is synthetic.
final class GlanceModelTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func account(_ id: String, _ provider: ProviderID) -> ProviderAccount {
        ProviderAccount(
            id: id,
            provider: provider,
            externalAccountID: "ext-\(id)",
            email: "\(id)@example.com",
            displayName: nil,
            plan: "Plus",
            credentialReference: "ref-\(id)",
            createdAt: now,
            lastSuccessfulSync: now)
    }

    private func usage(
        _ id: String,
        _ provider: ProviderID,
        usedPercent: Double,
        fetchedAt: Date? = nil,
        status: SnapshotStatus = .ok
    ) -> AccountUsage {
        AccountUsage(
            account: account(id, provider),
            snapshot: UsageSnapshot(
                accountID: id,
                fetchedAt: fetchedAt ?? now,
                status: status,
                windows: [
                    UsageWindow(
                        id: "5h", label: "5h limit", category: .fiveHour,
                        usedPercent: usedPercent, periodSeconds: 18_000,
                        resetAt: now.addingTimeInterval(3_600), exhausted: false),
                    UsageWindow(
                        id: "wk", label: "Weekly", category: .weekly,
                        usedPercent: usedPercent, periodSeconds: 604_800,
                        resetAt: now.addingTimeInterval(86_400), exhausted: false),
                ]))
    }

    private var all: [AccountUsage] {
        [
            usage("a", .codex, usedPercent: 10),
            usage("b", .codex, usedPercent: 90),
            usage("c", .claude, usedPercent: 50),
        ]
    }

    // MARK: - Scope selection

    func testAccountScopeSelectsExactlyOneAccount() {
        let snapshot = GlanceModel.build(all, now: now, scope: .account, accountID: "b")

        XCTAssertEqual(snapshot.accountCount, 1)
        XCTAssertEqual(snapshot.accounts.map(\.id), ["b"])
    }

    func testProviderScopeSelectsEveryAccountOfThatProvider() {
        let snapshot = GlanceModel.build(all, now: now, scope: .provider, providerID: "codex")

        XCTAssertEqual(snapshot.accountCount, 2)
        XCTAssertEqual(Set(snapshot.accounts.map(\.id)), ["a", "b"])
    }

    func testAllAccountsScopeKeepsEveryAccount() {
        XCTAssertEqual(GlanceModel.build(all, now: now, scope: .allAccounts).accountCount, 3)
    }

    func testAnUnmatchedAccountIDYieldsTheEmptySnapshot() {
        let snapshot = GlanceModel.build(all, now: now, scope: .account, accountID: "missing")

        XCTAssertEqual(snapshot.accountCount, 0)
        XCTAssertEqual(snapshot, .empty)
    }

    // MARK: - Ordering

    func testMostCriticalLeadsWithTheWorstAccount() {
        let snapshot = GlanceModel.build(all, now: now, scope: .mostCritical)

        // "b" is at 10 % remaining — the one worth showing first.
        XCTAssertEqual(snapshot.accounts.first?.id, "b")
    }

    func testMostCriticalLeadsWithAnExhaustedAccountNotAStaleOrFailedOne() {
        let mixed = [
            // 80 % left, but too old to trust.
            usage("stale", .codex, usedPercent: 20,
                  fetchedAt: now.addingTimeInterval(-2 * Severity.staleAfter)),
            // 95 % left, but the last fetch failed.
            usage("error", .claude, usedPercent: 5, status: .failed),
            usage("out", .xai, usedPercent: 100),
        ]

        let snapshot = GlanceModel.build(mixed, now: now, scope: .mostCritical)

        // Ranking by the Severity case order put error, then stale, then exhausted — so this
        // used to lead with an account whose quota may be perfectly fine.
        XCTAssertEqual(snapshot.accounts.map(\.id), ["out", "stale", "error"])
    }

    func testOneSeverityBandIsOrderedByRemainingAscending() {
        // Both sit at 45 % and 30 % remaining: medium either way, so only the number separates
        // them. A comparator that saw equal keys left them in cache order.
        let band = [
            usage("roomy", .codex, usedPercent: 55),
            usage("tight", .claude, usedPercent: 70),
        ]

        let snapshot = GlanceModel.build(band, now: now, scope: .mostCritical)

        XCTAssertEqual(snapshot.accounts.map(\.id), ["tight", "roomy"])
    }

    func testTheWholeSeverityRampIsWalked() {
        let ramp = [
            usage("healthy", .codex, usedPercent: 10),
            usage("medium", .claude, usedPercent: 60),
            usage("low", .antigravity, usedPercent: 90),
            usage("exhausted", .xai, usedPercent: 100),
            usage("error", .codex, usedPercent: 5, status: .failed),
            usage("stale", .claude, usedPercent: 30,
                  fetchedAt: now.addingTimeInterval(-2 * Severity.staleAfter)),
        ]

        let snapshot = GlanceModel.build(ramp, now: now, scope: .mostCritical)

        XCTAssertEqual(
            snapshot.accounts.map(\.id),
            ["exhausted", "stale", "error", "low", "medium", "healthy"])
    }

    func testOrderingIsStableForAccountsThatCannotBeSeparated() {
        // Identical in every respect the sort can see. Without a stable tie-break they could
        // swap places between refreshes, which reads as the widget flickering for no reason.
        let twins = [usage("first", .codex, usedPercent: 50), usage("second", .claude, usedPercent: 50)]

        XCTAssertEqual(
            GlanceModel.build(twins, now: now, scope: .mostCritical).accounts.map(\.id),
            ["first", "second"])
    }

    // MARK: - Headlines and staleness

    func testHeadlinePicksTheTightestWindowOfEachHorizon() throws {
        let snapshot = GlanceModel.build(all, now: now, scope: .allAccounts)

        XCTAssertEqual(try XCTUnwrap(snapshot.headlineShort?.remainingPercent), 10, accuracy: 0.001)
        XCTAssertEqual(try XCTUnwrap(snapshot.headlineLong?.remainingPercent), 10, accuracy: 0.001)
    }

    func testAMonthlyWindowStandsInForTheLongHorizon() throws {
        // A plan with no weekly window would otherwise show nothing at all for the long
        // horizon, which reads as "no limit" rather than "a limit measured differently".
        let monthlyOnly = AccountUsage(
            account: account("m", .xai),
            snapshot: UsageSnapshot(
                accountID: "m", fetchedAt: now, status: .ok,
                windows: [
                    UsageWindow(id: "mo", label: "Monthly", category: .monthly,
                                usedPercent: 40, periodSeconds: 2_592_000,
                                resetAt: nil, exhausted: false),
                ]))

        let snapshot = GlanceModel.build([monthlyOnly], now: now, scope: .allAccounts)

        XCTAssertEqual(snapshot.headlineLong?.label, "Monthly")
        XCTAssertEqual(try XCTUnwrap(snapshot.headlineLong?.remainingPercent), 60, accuracy: 0.001)
        XCTAssertNil(snapshot.headlineShort)
    }

    func testAnOldSnapshotReportsAsStaleRatherThanHealthy() {
        let old = [
            usage("a", .codex, usedPercent: 1,
                  fetchedAt: now.addingTimeInterval(-2 * Severity.staleAfter)),
        ]

        XCTAssertEqual(
            GlanceModel.build(old, now: now, scope: .allAccounts).overallSeverity, .stale)
    }

    func testTheSubtitleNeverCarriesARawEmailAddress() {
        // A widget renders on a lock screen, where the account label is visible to anyone
        // holding the phone.
        let snapshot = GlanceModel.build(all, now: now, scope: .allAccounts)

        XCTAssertFalse(snapshot.accounts.contains { $0.subtitle?.contains("a@example.com") == true })
        XCTAssertTrue(snapshot.accounts.allSatisfy { $0.subtitle?.contains("@example.com") == true })
    }

    func testAnAccountBlockShowsAtMostTwoRows() {
        // The constraint that makes a tile readable: two horizons, not every window an account
        // reports.
        let many = AccountUsage(
            account: account("many", .claude),
            snapshot: UsageSnapshot(
                accountID: "many", fetchedAt: now, status: .ok,
                windows: (0..<8).map { index in
                    UsageWindow(
                        id: "w\(index)", label: "Window \(index)",
                        category: index.isMultiple(of: 2) ? .fiveHour : .weekly,
                        usedPercent: Double(index * 10), periodSeconds: nil,
                        resetAt: nil, exhausted: false)
                }))

        let snapshot = GlanceModel.build([many], now: now, scope: .allAccounts)

        XCTAssertEqual(snapshot.accounts.first?.rows.count, 2)
    }
}
