import XCTest
@testable import UsageLimitsKit

/// The two things that have to survive the process dying.
///
/// Settings, because a preferences screen that forgets is worse than no preferences screen. And
/// the notification ledger, because the evaluator decides an account has BECOME low — a claim
/// that means nothing without a memory of what it said last time. Both are read by a background
/// task in a different process from the one that wrote them, so both are files rather than
/// anything held in memory.
final class PreferencesAndLedgerTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("prefs-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: - Settings

    func testASettingSurvivesAReopen() async throws {
        let store = SettingsStore(directory: directory)
        var settings = AppSettings()
        settings.syncIntervalMinutes = 180
        settings.notifications.notifyOnExhausted = false
        try await store.save(settings)

        let reopened = await SettingsStore(directory: directory).settings()

        XCTAssertEqual(reopened.syncIntervalMinutes, 180)
        XCTAssertFalse(reopened.notifications.notifyOnExhausted)
    }

    func testDefaultsRatherThanAFailureWhenNothingIsStored() async {
        let settings = await SettingsStore(directory: directory).settings()

        XCTAssertEqual(settings.syncIntervalMinutes, AppSettings.defaultSyncIntervalMinutes)
    }

    func testAnUnreadableFileFallsBackToDefaults() async throws {
        // Preferences, not data: losing them costs a trip back to the settings screen, where
        // refusing to launch over the file would cost the whole app.
        try "not json".write(
            to: directory.appendingPathComponent("settings.json"),
            atomically: true, encoding: .utf8)

        let settings = await SettingsStore(directory: directory).settings()

        XCTAssertEqual(settings.syncIntervalMinutes, AppSettings.defaultSyncIntervalMinutes)
    }

    func testAnIntervalBelowThePlatformFloorIsRaisedOnTheWayIn() async throws {
        // Below the floor the system quietly ignores the schedule, so the app would promise a
        // five-minute refresh and deliver whatever the platform felt like.
        let store = SettingsStore(directory: directory)

        let stored = try await store.save(AppSettings(syncIntervalMinutes: 5))

        XCTAssertEqual(stored.syncIntervalMinutes, AppSettings.minimumSyncIntervalMinutes)
    }

    func testAFileWrittenWithATooSmallIntervalIsRaisedOnTheWayOut() async throws {
        // A hand-edited or downgraded file is the other direction the floor can be bypassed
        // from, and it reaches the scheduler just the same.
        try #"{"syncIntervalMinutes":1,"notifications":{}}"#.write(
            to: directory.appendingPathComponent("settings.json"),
            atomically: true, encoding: .utf8)

        let settings = await SettingsStore(directory: directory).settings()

        XCTAssertEqual(settings.syncIntervalMinutes, AppSettings.minimumSyncIntervalMinutes)
    }

    func testASettingsFileFromAnOlderVersionKeepsWhatItDoesSay() async throws {
        // Synthesised decoding requires every key, so adding one toggle later would make every
        // stored file undecodable — and an undecodable file means "use defaults", silently
        // wiping every preference the user had set. A missing key is a version difference.
        try #"{"syncIntervalMinutes":120,"notifications":{"notifyOnExhausted":false}}"#.write(
            to: directory.appendingPathComponent("settings.json"),
            atomically: true, encoding: .utf8)

        let settings = await SettingsStore(directory: directory).settings()

        XCTAssertEqual(settings.syncIntervalMinutes, 120)
        XCTAssertFalse(settings.notifications.notifyOnExhausted)
        // Untouched by the file, so it keeps its default rather than becoming false.
        XCTAssertTrue(settings.notifications.notifyBelow20Percent)
    }

    func testTheStaleThresholdFollowsTheChosenInterval() async throws {
        // The setting that made a fixed one-hour threshold wrong: at a three-hour interval
        // every snapshot was older than an hour before the next arrived, so every account read
        // stale permanently.
        let store = SettingsStore(directory: directory)
        let stored = try await store.save(AppSettings(syncIntervalMinutes: 180))

        XCTAssertEqual(stored.staleAfter, 2 * 180 * 60)
    }

    // MARK: - The ledger

    private func event(_ key: String, account: String = "a") -> NotificationEvaluator.Event {
        NotificationEvaluator.Event(accountId: account, key: key, line: "line for \(key)")
    }

    func testAnEventIsClaimedOnceAndOnlyOnce() async throws {
        let ledger = NotificationLedger(directory: directory)

        let first = try await ledger.claim([event("a|0|low")], at: now)
        let second = try await ledger.claim([event("a|0|low")], at: now.addingTimeInterval(1_800))

        XCTAssertEqual(first.map(\.key), ["a|0|low"])
        XCTAssertTrue(second.isEmpty)
    }

    func testAClaimSurvivesTheProcess() async throws {
        // The whole reason this is a file. An account at 8% would otherwise be announced afresh
        // on every launch, and on every background refresh after one.
        let ledger = NotificationLedger(directory: directory)
        _ = try await ledger.claim([event("a|0|low")], at: now)

        let reopened = NotificationLedger(directory: directory)
        let again = try await reopened.claim([event("a|0|low")], at: now)

        XCTAssertTrue(again.isEmpty)
    }

    func testClaimingReturnsTheEventsSoTheCallerCanPostThem() async throws {
        let ledger = NotificationLedger(directory: directory)

        let claimed = try await ledger.claim(
            [event("a|0|low"), event("a|0|critical")], at: now)

        XCTAssertEqual(claimed.map(\.line), ["line for a|0|low", "line for a|0|critical"])
    }

    func testAMixOfNewAndAlreadyDeliveredKeepsOnlyTheNew() async throws {
        let ledger = NotificationLedger(directory: directory)
        _ = try await ledger.claim([event("a|0|low")], at: now)

        let claimed = try await ledger.claim(
            [event("a|0|low"), event("a|0|exhausted")], at: now)

        XCTAssertEqual(claimed.map(\.key), ["a|0|exhausted"])
    }

    func testReClaimingDoesNotExtendAKeyRetention() async throws {
        // Otherwise a still-true condition re-stated on every sync would keep its key alive for
        // ever, and the episode it belongs to could never age out.
        let ledger = NotificationLedger(directory: directory)
        _ = try await ledger.claim([event("a|0|low")], at: now)

        // Re-claimed just before it would age out...
        _ = try await ledger.claim(
            [event("a|0|low")],
            at: now.addingTimeInterval(NotificationLedger.retention - 60))
        // ...and then a sync past the original claim's retention.
        _ = try await ledger.claim(
            [event("unrelated")],
            at: now.addingTimeInterval(NotificationLedger.retention + 60))

        let expired = await ledger.hasDelivered("a|0|low")
        XCTAssertFalse(expired)
    }

    func testAKeyIsRememberedForLongerThanAnyRealEpisode() async throws {
        // A monthly window's exhaustion must not be re-announced because the record aged out
        // mid-month.
        let ledger = NotificationLedger(directory: directory)
        _ = try await ledger.claim([event("a|0|exhausted")], at: now)

        _ = try await ledger.claim(
            [event("unrelated")], at: now.addingTimeInterval(31 * 24 * 60 * 60))

        let stillThere = await ledger.hasDelivered("a|0|exhausted")
        XCTAssertTrue(stillThere)
    }

    func testEvaluatorStateSurvivesTheProcess() async throws {
        // Without it every launch reads as a fresh dip: the episode counter advances, fresh keys
        // are minted, and every threshold fires again.
        let ledger = NotificationLedger(directory: directory)
        try await ledger.save(states: [
            NotificationEvaluator.AccountState(
                accountId: "a", lowQuotaEpisode: 3, lowQuotaActive: true,
                lastProcessedFetchedAt: now),
        ])

        let reopened = await NotificationLedger(directory: directory).states()

        XCTAssertEqual(reopened["a"]?.lowQuotaEpisode, 3)
        XCTAssertEqual(reopened["a"]?.lowQuotaActive, true)
        XCTAssertEqual(reopened["a"]?.lastProcessedFetchedAt, now)
    }

    func testForgettingAnAccountClearsItsKeysAndNotItsNeighbours() async throws {
        // A re-added account under the same id would otherwise inherit records saying every one
        // of its edges had already been announced, and go silent while genuinely low.
        let ledger = NotificationLedger(directory: directory)
        _ = try await ledger.claim(
            [event("a|0|low", account: "a"), event("b|0|low", account: "b")], at: now)
        try await ledger.save(states: [
            NotificationEvaluator.AccountState(accountId: "a"),
            NotificationEvaluator.AccountState(accountId: "b"),
        ])

        try await ledger.forget(accountID: "a")

        let goneA = await ledger.hasDelivered("a|0|low")
        let keptB = await ledger.hasDelivered("b|0|low")
        let states = await ledger.states()
        XCTAssertFalse(goneA)
        XCTAssertTrue(keptB)
        XCTAssertNil(states["a"])
        XCTAssertNotNil(states["b"])
    }

    func testAnUnreadableLedgerIsEmptyRatherThanFatal() async throws {
        // Re-announcing an edge is a repeated message the user can see. Announcing nothing is
        // the failure they cannot.
        try "not json".write(
            to: directory.appendingPathComponent("notifications.json"),
            atomically: true, encoding: .utf8)

        let ledger = NotificationLedger(directory: directory)
        let claimed = try await ledger.claim([event("a|0|low")], at: now)

        XCTAssertEqual(claimed.count, 1)
    }

    // MARK: - What counts as a credit you have

    private func snapshot(
        rows: [ResetCredit], count: Int?, applicable: Int?
    ) -> UsageSnapshot {
        UsageSnapshot(
            accountID: "a", fetchedAt: now, status: .ok, windows: [],
            resetCredits: rows, resetCreditCount: count,
            applicableResetCreditCount: applicable)
    }

    func testACountWithoutRowsStillCounts() {
        // The shape Codex actually serves in its usage payload: a count and no rows at all.
        // Counting rows told a user holding two credits that they had none.
        let stored = snapshot(rows: [], count: 2, applicable: 2)

        XCTAssertEqual(stored.heldResetCredits, 2)
        XCTAssertEqual(stored.spendableResetCredits, 2)
    }

    func testASpentCreditIsNotOneYouHold() {
        // A consumed credit is still listed. Counting it says the user has one to spend.
        let stored = snapshot(
            rows: [ResetCredit(id: "c1", grantedAt: nil, expiresAt: nil, status: "consumed")],
            count: nil, applicable: nil)

        XCTAssertEqual(stored.heldResetCredits, 0)
    }

    func testHoldingCreditsIsNotTheSameAsBeingAbleToSpendOne() {
        // The distinction the whole applicable count exists for: two in the balance, none that
        // applies to the limit in force.
        let stored = snapshot(rows: [], count: 2, applicable: 0)

        XCTAssertEqual(stored.heldResetCredits, 2)
        XCTAssertEqual(stored.spendableResetCredits, 0)
    }
}
