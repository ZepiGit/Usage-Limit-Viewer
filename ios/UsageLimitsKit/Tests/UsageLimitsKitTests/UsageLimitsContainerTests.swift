import XCTest
@testable import UsageLimitsKit

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The wiring, exercised end to end without a network or a keychain.
///
/// The container is the one place where the pieces meet, so it is where the mistakes that no
/// single unit test can see actually live: a credential deleted in the wrong order, a widget
/// snapshot that stops being written when the account list empties, a refresh that returns the
/// figures from before the sync it just ran. None of those is visible from inside the parts.
/// A clock a test can move, for the cases that are about time passing rather than a fixed
/// instant. `Sendable` by lock rather than by actor so a `@Sendable` now-closure can read it.
private final class MovableClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date
    init(_ start: Date) { current = start }
    var now: Date { lock.lock(); defer { lock.unlock() }; return current }
    func advance(by seconds: TimeInterval) {
        lock.lock(); defer { lock.unlock() }
        current = current.addingTimeInterval(seconds)
    }
}

final class UsageLimitsContainerTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("container-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    /// Answers every request from a script, in order, repeating the last entry once the script
    /// runs out.
    ///
    /// Repeating rather than falling back to an empty 200, because the client retries a 5xx: a
    /// one-entry script of "the server is down" otherwise answers the retry with success, and a
    /// test written to prove a failure quietly proves nothing.
    private actor Transport: HTTPTransport {
        private var replies: [(status: Int, body: String)]
        private(set) var count = 0

        init(_ replies: [(status: Int, body: String)]) { self.replies = replies }

        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            count += 1
            let reply: (status: Int, body: String)
            switch replies.count {
            case 0: reply = (status: 200, body: "{}")
            case 1: reply = replies[0]
            default: reply = replies.removeFirst()
            }
            let response = HTTPURLResponse(
                url: request.url ?? URL(string: "https://example.invalid")!,
                statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: [:])!
            return (Data(reply.body.utf8), response)
        }
    }

    /// A trimmed Codex usage body: one weekly window, 40% used.
    private let codexUsage = """
    { "plan_type": "plus",
      "rate_limit": { "allowed": true, "limit_reached": false,
        "primary_window": { "used_percent": 40, "limit_window_seconds": 604800,
                            "reset_after_seconds": 469200, "reset_at": 1757469200 },
        "secondary_window": null },
      "rate_limit_reset_credits": { "available_count": 0, "applicable_available_count": 0 } }
    """

    private func account(_ id: String = "a") -> ProviderAccount {
        ProviderAccount(
            id: id,
            provider: .codex,
            externalAccountID: "ext-\(id)",
            email: "\(id)@example.com",
            displayName: nil,
            plan: nil,
            credentialReference: "ref-\(id)",
            createdAt: Date(timeIntervalSince1970: 0),
            lastSuccessfulSync: nil)
    }

    private func container(
        _ replies: [(status: Int, body: String)],
        credentials: InMemoryCredentialStore = InMemoryCredentialStore(
            credentials: ["ref-a": OAuthCredentials(
                accessToken: "synthetic-access", refreshToken: "synthetic-refresh")])
    ) -> (UsageLimitsContainer, InMemoryCredentialStore) {
        (UsageLimitsContainer(
            directory: directory,
            credentials: credentials,
            transport: Transport(replies),
            now: { [now] in now }),
         credentials)
    }

    // MARK: - A refresh, end to end

    func testARefreshReachesTheProviderAndComesBackWithNumbers() async throws {
        // Every layer at once: the account cache, the credential store, the engine, the Codex
        // adapter, the parser. Each is tested alone; this is the only test that says they are
        // plugged into one another.
        let (subject, _) = container([(200, codexUsage), (200, #"{"credits": []}"#)])
        try await subject.add(account())

        let usage = try await subject.refresh()

        XCTAssertEqual(usage.count, 1)
        XCTAssertEqual(usage.first?.snapshot?.status, .ok)
        XCTAssertEqual(usage.first?.snapshot?.windows.first?.usedPercent, 40)
        XCTAssertEqual(usage.first?.account.plan, "Plus")
    }

    func testAProviderFailureLandsOnTheAccountRatherThanTheRun() async throws {
        // A dead provider is one red card, not a failed refresh. Throwing here would blank
        // three healthy accounts because a fourth was having a bad day.
        let (subject, _) = container([(500, "upstream is unwell")])
        try await subject.add(account())

        let usage = try await subject.refresh()

        XCTAssertEqual(usage.first?.snapshot?.status, .failed)
        XCTAssertNotNil(usage.first?.snapshot?.errorMessage)
    }

    func testTheReturnedUsageIsTheResultOfTheSyncThatJustRan() async throws {
        // Returned rather than left to the caller to re-read, because a re-read races the write
        // the sync just made and can answer with the figures from before it.
        let (subject, _) = container([(200, codexUsage), (200, #"{"credits": []}"#)])
        try await subject.add(account())

        let returned = try await subject.refresh()
        let reRead = await subject.usage()

        XCTAssertEqual(
            returned.first?.snapshot?.windows.first?.usedPercent,
            reRead.first?.snapshot?.windows.first?.usedPercent)
    }

    // MARK: - What the widget is told

    func testTheWidgetSnapshotIsWrittenWhereTheWidgetLooks() async throws {
        let (subject, _) = container([(200, codexUsage), (200, #"{"credits": []}"#)])
        try await subject.add(account())

        _ = try await subject.refresh()

        let published = GlanceSnapshotCodec.read(fromDirectory: directory)
        XCTAssertEqual(published.accountCount, 1)
        XCTAssertEqual(published.accounts.first?.rows.first?.remainingPercent, 60)
    }

    func testAnEmptyAccountListIsStillPublished() async throws {
        // The tile has no other way to learn an account was removed. Skipping the publish
        // because there is nothing to sync leaves the home screen showing an account the user
        // deleted — with its last known numbers, indefinitely.
        let (subject, _) = container([(200, codexUsage), (200, #"{"credits": []}"#)])
        try await subject.add(account())
        _ = try await subject.refresh()
        XCTAssertFalse(GlanceSnapshotCodec.read(fromDirectory: directory).accounts.isEmpty)

        try await subject.remove(id: "a")

        XCTAssertTrue(GlanceSnapshotCodec.read(fromDirectory: directory).accounts.isEmpty)
    }

    func testTheWidgetFileHoldsNoTokenMaterial() async throws {
        // It lives in a container a second process reads, so this is worth asserting rather
        // than assuming.
        let (subject, _) = container([(200, codexUsage), (200, #"{"credits": []}"#)])
        try await subject.add(account())
        _ = try await subject.refresh()

        let contents = try String(
            contentsOf: directory.appendingPathComponent(GlanceSnapshotCodec.fileName),
            encoding: .utf8)

        XCTAssertFalse(contents.contains("synthetic-access"))
        XCTAssertFalse(contents.contains("synthetic-refresh"))
    }

    // MARK: - Signing in

    /// A JWT payload carrying the claims Codex's profile reads. Synthetic throughout.
    private func idToken() throws -> String {
        let claims: [String: Any] = [
            "sub": "user-1", "email": "someone@example.com",
            "https://api.openai.com/auth": [
                "chatgpt_account_id": "acct-1", "chatgpt_plan_type": "plus",
            ],
        ]
        let encoded = try JSONSerialization
            .data(withJSONObject: claims, options: [.sortedKeys])
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "header.\(encoded).signature"
    }

    func testSigningInStoresBothTheAccountAndItsCredentials() async throws {
        // The end of the flow that makes every other part of the app reachable. An account with
        // no credential is a row that can never sync and offers no way to repair itself.
        let token = try idToken()
        let (subject, credentials) = container([
            (200, #"{"user_code": "ABCD", "device_auth_id": "d-1"}"#),
            (200, #"{"authorization_code": "code-1", "code_verifier": "v-1"}"#),
            (200, #"{"access_token": "acc", "refresh_token": "ref", "id_token": "\#(token)", "expires_in": 3600}"#),
        ], credentials: InMemoryCredentialStore())

        let challenge = try await subject.beginLogin(provider: .codex)
        let account = try await subject.completeLogin(provider: .codex, challenge: challenge)

        XCTAssertEqual(challenge.userCode, "ABCD")
        XCTAssertEqual(account.externalAccountID, "acct-1")
        XCTAssertEqual(account.email, "someone@example.com")
        XCTAssertEqual(account.plan, "Plus")
        // The account id scopes team and enterprise quota, and travels as a header on the usage
        // call — so it has to survive from the sign-in that learned it.
        XCTAssertEqual(account.attributes["chatgpt_account_id"], "acct-1")

        let stored = try await credentials.load(reference: account.credentialReference)
        XCTAssertEqual(stored?.accessToken, "acc")
    }

    func testSigningInAgainUpdatesTheAccountRatherThanDuplicatingIt() async throws {
        // Re-authenticating changes the tokens, not the identity. A second row for the same
        // account would double every figure the overview adds up.
        let token = try idToken()
        let replies: [(status: Int, body: String)] = [
            (200, #"{"user_code": "ABCD", "device_auth_id": "d-1"}"#),
            (200, #"{"authorization_code": "code-1", "code_verifier": "v-1"}"#),
            (200, #"{"access_token": "acc", "id_token": "\#(token)", "expires_in": 3600}"#),
            (200, #"{"user_code": "EFGH", "device_auth_id": "d-2"}"#),
            (200, #"{"authorization_code": "code-2", "code_verifier": "v-2"}"#),
            (200, #"{"access_token": "acc-2", "id_token": "\#(token)", "expires_in": 3600}"#),
        ]
        let (subject, _) = container(replies, credentials: InMemoryCredentialStore())

        let first = try await subject.beginLogin(provider: .codex)
        _ = try await subject.completeLogin(provider: .codex, challenge: first)
        let second = try await subject.beginLogin(provider: .codex)
        _ = try await subject.completeLogin(provider: .codex, challenge: second)

        let usage = await subject.usage()
        XCTAssertEqual(usage.count, 1)
    }

    func testAProviderAPhoneCannotSignIntoSaysSoRatherThanFailingLater() async throws {
        let (subject, _) = container([])

        do {
            _ = try await subject.beginLogin(provider: .claude)
            XCTFail("Claude cannot be signed into from here")
        } catch let error as DeviceLoginError {
            guard case .unsupportedOnThisPlatform(let reason) = error else {
                return XCTFail("expected unsupportedOnThisPlatform, got \(error)")
            }
            XCTAssertFalse(reason.isEmpty)
        }
    }

    // MARK: - Spending a reset credit

    /// A Codex payload holding two credits, of which the provider says NONE applies right now.
    private let creditsHeldButNoneApplicable = """
    { "plan_type": "plus",
      "rate_limit": { "allowed": false, "limit_reached": true,
        "primary_window": { "used_percent": 100, "limit_window_seconds": 18000,
                            "reset_after_seconds": 3600, "reset_at": 1757003600 } },
      "rate_limit_reset_credits": { "available_count": 2, "applicable_available_count": 0 } }
    """

    /// The same account with one credit the provider says can be applied.
    private let creditApplicable = """
    { "plan_type": "plus",
      "rate_limit": { "allowed": false, "limit_reached": true,
        "primary_window": { "used_percent": 100, "limit_window_seconds": 18000,
                            "reset_after_seconds": 3600, "reset_at": 1757003600 } },
      "rate_limit_reset_credits": { "available_count": 2, "applicable_available_count": 1 } }
    """

    func testTheApplicableCountSurvivesIntoTheSnapshot() async throws {
        // The client reads this off the one source that reports it. Losing it at the model
        // boundary leaves the redeem control gated on the HELD count, which is the exact bug
        // that reading it was meant to fix.
        let (subject, _) = container([
            (200, creditsHeldButNoneApplicable), (200, #"{"credits": [], "available_count": 2}"#),
        ])
        try await subject.add(account())

        let usage = try await subject.refresh()

        XCTAssertEqual(usage.first?.snapshot?.heldResetCredits, 2)
        XCTAssertEqual(usage.first?.snapshot?.spendableResetCredits, 0)
    }

    func testCreditsHeldButNotApplicableCannotBeSpent() async throws {
        // Two credits in the balance and none that applies. Offering the spend would take one
        // against a refusal the user watches their balance absorb.
        let (subject, _) = container([
            (200, creditsHeldButNoneApplicable), (200, #"{"credits": [], "available_count": 2}"#),
        ])
        try await subject.add(account())
        _ = try await subject.refresh()

        do {
            _ = try await subject.redeemResetCredit(accountID: "a")
            XCTFail("a credit that does not apply must not be spent")
        } catch let error as UsageLimitsContainer.ResetCreditError {
            XCTAssertEqual(error, .noneApplicable)
        }
    }

    func testAnApplicableCreditIsSpentAndTheAccountRefreshed() async throws {
        let (subject, _) = container([
            (200, creditApplicable), (200, #"{"credits": [], "available_count": 2}"#),
            (200, "{}"),                                        // the redemption itself
            (200, creditApplicable), (200, #"{"credits": [], "available_count": 1}"#),
        ])
        try await subject.add(account())
        _ = try await subject.refresh()

        let usage = try await subject.redeemResetCredit(accountID: "a")

        XCTAssertEqual(usage.first?.snapshot?.status, .ok)
    }

    func testAnExpiredTokenIsRenewedBeforeTheCreditIsSpent() async throws {
        // Redemption loaded the stored pair and sent it as-is. Leave the app open past the
        // access token's expiry, tap redeem, and it failed with 401 while a perfectly usable
        // refresh token sat in the store — the user told their credit could not be spent for
        // a reason entirely inside this app, on the one action where that is least acceptable.
        //
        // Which is why the clock moves here rather than the credential starting expired: the
        // token has to be good enough for the first sync and stale by the time the button is
        // pressed, and that is precisely an app left open for an hour.
        let clock = MovableClock(now)
        let expiring = OAuthCredentials(
            accessToken: "first-access",
            refreshToken: "live-refresh",
            expiresAt: now.addingTimeInterval(1800))
        let store = InMemoryCredentialStore(credentials: ["ref-a": expiring])
        let transport = Transport([
            (200, creditApplicable), (200, #"{"credits": [], "available_count": 2}"#),
            (200, #"{"access_token":"fresh-access","refresh_token":"live-refresh","expires_in":3600}"#),
            (200, "{}"),                                        // the redemption itself
            (200, creditApplicable), (200, #"{"credits": [], "available_count": 1}"#),
        ])
        let subject = UsageLimitsContainer(
            directory: directory,
            credentials: store,
            transport: transport,
            now: { clock.now })

        try await subject.add(account())
        _ = try await subject.refresh()

        // An hour passes with the app open. The stored access token is now past its expiry.
        clock.advance(by: 3600)

        let usage = try await subject.redeemResetCredit(accountID: "a")

        XCTAssertEqual(usage.first?.snapshot?.status, .ok)
        let stored = try await store.load(reference: "ref-a")
        XCTAssertEqual(stored?.accessToken, "fresh-access",
                       "the renewed pair must be the one that was persisted and sent")
    }

    func testAnUnknownAccountCannotSpendAnything() async throws {
        let (subject, _) = container([])

        do {
            _ = try await subject.redeemResetCredit(accountID: "ghost")
            XCTFail("there is no such account")
        } catch let error as UsageLimitsContainer.ResetCreditError {
            XCTAssertEqual(error, .notAvailable)
        }
    }

    // MARK: - Notifications

    /// A payload whose 5-hour window is nearly spent, so the evaluator has something to say.
    private let codexLow = """
    { "plan_type": "plus",
      "rate_limit": { "allowed": true, "limit_reached": false,
        "primary_window": { "used_percent": 95, "limit_window_seconds": 18000,
                            "reset_after_seconds": 3600, "reset_at": 1757003600 },
        "secondary_window": null } }
    """

    func testAnEdgeIsOfferedOnceAndNotAgain() async throws {
        // The property the whole ledger exists for. Re-deriving from the snapshot would say
        // "low quota" every thirty minutes for as long as the account stayed low, which is what
        // makes people switch notifications off.
        let (subject, _) = container([(200, codexLow), (200, #"{"credits": []}"#)])
        try await subject.add(account())
        _ = try await subject.refresh()

        let first = try await subject.pendingNotifications()
        let second = try await subject.pendingNotifications()

        XCTAssertFalse(first.isEmpty)
        XCTAssertTrue(second.isEmpty)
    }

    func testAClaimSurvivesTheProcessThatMadeIt() async throws {
        // A background refresh runs in a fresh process. Without the file, every one of them
        // would re-announce the same edge.
        let (subject, credentials) = container([(200, codexLow), (200, #"{"credits": []}"#)])
        try await subject.add(account())
        _ = try await subject.refresh()
        _ = try await subject.pendingNotifications()

        let restarted = UsageLimitsContainer(
            directory: directory, credentials: credentials,
            transport: Transport([]), now: { [now] in now })

        let again = try await restarted.pendingNotifications()
        XCTAssertTrue(again.isEmpty)
    }

    func testAnAlertTheUserTurnedOffIsNotOffered() async throws {
        let (subject, _) = container([(200, codexLow), (200, #"{"credits": []}"#)])
        try await subject.add(account())
        _ = try await subject.refresh()

        var settings = AppSettings()
        settings.notifications.notifyBelow20Percent = false
        settings.notifications.notifyBelow10Percent = false
        settings.notifications.notifyOnExhausted = false
        try await subject.save(settings: settings)

        let pending = try await subject.pendingNotifications()
        XCTAssertTrue(pending.isEmpty, "offered: \(pending.map(\.key))")
    }

    func testSettingsSurviveTheProcess() async throws {
        let (subject, credentials) = container([])
        try await subject.save(settings: AppSettings(syncIntervalMinutes: 180))

        let restarted = UsageLimitsContainer(
            directory: directory, credentials: credentials,
            transport: Transport([]), now: { [now] in now })

        let minutes = await restarted.settings().syncIntervalMinutes
        XCTAssertEqual(minutes, 180)
    }

    func testRemovingAnAccountClearsWhatWasSaidAboutIt() async throws {
        // Re-added under the same id, it would otherwise inherit records saying every edge had
        // already been announced, and stay silent while genuinely low.
        // Four replies: usage and credits for each of the two refreshes below.
        let (subject, credentials) = container([
            (200, codexLow), (200, #"{"credits": []}"#),
            (200, codexLow), (200, #"{"credits": []}"#),
        ])
        try await subject.add(account())
        _ = try await subject.refresh()
        let before = try await subject.pendingNotifications()
        XCTAssertFalse(before.isEmpty)

        try await subject.remove(id: "a")
        // Re-adding an account means signing in again, so the credential comes back with it —
        // `remove` deleted it, which is the behaviour a neighbouring test pins.
        try await credentials.save(
            OAuthCredentials(accessToken: "synthetic-access", refreshToken: "synthetic-refresh"),
            reference: "ref-a")
        try await subject.add(account())
        _ = try await subject.refresh()

        let after = try await subject.pendingNotifications()
        XCTAssertFalse(after.isEmpty, "nothing offered for the re-added account")
    }

    // MARK: - Removing an account

    func testRemovingAnAccountRemovesItsCredentialToo() async throws {
        // A keychain entry nothing references is invisible, unreachable, and still grants access
        // to a paid account.
        let (subject, credentials) = container([])
        try await subject.add(account())

        try await subject.remove(id: "a")

        let left = try await credentials.load(reference: "ref-a")
        XCTAssertNil(left)
        let accounts = await subject.usage()
        XCTAssertTrue(accounts.isEmpty)
    }

    func testAFailedCredentialDeleteLeavesTheAccountVisible() async throws {
        // The recoverable order. If the delete fails the user still sees the account and can
        // try again; the other order leaves an orphaned credential nothing can ever reach.
        let refusing = RefusingCredentialStore()
        let subject = UsageLimitsContainer(
            directory: directory, credentials: refusing,
            transport: Transport([]), now: { [now] in now })
        try await subject.add(account())

        do {
            try await subject.remove(id: "a")
            XCTFail("the delete failed; the removal must not report success")
        } catch {
            // expected
        }

        let accounts = await subject.usage()
        XCTAssertEqual(accounts.count, 1)
    }

    // MARK: - Reinstall

    func testAReinstallCanForgetCredentialsThatOutlivedTheApp() async throws {
        // Keychain items survive the app being deleted while everything in the container does
        // not. Without this, a user who deletes the app to revoke its access and later
        // reinstalls finds every paid account still connected, with no sign-in.
        let (subject, credentials) = container([])

        try await subject.purgeCredentialsFromPreviousInstall()

        let left = try await credentials.load(reference: "ref-a")
        XCTAssertNil(left)
    }

    // MARK: - The install check

    func testAReinstallDoesNotInheritTheCredentialsOfThePreviousInstall() async throws {
        // Keychain items outlive the app being deleted; the container does not. A user who
        // deletes the app to revoke its access and reinstalls therefore arrives with an empty
        // container and a keychain still holding every token. `purgeCredentialsFromPreviousInstall`
        // was written for exactly this and then never called from anywhere, so the guarantee was
        // documented and absent.
        let store = InMemoryCredentialStore(
            credentials: ["ref-a": OAuthCredentials(
                accessToken: "left-behind", refreshToken: "left-behind-refresh")])
        let (subject, _) = container([], credentials: store)

        try await subject.prepareForUse()

        let references = try await store.allReferences()
        XCTAssertEqual(references, [], "a fresh container means a reinstall: nothing carries over")
    }

    func testAnExistingInstallKeepsItsAccountsWhenTheMarkerFirstAppears() async throws {
        // The half that makes the purge safe to ship. Every installation that predates the
        // marker also lacks one, so "no marker means purge" would sign out every existing user
        // on the upgrade that introduced it. An existing accounts file is what distinguishes an
        // install already in use from a genuinely new container.
        let store = InMemoryCredentialStore(
            credentials: ["ref-a": OAuthCredentials(
                accessToken: "synthetic-access", refreshToken: "synthetic-refresh")])
        let (subject, _) = container([], credentials: store)
        try await subject.add(account())

        // A second container over the same directory: the app relaunching after the upgrade.
        let upgraded = UsageLimitsContainer(
            directory: directory,
            credentials: store,
            transport: Transport([]),
            now: { [now] in now })
        try await upgraded.prepareForUse()

        let references = try await store.allReferences()
        XCTAssertEqual(references, ["ref-a"], "an install with accounts must not be purged")
        let accounts = await upgraded.usage()
        XCTAssertEqual(accounts.count, 1)
    }

    func testTheInstallCheckRunsOnceAndIsNotRepeatedAfterALogin() async throws {
        // The marker is what stops the purge running a second time. Without it, the next launch
        // would find no marker again and wipe the credentials the user has just signed in with.
        let store = InMemoryCredentialStore(credentials: [:])
        let (subject, _) = container([], credentials: store)
        try await subject.prepareForUse()

        try await subject.add(account())

        let relaunched = UsageLimitsContainer(
            directory: directory,
            credentials: store,
            transport: Transport([]),
            now: { [now] in now })
        try await relaunched.prepareForUse()

        let accounts = await relaunched.usage()
        XCTAssertEqual(accounts.count, 1, "the marker must survive to the next launch")
    }

    /// Refuses to delete, so the ordering inside `remove` can be observed.
    private actor RefusingCredentialStore: CredentialStore {
        private struct Refused: Error {}
        private var stored: [String: OAuthCredentials] = [:]

        func load(reference: String) async throws -> OAuthCredentials? { stored[reference] }
        func save(_ credentials: OAuthCredentials, reference: String) async throws {
            stored[reference] = credentials
        }
        func updateIfPresent(
            _ credentials: OAuthCredentials, reference: String
        ) async throws -> Bool {
            guard stored[reference] != nil else { return false }
            stored[reference] = credentials
            return true
        }
        func delete(reference: String) async throws { throw Refused() }
        func removeAll() async throws { throw Refused() }
        func allReferences() async throws -> [String] { Array(stored.keys) }
    }
}
