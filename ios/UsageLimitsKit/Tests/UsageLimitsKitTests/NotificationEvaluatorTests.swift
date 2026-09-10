import XCTest
@testable import UsageLimitsKit

/// Ported from the Android suite, including every case its audit added.
///
/// The behaviour these pin is "said once", which no single snapshot can express, so each case
/// is a sequence driven through a ledger-backed harness rather than a single call. Testing the
/// evaluator alone reads every legitimate re-offer of a claimed key as a repeat notification,
/// which is precisely the false alarm this harness exists to avoid.
///
/// Every value is synthetic.
final class NotificationEvaluatorTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)
    private let settings = NotificationSettings()

    /// The publisher, reduced to the two things that decide what a user sees.
    private final class Publisher {
        private var consumed = Set<String>()
        private var states: [String: NotificationEvaluator.AccountState] = [:]

        func sync(
            _ accounts: [AccountSummary],
            _ settings: NotificationSettings,
            _ now: Date
        ) -> [String] {
            let outcome = NotificationEvaluator.evaluate(
                accounts: accounts, settings: settings, states: states, now: now)
            states = Dictionary(uniqueKeysWithValues: outcome.states.map { ($0.accountId, $0) })
            // Claim before posting, as the real publisher does.
            let fresh = outcome.events.filter { consumed.insert($0.key).inserted }
            return fresh.map(\.line).filter { !$0.isEmpty } + outcome.standingFindings
        }

        func state(_ accountId: String) -> NotificationEvaluator.AccountState? {
            states[accountId]
        }
    }

    private func account(
        _ id: String = "acct",
        remaining: Double?,
        fetchedAt: Date? = nil,
        failed: Bool = false,
        exhausted: Bool = false,
        resetAt: Date? = nil,
        credits: [ResetCredit] = [],
        errorMessage: String? = nil
    ) -> AccountSummary {
        AccountSummary(
            accountId: id,
            label: "Account \(id)",
            snapshot: UsageSnapshot(
                accountID: id,
                fetchedAt: fetchedAt ?? now,
                status: failed ? .failed : .ok,
                windows: [
                    UsageWindow(
                        id: "w", label: "5h limit", category: .fiveHour,
                        usedPercent: remaining.map { 100 - $0 },
                        periodSeconds: 18_000, resetAt: resetAt, exhausted: exhausted),
                ],
                resetCredits: credits,
                errorMessage: errorMessage))
    }

    // MARK: - Low-quota tiers

    /// An account with several windows, each with its own remaining percentage.
    private func multi(
        _ windows: [(String, Double?)], fetchedAt: Date? = nil, id: String = "acct"
    ) -> AccountSummary {
        AccountSummary(
            accountId: id,
            label: "Account \(id)",
            snapshot: UsageSnapshot(
                accountID: id,
                fetchedAt: fetchedAt ?? now,
                status: .ok,
                windows: windows.enumerated().map { i, entry in
                    UsageWindow(
                        id: "w\(i)", label: entry.0, category: i == 0 ? .fiveHour : .weekly,
                        usedPercent: entry.1.map { 100 - $0 },
                        periodSeconds: i == 0 ? 18_000 : 604_800, resetAt: nil,
                        exhausted: entry.1.map { $0 <= 0 } ?? false)
                },
                resetCredits: []))
    }

    func testOneWindowRunningOutDoesNotSilenceAnotherWindowsCrossings() {
        // The bug: the episode was per ACCOUNT. The five-hour window running out claimed every
        // tier, and the weekly window then crossing 20 %, 10 % and 0 % found nothing left to
        // claim — the user was never told about the limit that matters most. Same input,
        // same expected lines as the Kotlin test.
        let publisher = Publisher()
        let t = { (s: TimeInterval) in self.now.addingTimeInterval(s) }

        XCTAssertEqual(
            publisher.sync([multi([("5h limit", 0), ("Weekly", 60)])], settings, now),
            ["Account acct · 5h limit exhausted"])
        XCTAssertEqual(
            publisher.sync([multi([("5h limit", 0), ("Weekly", 18)], fetchedAt: t(1))], settings, now),
            ["Account acct · Weekly: less than 20% remaining"])
        XCTAssertEqual(
            publisher.sync([multi([("5h limit", 0), ("Weekly", 8)], fetchedAt: t(2))], settings, now),
            ["Account acct · Weekly: less than 10% remaining"])
        XCTAssertEqual(
            publisher.sync([multi([("5h limit", 0), ("Weekly", 0)], fetchedAt: t(3))], settings, now),
            ["Account acct · Weekly exhausted"])
        // A window that recovers re-arms on its own, without waiting for the other.
        XCTAssertTrue(publisher.sync([multi([("5h limit", 90), ("Weekly", 0)], fetchedAt: t(4))], settings, now).isEmpty)
        XCTAssertEqual(
            publisher.sync([multi([("5h limit", 15), ("Weekly", 0)], fetchedAt: t(5))], settings, now),
            ["Account acct · 5h limit: less than 20% remaining"])
    }

    func testKeysCarryTheWindowIdentity() {
        let outcome = NotificationEvaluator.evaluate(
            accounts: [multi([("5h limit", 18), ("Weekly", 18)])], settings: settings, states: [:], now: now)
        XCTAssertEqual(
            Set(outcome.events.map(\.key)),
            ["acct|fiveHour:5h limit|1|warning", "acct|weekly:Weekly|1|warning"])
    }

    func testStateWrittenBeforePerWindowEpisodesStillLoads() throws {
        // A ledger from before this change has no `windows` key. It must decode, not wipe.
        let legacy = Data(#"{"accountId":"a","lowQuotaEpisode":3,"lowQuotaActive":true}"#.utf8)
        let state = try JSONDecoder().decode(NotificationEvaluator.AccountState.self, from: legacy)
        XCTAssertEqual(state.lowQuotaEpisode, 3)
        XCTAssertTrue(state.windows.isEmpty)

        // And the per-window map survives a round trip.
        var next = state
        next.windows["fiveHour:5h limit"] = .init(episode: 2, active: true)
        let back = try JSONDecoder().decode(
            NotificationEvaluator.AccountState.self, from: JSONEncoder().encode(next))
        XCTAssertEqual(back.windows["fiveHour:5h limit"], .init(episode: 2, active: true))
    }


    func testCrossingBelowTwentyWarnsOnceThenStaysQuiet() {
        let publisher = Publisher()

        XCTAssertEqual(
            publisher.sync([account(remaining: 18)], settings, now),
            ["Account acct · 5h limit: less than 20% remaining"])
        XCTAssertTrue(
            publisher.sync(
                [account(remaining: 17, fetchedAt: now.addingTimeInterval(1))],
                settings, now).isEmpty)
    }

    func testDroppingBelowTenEscalatesOnce() {
        let publisher = Publisher()
        _ = publisher.sync([account(remaining: 18)], settings, now)

        XCTAssertEqual(
            publisher.sync(
                [account(remaining: 8, fetchedAt: now.addingTimeInterval(1))], settings, now),
            ["Account acct · 5h limit: less than 10% remaining"])
        XCTAssertTrue(
            publisher.sync(
                [account(remaining: 5, fetchedAt: now.addingTimeInterval(2))],
                settings, now).isEmpty)
    }

    func testFallingPastBothTiersSaysOnlyTheStrongerThing() {
        let publisher = Publisher()

        XCTAssertEqual(
            publisher.sync([account(remaining: 8)], settings, now),
            ["Account acct · 5h limit: less than 10% remaining"])
        // The warning key was spent on the way down, so climbing back to 15 % cannot produce a
        // delayed warning about a limit that just improved.
        XCTAssertTrue(
            publisher.sync(
                [account(remaining: 15, fetchedAt: now.addingTimeInterval(1))],
                settings, now).isEmpty)
    }

    func testAFullRecoveryRearmsTheWarning() {
        let publisher = Publisher()
        _ = publisher.sync([account(remaining: 8)], settings, now)
        XCTAssertTrue(
            publisher.sync(
                [account(remaining: 90, fetchedAt: now.addingTimeInterval(1))],
                settings, now).isEmpty)

        XCTAssertEqual(
            publisher.sync(
                [account(remaining: 18, fetchedAt: now.addingTimeInterval(2))], settings, now),
            ["Account acct · 5h limit: less than 20% remaining"])
    }

    func testExhaustionSupersedesAndConsumesTheLowTiers() {
        let publisher = Publisher()

        XCTAssertEqual(
            publisher.sync([account(remaining: 0, exhausted: true)], settings, now),
            ["Account acct · 5h limit exhausted"])
        // Partial recovery to 15 % must not now warn about 20 %: the user has already watched
        // this limit run out, and being told it is "low" afterwards is noise.
        XCTAssertTrue(
            publisher.sync(
                [account(remaining: 15, fetchedAt: now.addingTimeInterval(1))],
                settings, now).isEmpty)
    }

    func testACrashPastTenIsHeardByAUserWithOnlyTheTwentyTier() {
        // The urgent case used to be the silent one: the warning line was blanked whenever the
        // critical tier was reached, and the critical line was withheld because its own setting
        // was off, so a drop from 40 % to 8 % produced nothing at all.
        var onlyWarning = settings
        onlyWarning.notifyBelow10Percent = false

        XCTAssertEqual(
            Publisher().sync([account(remaining: 8)], onlyWarning, now),
            ["Account acct · 5h limit: less than 10% remaining"])
    }

    func testAnExhaustedLimitIsHeardByAUserWhoDisabledOnlyThatAlert() {
        var noExhausted = settings
        noExhausted.notifyOnExhausted = false

        // The message still describes what actually happened rather than understating it.
        XCTAssertEqual(
            Publisher().sync([account(remaining: 0, exhausted: true)], noExhausted, now),
            ["Account acct · 5h limit exhausted"])
    }

    func testEveryAlertOffIsSilentAndStaysSilentOnceEnabled() {
        var allOff = settings
        allOff.notifyOnExhausted = false
        allOff.notifyBelow20Percent = false
        allOff.notifyBelow10Percent = false

        let publisher = Publisher()
        XCTAssertTrue(
            publisher.sync([account(remaining: 0, exhausted: true)], allOff, now).isEmpty)
        // Turning the alerts on must not then deliver the exhaustion that happened while off.
        XCTAssertTrue(
            publisher.sync(
                [account(remaining: 0, fetchedAt: now.addingTimeInterval(1), exhausted: true)],
                settings, now).isEmpty)
    }

    func testAnUnknownPercentageDoesNotBlockRecoveryForEver() {
        // Treating unknown as "still low" looked cautious and was the opposite: one window
        // whose figure the provider stopped reporting vetoed recovery permanently, so the
        // account never alerted again.
        let publisher = Publisher()
        _ = publisher.sync([account(remaining: 8)], settings, now)
        _ = publisher.sync(
            [account(remaining: nil, fetchedAt: now.addingTimeInterval(1))], settings, now)

        XCTAssertEqual(
            publisher.sync(
                [account(remaining: 5, fetchedAt: now.addingTimeInterval(2))], settings, now),
            ["Account acct · 5h limit: less than 10% remaining"])
    }

    func testAFailedSnapshotChangesNothing() {
        let publisher = Publisher()
        _ = publisher.sync([account(remaining: 8)], settings, now)
        let before = publisher.state("acct")

        // A network blip reporting stale numbers must not end the episode.
        _ = publisher.sync(
            [account(remaining: 95, fetchedAt: now.addingTimeInterval(1), failed: true)],
            settings, now)

        XCTAssertEqual(publisher.state("acct"), before)
    }

    func testReprocessingTheSameSnapshotProducesNothingNew() {
        let publisher = Publisher()
        _ = publisher.sync([account(remaining: 18)], settings, now)

        XCTAssertTrue(publisher.sync([account(remaining: 18)], settings, now).isEmpty)
    }

    func testAnAccountMissingFromOneSyncKeepsItsEpisode() {
        // A partial provider response dropped the account's state, restarting its episode at 1
        // and colliding with keys episode 1 had already claimed — permanently silent after.
        let publisher = Publisher()
        _ = publisher.sync([account(remaining: 8)], settings, now)
        _ = publisher.sync([], settings, now)
        _ = publisher.sync(
            [account(remaining: 90, fetchedAt: now.addingTimeInterval(2))], settings, now)

        XCTAssertEqual(
            publisher.sync(
                [account(remaining: 18, fetchedAt: now.addingTimeInterval(3))], settings, now),
            ["Account acct · 5h limit: less than 20% remaining"])
    }

    func testEachAccountKeepsItsOwnEpisode() {
        let lines = Publisher().sync(
            [account("a", remaining: 18), account("b", remaining: 8)], settings, now)

        XCTAssertEqual(lines.count, 2)
    }

    // MARK: - Reset approaching

    func testAResetInsideTheLeadIntervalNotifiesOncePerScheduledTime() {
        var enabled = settings
        enabled.notifyOnResetApproaching = true
        let resetAt = now.addingTimeInterval(10 * 60)
        let publisher = Publisher()

        XCTAssertEqual(
            publisher.sync([account(remaining: 80, resetAt: resetAt)], enabled, now),
            ["Account acct · 5h limit resets in about 10 minutes"])
        XCTAssertTrue(
            publisher.sync(
                [account(remaining: 80, fetchedAt: now.addingTimeInterval(1), resetAt: resetAt)],
                enabled, now.addingTimeInterval(60)).isEmpty)
    }

    func testAResetAlreadyPastNeverNotifies() {
        var enabled = settings
        enabled.notifyOnResetApproaching = true

        XCTAssertTrue(
            Publisher().sync(
                [account(remaining: 80, resetAt: now.addingTimeInterval(-1))],
                enabled, now).isEmpty)
    }

    func testAResetBeyondTheLeadIntervalWaits() {
        var enabled = settings
        enabled.notifyOnResetApproaching = true

        XCTAssertTrue(
            Publisher().sync(
                [account(remaining: 80, resetAt: now.addingTimeInterval(31 * 60))],
                enabled, now).isEmpty)
    }

    func testResetApproachingSaysNothingUnlessAskedButStillConsumesTheKey() {
        let publisher = Publisher()
        let resetAt = now.addingTimeInterval(10 * 60)

        XCTAssertTrue(
            publisher.sync([account(remaining: 80, resetAt: resetAt)], settings, now).isEmpty)

        var enabled = settings
        enabled.notifyOnResetApproaching = true
        // Switching it on delivers what happens next, not a backlog.
        XCTAssertTrue(
            publisher.sync(
                [account(remaining: 80, fetchedAt: now.addingTimeInterval(1), resetAt: resetAt)],
                enabled, now).isEmpty)
    }

    // MARK: - Expiring credits

    func testACreditLapsingInsideTheLeadWindowNotifiesOnceWithItsOwnDeadline() {
        let credit = ResetCredit(
            id: "c1", grantedAt: now.addingTimeInterval(-1_000),
            expiresAt: now.addingTimeInterval(3_600), status: "available")
        let publisher = Publisher()

        XCTAssertEqual(
            publisher.sync([account(remaining: 80, credits: [credit])], settings, now),
            [
                "Account acct · a reset credit expires in 1h",
                "Account acct · 1 reset credit available",
            ])
        XCTAssertEqual(
            publisher.sync(
                [account(remaining: 80, fetchedAt: now.addingTimeInterval(1), credits: [credit])],
                settings, now),
            ["Account acct · 1 reset credit available"])
    }

    func testACreditEnteringTheWindowLaterIsAnnounced() {
        // The defect: a counted summary was hung on the first expiring credit, so a second
        // credit arriving later found that key claimed and lapsed in silence.
        let first = ResetCredit(
            id: "c1", grantedAt: now.addingTimeInterval(-1_000),
            expiresAt: now.addingTimeInterval(3_600), status: "available")
        let second = ResetCredit(
            id: "c2", grantedAt: now.addingTimeInterval(-1_000),
            expiresAt: now.addingTimeInterval(7_200), status: "available")
        let publisher = Publisher()

        _ = publisher.sync([account(remaining: 80, credits: [first])], settings, now)

        XCTAssertEqual(
            publisher.sync(
                [account(
                    remaining: 80, fetchedAt: now.addingTimeInterval(1),
                    credits: [first, second])],
                settings, now),
            [
                "Account acct · a reset credit expires in 2h",
                "Account acct · 2 reset credits available",
            ])
    }

    func testASpentCreditNeverClaimsAnExpiry() {
        let credit = ResetCredit(
            id: "c1", grantedAt: now.addingTimeInterval(-1_000),
            expiresAt: now.addingTimeInterval(3_600), status: "consumed")

        XCTAssertTrue(
            Publisher().sync([account(remaining: 80, credits: [credit])], settings, now).isEmpty)
    }

    func testACreditWithNoExpiryCannotBeSaidToBeExpiring() {
        // A count without a date proves nothing; guessing one would put a deadline on screen
        // the provider never stated.
        let credit = ResetCredit(
            id: "c1", grantedAt: now.addingTimeInterval(-1_000),
            expiresAt: nil, status: "available")

        XCTAssertEqual(
            Publisher().sync([account(remaining: 80, credits: [credit])], settings, now),
            ["Account acct · 1 reset credit available"])
    }

    // MARK: - Standing findings

    func testAnExpiredSignInIsReportedEvenThoughTheSnapshotFailed() {
        XCTAssertEqual(
            Publisher().sync(
                [account(remaining: nil, failed: true, errorMessage: "refresh token expired")],
                settings, now),
            ["Account acct needs to be reconnected"])
    }

    func testNoFindingsAtAllWhenEverythingIsHealthy() {
        XCTAssertTrue(Publisher().sync([account(remaining: 80)], settings, now).isEmpty)
    }
}
