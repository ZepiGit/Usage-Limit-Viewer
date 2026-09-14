import XCTest
@testable import UsageLimitsKit

final class WidgetSelectionContractTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func account(_ id: String, used: Double, reset: Date?, connection: ConnectionStatus = .connected) -> AccountUsage {
        AccountUsage(account: ProviderAccount(id: id, provider: .codex, externalAccountID: id,
            email: "\(id)@example.com", displayName: id, plan: "Plus", credentialReference: id,
            createdAt: now, lastSuccessfulSync: now), snapshot: UsageSnapshot(accountID: id, fetchedAt: now,
            status: .ok, windows: [UsageWindow(id: "short", label: "5h limit", category: .fiveHour,
                usedPercent: used, periodSeconds: 18_000, resetAt: reset, exhausted: used >= 100)],
            connectionStatus: connection))
    }

    func testQuotaAndConnectionAreIndependent() {
        let values = [account("empty", used: 100, reset: nil), account("low", used: 97, reset: nil),
            account("revoked", used: 0, reset: nil, connection: .reconnectRequired)]
        let result = GlanceModel.build(values, now: now, scope: .allAccounts)
        XCTAssertEqual(result.accounts.filter { $0.connectionStatus == .connected }.count, 2)
        XCTAssertEqual(result.accounts.first?.severity, .exhausted)
        XCTAssertEqual(result.accounts.last?.connectionStatus, .reconnectRequired)
    }

    func testClosestResetsUsesFutureTimeNotQuota() {
        let values = [account("later", used: 100, reset: now.addingTimeInterval(900)),
            account("soon", used: 0, reset: now.addingTimeInterval(10)), account("past", used: 10, reset: now.addingTimeInterval(-1))]
        XCTAssertEqual(GlanceModel.build(values, now: now, scope: .closestResets).accounts.map(\.id), ["soon", "later", "past"])
    }

    func testCustomPreservesTheExactSubsetAndOrder() {
        let values = [account("a", used: 0, reset: nil), account("b", used: 50, reset: nil), account("c", used: 100, reset: nil)]
        XCTAssertEqual(GlanceModel.build(values, now: now, scope: .custom,
            customAccountIDs: ["c", "missing", "c", "a"]).accounts.map(\.id), ["c", "a"])
        XCTAssertTrue(GlanceModel.build(values, now: now, scope: .custom).accounts.isEmpty)
        XCTAssertEqual(GlanceModel.build(values, now: now, scope: .account, accountID: "b").accounts.map(\.id), ["b"])
    }

    func testLegacyAuthenticationMappingIsNarrow() {
        XCTAssertEqual(ConnectionStatus.legacy(status: .failed, message: NotificationEvaluator.signInExpiredMessage), .reconnectRequired)
        XCTAssertEqual(ConnectionStatus.legacy(status: .failed, message: "Request deadline expired"), .unknown)
        XCTAssertEqual(ConnectionStatus.legacy(status: .ok, message: nil), .connected)
    }

    // MARK: WGT-002 — the two automatic scopes are different policies

    /// Overview order, urgency order and reset order are three different answers. The
    /// exporter publishes overview order; `selecting(.mostCritical)` must re-rank by URGENCY
    /// even when reset times disagree with it — selecting used to fall through to reset
    /// order, which made most-critical and closest-resets indistinguishable.
    func testSelectingMostCriticalDoesNotUseResetOrder() {
        let values = [account("exhausted-later", used: 100, reset: now.addingTimeInterval(9000)),
            account("healthy-soon", used: 0, reset: now.addingTimeInterval(10)),
            account("low-middle", used: 97, reset: now.addingTimeInterval(500))]
        let export = GlanceModel.build(values, now: now, scope: .allAccounts)

        XCTAssertEqual(export.selecting(scope: .mostCritical, now: now).accounts.map(\.id),
            ["exhausted-later", "low-middle", "healthy-soon"])
        XCTAssertEqual(export.selecting(scope: .closestResets, now: now).accounts.map(\.id),
            ["healthy-soon", "low-middle", "exhausted-later"])
    }

    /// The legacy tiles' contract is the account most in need of attention, read from the
    /// all-accounts export. Selection must also carry the LEAD's own clock: a critical lead
    /// whose reset is far away must not borrow the soonest account's reset instant.
    func testLegacyProviderSelectsCriticalAccountFromAllAccountsExport() {
        let values = [account("healthy-soon", used: 0, reset: now.addingTimeInterval(10)),
            account("exhausted-later", used: 100, reset: now.addingTimeInterval(9000))]
        let export = GlanceModel.build(values, now: now, scope: .allAccounts)

        let selected = export.selecting(scope: .mostCritical, now: now)
        XCTAssertEqual(selected.accounts.first?.id, "exhausted-later")
        XCTAssertEqual(selected.headlineShort?.category, .fiveHour)
        XCTAssertEqual(selected.nextResetAt, now.addingTimeInterval(9000))
    }

    /// The export itself stays in overview order — the configured "All accounts" choice
    /// depends on it, so the legacy fix must not change the shared export's ordering.
    func testConfiguredAllAccountsPreservesOverviewOrder() {
        let values = [account("c", used: 0, reset: nil), account("a", used: 100, reset: nil),
            account("b", used: 50, reset: nil)]
        let export = GlanceModel.build(values, now: now, scope: .allAccounts)
        XCTAssertEqual(export.accounts.map(\.id), ["c", "a", "b"])
        XCTAssertEqual(export.selecting(scope: .allAccounts, now: now).accounts.map(\.id), ["c", "a", "b"])
    }

    /// "Is anything wrong anywhere" across the SELECTION — the lead's own severity would
    /// hide a broken account behind a healthy one that sorts first.
    func testSelectionOverallSeveritySpansTheWholeSelection() {
        let values = [account("lead", used: 0, reset: nil), account("spent", used: 100, reset: nil)]
        let export = GlanceModel.build(values, now: now, scope: .allAccounts)
        XCTAssertEqual(export.accounts.first?.id, "lead")
        XCTAssertEqual(export.overallSeverity, .exhausted)
        XCTAssertEqual(export.selecting(scope: .allAccounts, now: now).overallSeverity, .exhausted)
    }
}
