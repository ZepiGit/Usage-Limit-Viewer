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
        usedPercent: Double?,
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
        XCTAssertEqual(snapshot.accounts.first?.id, "out")
    }

    func testAccountsWithNoNumbersDoNotDisplaceAccountsWithNumbers() {
        // A two-tile widget has two slots. A never-fetched or failed account has no rows to
        // show, so ranking it above a real one filled the tile with blank cards and pushed the
        // account at 3 % off the screen entirely.
        let mixed = [
            usage("never-fetched", .codex, usedPercent: nil),
            usage("failed", .claude, usedPercent: nil, status: .failed),
            usage("tight", .xai, usedPercent: 97),
        ]

        let snapshot = GlanceModel.build(mixed, now: now, scope: .mostCritical)

        XCTAssertEqual(snapshot.accounts.first?.id, "tight")
        // The unreadable accounts still reach the user, through the one signal that is not
        // slot-limited.
        XCTAssertEqual(snapshot.overallSeverity, Severity.error)
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
            ["exhausted", "low", "medium", "healthy", "error", "stale"])
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

    func testTheHeadlineBelongsToTheLeadingAccount() throws {
        // Not a pooled minimum. On a tile showing two of six accounts, a pooled figure could be
        // the sixth account's window — a number the reader cannot locate anywhere on screen.
        let snapshot = GlanceModel.build(all, now: now, scope: .mostCritical)

        let lead = try XCTUnwrap(snapshot.accounts.first)
        XCTAssertEqual(lead.id, "b")
        XCTAssertEqual(try XCTUnwrap(snapshot.headlineShort?.remainingPercent), 10, accuracy: 0.001)
        XCTAssertEqual(try XCTUnwrap(snapshot.headlineLong?.remainingPercent), 10, accuracy: 0.001)
        // And it is the same row the leading card itself shows.
        XCTAssertEqual(snapshot.headlineShort, lead.rows.first { $0.category == .fiveHour })
    }

    func testOneAccountsWeeklyWindowDoesNotSuppressAnothersMonthlyOne() throws {
        // Applying `weekly ?? monthly` to the pool meant any single weekly window hid every
        // monthly one, so the headline could read 80 % while a visible row read 3 %.
        let weeklyOnly = usage("weekly", .claude, usedPercent: 20)
        let monthlyOnly = AccountUsage(
            account: account("monthly", .xai),
            snapshot: UsageSnapshot(
                accountID: "monthly", fetchedAt: now, status: .ok,
                windows: [
                    UsageWindow(id: "mo", label: "Monthly", category: .monthly,
                                usedPercent: 97, periodSeconds: 2_592_000,
                                resetAt: nil, exhausted: false),
                ]))

        let snapshot = GlanceModel.build(
            [weeklyOnly, monthlyOnly], now: now, scope: .mostCritical)

        // The monthly-only account leads on 3 % remaining, and the headline is ITS window.
        XCTAssertEqual(snapshot.accounts.first?.id, "monthly")
        XCTAssertEqual(snapshot.headlineLong?.category, .monthly)
        XCTAssertEqual(try XCTUnwrap(snapshot.headlineLong?.remainingPercent), 3, accuracy: 0.001)
    }

    func testTheNextResetBelongsToTheAccountTheHeadlineDescribes() throws {
        // Taken across every account this paired the headline state with an unrelated clock:
        // "0 % left · resets in 12m", where the twelve minutes belonged to a healthy account's
        // five-hour window. Healthy five-hour windows reset constantly, so it was the common
        // case rather than an edge.
        let spentSoon = AccountUsage(
            account: account("spent", .codex),
            snapshot: UsageSnapshot(
                accountID: "spent", fetchedAt: now, status: .ok,
                windows: [
                    UsageWindow(id: "mo", label: "Monthly", category: .monthly,
                                usedPercent: 100, periodSeconds: 2_592_000,
                                resetAt: now.addingTimeInterval(20 * 86_400), exhausted: true),
                ]))
        let healthySoon = AccountUsage(
            account: account("healthy", .claude),
            snapshot: UsageSnapshot(
                accountID: "healthy", fetchedAt: now, status: .ok,
                windows: [
                    UsageWindow(id: "5h", label: "5h limit", category: .fiveHour,
                                usedPercent: 5, periodSeconds: 18_000,
                                resetAt: now.addingTimeInterval(12 * 60), exhausted: false),
                ]))

        let snapshot = GlanceModel.build(
            [healthySoon, spentSoon], now: now, scope: .mostCritical)

        XCTAssertEqual(snapshot.accounts.first?.id, "spent")
        XCTAssertEqual(snapshot.nextResetAt, now.addingTimeInterval(20 * 86_400))
    }

    func testAnUnknownPercentageIsTheMostUrgentHeadlineNotTheLeast() throws {
        // A nil percentage resolves to `.error`. Treating it as the largest possible number
        // made the one window the app could not read the last one it would ever show.
        let unreadable = AccountUsage(
            account: account("mixed", .codex),
            snapshot: UsageSnapshot(
                accountID: "mixed", fetchedAt: now, status: .ok,
                windows: [
                    UsageWindow(id: "a", label: "5h limit", category: .fiveHour,
                                usedPercent: nil, periodSeconds: 18_000,
                                resetAt: nil, exhausted: false),
                    UsageWindow(id: "b", label: "5h limit", category: .fiveHour,
                                usedPercent: 10, periodSeconds: 18_000,
                                resetAt: nil, exhausted: false),
                ]))

        let snapshot = GlanceModel.build([unreadable], now: now, scope: .mostCritical)

        XCTAssertNil(snapshot.headlineShort?.remainingPercent ?? nil)
        XCTAssertEqual(snapshot.headlineShort?.severity, .error)
    }

    func testSubPointDriftDoesNotReorderTheTiles() {
        // Two healthy accounts drifting 47.2 to 46.8 and 46.9 to 47.1 swapped on every refresh.
        // On a home screen that means the reader re-scans from scratch each time.
        let before = [
            usage("a", .codex, usedPercent: 52.8),
            usage("b", .claude, usedPercent: 53.1),
        ]
        let after = [
            usage("a", .codex, usedPercent: 53.2),
            usage("b", .claude, usedPercent: 52.9),
        ]

        XCTAssertEqual(
            GlanceModel.build(before, now: now, scope: .mostCritical).accounts.map(\.id),
            GlanceModel.build(after, now: now, scope: .mostCritical).accounts.map(\.id))
    }

    func testAMeaningfulDifferenceStillReorders() {
        let band = [
            usage("roomy", .codex, usedPercent: 55),
            usage("tight", .claude, usedPercent: 70),
        ]

        XCTAssertEqual(
            GlanceModel.build(band, now: now, scope: .mostCritical).accounts.map(\.id),
            ["tight", "roomy"])
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

// MARK: - Where the two platforms had drifted apart

/// Three divergences from the Kotlin original, found by reading them side by side.
///
/// The kit exists so an Android phone and an iPhone say the same thing about the same account.
/// A difference here is not a style question: it is one user being told 3 % and another being
/// told 80 % about the same subscription.
extension GlanceModelTests {

    private var parityNow: Date { Date(timeIntervalSince1970: 1_757_000_000) }

    private func parityAccount(_ id: String, plan: String? = nil) -> ProviderAccount {
        ProviderAccount(
            id: id, provider: .codex, externalAccountID: "ext-\(id)",
            email: "\(id)@example.com", displayName: nil, plan: plan,
            credentialReference: "ref-\(id)",
            createdAt: Date(timeIntervalSince1970: 0), lastSuccessfulSync: nil)
    }

    private func parityWindow(used: Double?) -> UsageWindow {
        UsageWindow(
            id: "5h", label: "5h limit", category: .fiveHour, usedPercent: used,
            periodSeconds: 18_000, resetAt: parityNow.addingTimeInterval(3_600),
            exhausted: false)
    }

    private func parityUsage(
        _ id: String, used: Double?, fetchedAt: Date, plan: String? = nil
    ) -> AccountUsage {
        AccountUsage(
            account: parityAccount(id, plan: plan),
            snapshot: UsageSnapshot(
                accountID: id, fetchedAt: fetchedAt, status: .ok,
                windows: [parityWindow(used: used)]))
    }

    func testTheStaleThresholdFollowsTheSyncIntervalRatherThanAConstant() throws {
        // At the three-hour interval the settings screen offers, a fixed one-hour threshold
        // marks a two-hour-old snapshot stale — and stale sorts LAST. The account at 3 % would
        // be pushed below a healthy one, and the headline would read 80 % with a 3 % card under
        // it. This is the whole reason the Kotlin builder takes the threshold as a parameter.
        let critical = parityUsage(
            "critical", used: 97, fetchedAt: parityNow.addingTimeInterval(-2 * 60 * 60))
        let healthy = parityUsage(
            "healthy", used: 20, fetchedAt: parityNow)

        let snapshot = GlanceModel.build(
            [healthy, critical], now: parityNow, scope: .mostCritical,
            staleAfter: Severity.staleAfter(syncIntervalMinutes: 180))

        XCTAssertEqual(snapshot.accounts.first?.id, "critical")
        XCTAssertEqual(snapshot.headlineShort?.remainingPercent, 3)
    }

    func testTheDefaultThresholdStillMarksATrulyOldSnapshotStale() throws {
        // The parameter must not become a way to never be stale.
        let old = parityUsage(
            "old", used: 97, fetchedAt: parityNow.addingTimeInterval(-2 * 60 * 60))

        let snapshot = GlanceModel.build([old], now: parityNow, scope: .mostCritical)

        XCTAssertEqual(snapshot.accounts.first?.severity, .stale)
    }

    func testANonFiniteUsageIsUnknownRatherThanACrash() throws {
        // A limit of zero divided into anything yields a non-finite figure, and clamping
        // propagates it: NaN compares false against every bound. Downstream it reaches an
        // `Int(_:)` conversion, which in Swift TRAPS where Kotlin's `.toInt()` saturates —
        // inside a widget extension that is a blank tile with nothing saying why.
        for hostile in [Double.nan, .infinity, -.infinity] {
            let usage = parityUsage("a", used: hostile, fetchedAt: parityNow)

            let snapshot = GlanceModel.build([usage], now: parityNow, scope: .mostCritical)

            XCTAssertNil(
                snapshot.accounts.first?.rows.first?.remainingPercent,
                "\(hostile) should read as unknown")
        }
    }

    func testAnAbsurdButFiniteUsageIsClampedRatherThanRefused() throws {
        // The clamp handles every finite value, so the guard above is about non-finite ones
        // alone: a merely enormous figure still means "nothing left", which is a statement the
        // app can honestly make.
        let usage = parityUsage("a", used: 1e300, fetchedAt: parityNow)

        let snapshot = GlanceModel.build([usage], now: parityNow, scope: .mostCritical)

        XCTAssertEqual(snapshot.accounts.first?.rows.first?.remainingPercent, 0)
    }

    func testAPlanThatIsOnlyWhitespaceIsNotAPlan() throws {
        // `.whitespaces` is space and tab only — a plan of "\n" survived it and appended a blank
        // line to a title in a fixed-height tile. Kotlin's `isNotBlank` counts newlines.
        let usage = parityUsage("a", used: 20, fetchedAt: parityNow, plan: "\n ")

        let snapshot = GlanceModel.build([usage], now: parityNow, scope: .mostCritical)

        XCTAssertEqual(snapshot.accounts.first?.title, ProviderID.codex.displayName)
    }

    func testARealPlanIsStillAppended() throws {
        let usage = parityUsage("a", used: 20, fetchedAt: parityNow, plan: "Max 5×")

        let snapshot = GlanceModel.build([usage], now: parityNow, scope: .mostCritical)

        XCTAssertEqual(snapshot.accounts.first?.title, "\(ProviderID.codex.displayName) Max 5×")
    }

    // MARK: - The long-horizon row

    private func withWindows(_ windows: [UsageWindow]) -> AccountUsage {
        AccountUsage(
            account: account("a", .codex),
            snapshot: UsageSnapshot(
                accountID: "a", fetchedAt: now, status: .ok, windows: windows))
    }

    private func longWindow(
        _ id: String, _ label: String, _ category: WindowCategory, usedPercent: Double?
    ) -> UsageWindow {
        UsageWindow(
            id: id, label: label, category: category,
            usedPercent: usedPercent,
            periodSeconds: category == .weekly ? 604_800 : 2_592_000,
            resetAt: now.addingTimeInterval(86_400),
            exhausted: usedPercent == 100)
    }

    func testAnExhaustedMonthlyWindowIsShownEvenBesideAHealthyWeeklyOne() {
        // `weekly ?? monthly ?? other` short-circuits on the mere existence of a weekly window,
        // so an account whose monthly quota had run out showed its healthy weekly bar and the
        // exhausted limit appeared nowhere on the tile. Kotlin had the identical defect.
        let snapshot = GlanceModel.build(
            [withWindows([
                longWindow("wk", "Weekly", .weekly, usedPercent: 5),
                longWindow("mo", "Monthly", .monthly, usedPercent: 100),
            ])],
            now: now, scope: .allAccounts, accountID: nil)

        let labels = snapshot.accounts[0].rows.map(\.label)
        XCTAssertTrue(labels.contains("Monthly"), "expected the exhausted monthly row, got \(labels)")
    }

    func testTheWeeklyWindowStillWinsWhenNothingIsMoreUrgent() {
        // The other direction: with both equally healthy, the better-understood category keeps
        // the slot. The change is one of ranking, not of preference.
        let snapshot = GlanceModel.build(
            [withWindows([
                longWindow("wk", "Weekly", .weekly, usedPercent: 5),
                longWindow("mo", "Monthly", .monthly, usedPercent: 5),
            ])],
            now: now, scope: .allAccounts, accountID: nil)

        XCTAssertEqual(snapshot.accounts[0].rows.map(\.label), ["Weekly"])
    }

    func testAnUnreadableWindowDoesNotDisplaceOneKnownToBeEmpty() {
        // `.error` ranks below `.exhausted` deliberately: a window whose percentage could not be
        // read says nothing actionable, one at zero says the user has run out.
        let snapshot = GlanceModel.build(
            [withWindows([
                longWindow("wk", "Weekly", .weekly, usedPercent: 100),
                longWindow("mo", "Monthly", .monthly, usedPercent: nil),
            ])],
            now: now, scope: .allAccounts, accountID: nil)

        XCTAssertEqual(snapshot.accounts[0].rows.map(\.label), ["Weekly"])
    }
}
