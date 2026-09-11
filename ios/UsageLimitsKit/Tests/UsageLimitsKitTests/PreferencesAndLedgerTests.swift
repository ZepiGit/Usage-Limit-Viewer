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

    /// The store used to normalise by rebuilding the value from the two fields it named, so
    /// every preference added afterwards was silently discarded on the way to disk — switching
    /// the tier off wrote it straight back on. This fails against that implementation.
    func testEveryDisplayPreferenceSurvivesASave() async throws {
        let store = SettingsStore(directory: directory)
        var settings = AppSettings()
        settings.showSubscriptionTier = false
        settings.showRenewalTime = true
        settings.accountsManuallyOrdered = true
        settings.notifications.mutedAccountIDs = ["acct-1", "acct-2"]

        let returned = try await store.save(settings)

        // What `save` reports and what the next launch reads have to agree: the settings screen
        // renders the former and the user judges it by the latter.
        XCTAssertEqual(returned, settings)
        let reopened = await SettingsStore(directory: directory).settings()
        XCTAssertFalse(reopened.showSubscriptionTier)
        XCTAssertTrue(reopened.showRenewalTime)
        XCTAssertTrue(reopened.accountsManuallyOrdered)
        XCTAssertEqual(reopened.notifications.mutedAccountIDs, ["acct-1", "acct-2"])
    }

    /// A file from before these preferences existed must keep every value it does state, and
    /// take the defaults only for what it does not.
    func testAFileWithoutTheDisplayPreferencesTakesTheirDefaults() async throws {
        let json = #"{"syncIntervalMinutes": 60}"#
        try Data(json.utf8).write(to: directory.appendingPathComponent("settings.json"))

        let settings = await SettingsStore(directory: directory).settings()

        XCTAssertEqual(settings.syncIntervalMinutes, 60)
        XCTAssertTrue(settings.showSubscriptionTier)
        XCTAssertFalse(settings.showRenewalTime)
        XCTAssertFalse(settings.accountsManuallyOrdered)
        XCTAssertTrue(settings.notifications.mutedAccountIDs.isEmpty)
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
    // MARK: - Arithmetic on a decoded interval

    /// `2 * minutes * 60` is Int arithmetic and trapped past Int.max / 120. The interval comes
    /// off a settings file with a floor and no ceiling, so a hand-edited or corrupt file could
    /// crash every launch. Against the Int version this test aborts the process.
    func testAHugeSyncIntervalDoesNotOverflowTheStaleThreshold() {
        XCTAssertEqual(
            Severity.staleAfter(syncIntervalMinutes: Int.max),
            TimeInterval(Int.max) * 120)
    }

    // MARK: - One ledger step

    /// A failed commit must leave the transition retryable.
    ///
    /// Split across save-then-claim, a failed first write left the ledger's cache advanced
    /// (lastProcessedFetchedAt moved) and its file not; the retry then read the same snapshot
    /// as a replay and skipped its quota edges. The warning was consumed and never returned,
    /// with no crash and nothing posted.
    func testAFailedLedgerWriteDoesNotConsumeTheTransition() async throws {
        // A directory that does not exist yet: the first write fails, and creating it afterwards
        // is "write access restored".
        let missing = directory.appendingPathComponent("not-yet")
        let ledger = NotificationLedger(directory: missing)
        let now = Date(timeIntervalSince1970: 1_757_000_000)
        let low = AccountSummary(
            accountId: "acct", label: "Account acct",
            snapshot: UsageSnapshot(
                accountID: "acct", fetchedAt: now, status: .ok,
                windows: [UsageWindow(
                    id: "w", label: "5h limit", category: .fiveHour, usedPercent: 85,
                    periodSeconds: 18_000, resetAt: nil, exhausted: false)]))

        do {
            _ = try await ledger.evaluateAndClaim(accounts: [low], settings: NotificationSettings(), at: now)
            XCTFail("the first write must fail: the directory does not exist")
        } catch {
            // Expected.
        }

        try FileManager.default.createDirectory(at: missing, withIntermediateDirectories: true)
        let retried = try await ledger.evaluateAndClaim(
            accounts: [low], settings: NotificationSettings(), at: now)

        XCTAssertEqual(
            retried.map(\.line), ["Account acct · 5h limit: less than 20% remaining"],
            "the transition must still be announced once the write succeeds")
        // And once more, to prove the claim then holds.
        let again = try await ledger.evaluateAndClaim(
            accounts: [low], settings: NotificationSettings(), at: now.addingTimeInterval(1))
        XCTAssertTrue(again.isEmpty)
    }

}
