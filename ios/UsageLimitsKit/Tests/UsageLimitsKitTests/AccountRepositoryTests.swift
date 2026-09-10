import XCTest
@testable import UsageLimitsKit

/// The cache the app and the widget both read.
///
/// The behaviour worth pinning is what happens when a sync FAILS, because the obvious
/// implementation — replace the snapshot with the new one — silently throws away the last
/// numbers the user had. On a screen whose whole promise is "how much is left", blanking a card
/// because one refresh timed out is worse than showing yesterday's figure with a marker on it.
final class AccountRepositoryTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("accounts-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func account(_ id: String = "a", created: TimeInterval = 0) -> ProviderAccount {
        ProviderAccount(
            id: id,
            provider: .codex,
            externalAccountID: "ext-\(id)",
            email: "\(id)@example.com",
            displayName: nil,
            plan: nil,
            credentialReference: "ref-\(id)",
            createdAt: Date(timeIntervalSince1970: created),
            lastSuccessfulSync: nil)
    }

    private func window(_ usedPercent: Double) -> UsageWindow {
        UsageWindow(
            id: "5h", label: "5h limit", category: .fiveHour,
            usedPercent: usedPercent, periodSeconds: 18_000,
            resetAt: now.addingTimeInterval(3_600), exhausted: false)
    }

    private func result(_ usedPercent: Double, plan: String? = nil) -> UsageResult {
        UsageResult(
            windows: [window(usedPercent)], resetCredits: [],
            resetCreditCount: nil, applicableResetCreditCount: nil, plan: plan)
    }

    // MARK: - Round trip

    func testAnAccountSurvivesAReopen() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())

        let reopened = AccountRepository(directory: directory)

        let ids = await reopened.accounts().map(\.id)
        XCTAssertEqual(ids, ["a"])
    }

    func testAccountsComeBackInAStableOrder() async throws {
        // Oldest first, so the list does not reshuffle because a dictionary rehashed — a user
        // who learns where an account sits should find it there next time.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account("third", created: 300))
        try await repository.upsert(account("first", created: 100))
        try await repository.upsert(account("second", created: 200))

        let ids = await AccountRepository(directory: directory).accounts().map(\.id)
        XCTAssertEqual(ids, ["first", "second", "third"])
    }

    func testRemovingAnAccountRemovesItsUsageToo() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "a", result: result(20)), at: now)

        try await repository.remove(id: "a")

        let usage = await AccountRepository(directory: directory).usage()
        XCTAssertTrue(usage.isEmpty)
    }

    // MARK: - Recording a sync

    func testASuccessfulSyncIsStored() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())

        try await repository.record(.success(accountID: "a", result: result(20)), at: now)

        let usage = await repository.usage()
        XCTAssertEqual(usage.first?.snapshot?.status, .ok)
        XCTAssertEqual(usage.first?.snapshot?.windows.first?.usedPercent, 20)
        XCTAssertEqual(usage.first?.snapshot?.fetchedAt, now)
    }

    func testAPlanReportedBySyncIsRemembered() async throws {
        // The provider is the authority on which plan an account is on, and it only says so
        // during a sync. Discarding it means the subtitle stays blank for ever.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())

        try await repository.record(
            .success(accountID: "a", result: result(20, plan: "Max 5×")), at: now)

        let plan = await repository.accounts().first?.plan
        XCTAssertEqual(plan, "Max 5×")
    }

    func testAFailedSyncKeepsTheLastKnownNumbers() async throws {
        // The case this repository exists to get right. Replacing the snapshot on failure —
        // the obvious implementation — blanks the card, so one timeout costs the user the only
        // figures they had.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "a", result: result(20)), at: now)

        try await repository.record(
            .failure(accountID: "a", message: "the network went away"),
            at: now.addingTimeInterval(3_600))

        let snapshot = await repository.usage().first?.snapshot
        XCTAssertEqual(snapshot?.status, .failed)
        XCTAssertEqual(snapshot?.windows.first?.usedPercent, 20)
        XCTAssertEqual(snapshot?.errorMessage, "the network went away")
    }

    func testAFailedSyncKeepsTheORIGINALFetchTime() async throws {
        // Stamping a failure with the current time would make stale numbers look freshly
        // confirmed — and staleness is derived from exactly this field, so the card would go on
        // claiming to be current for as long as the failures continued.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "a", result: result(20)), at: now)

        try await repository.record(
            .failure(accountID: "a", message: "still gone"),
            at: now.addingTimeInterval(86_400))

        let snapshot = await repository.usage().first?.snapshot
        XCTAssertEqual(snapshot?.fetchedAt, now)
    }

    func testAFailureBeforeAnySuccessIsEmptyRatherThanWrong() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())

        try await repository.record(
            .failure(accountID: "a", message: "never worked"), at: now)

        let snapshot = await repository.usage().first?.snapshot
        XCTAssertEqual(snapshot?.status, .failed)
        XCTAssertTrue(snapshot?.windows.isEmpty ?? false)
    }

    func testAnOutcomeForAnUnknownAccountIsIgnored() async throws {
        // An account removed while its sync was in flight must not be resurrected by the
        // outcome arriving afterwards.
        let repository = AccountRepository(directory: directory)

        try await repository.record(.success(accountID: "ghost", result: result(20)), at: now)

        let accounts = await repository.accounts()
        XCTAssertTrue(accounts.isEmpty)
    }

    func testReSavingAnAccountKeepsItsUsage() async throws {
        // Re-authenticating changes the tokens, not the quota. Blanking the numbers would make
        // a successful sign-in look like a regression.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "a", result: result(20)), at: now)

        try await repository.upsert(account())

        let snapshot = await repository.usage().first?.snapshot
        XCTAssertEqual(snapshot?.windows.first?.usedPercent, 20)
    }

    // MARK: - What the file may contain

    func testTheFileHoldsNoTokenMaterial() async throws {
        // The structural reason this cache can live in a container the widget reads: an account
        // names its credentials, it does not carry them.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "a", result: result(20)), at: now)

        let contents = try String(
            contentsOf: directory.appendingPathComponent("accounts.json"), encoding: .utf8)

        XCTAssertTrue(contents.contains("credentialReference"))
        XCTAssertFalse(contents.lowercased().contains("accesstoken"))
        XCTAssertFalse(contents.lowercased().contains("refreshtoken"))
    }

    func testAnUnreadableCacheIsEmptyRatherThanFatal() throws {
        // It holds nothing a sync cannot rebuild, so refusing to open would strand the user
        // over a file that costs one refresh to replace.
        try "this is not JSON".write(
            to: directory.appendingPathComponent("accounts.json"),
            atomically: true, encoding: .utf8)

        let repository = AccountRepository(directory: directory)

        let expectation = expectation(description: "loaded")
        Task {
            let accounts = await repository.accounts()
            XCTAssertTrue(accounts.isEmpty)
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 5)
    }

    // MARK: - The sink

    func testTheSinkPersistsWhatTheEngineReports() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        let sink = RepositorySink(repository: repository)

        await sink.record(.success(accountID: "a", result: result(35)), at: now)

        let snapshot = await repository.usage().first?.snapshot
        XCTAssertEqual(snapshot?.windows.first?.usedPercent, 35)
    }
}
