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
}
