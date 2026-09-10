import XCTest
@testable import UsageLimitsKit

/// The refresh path, which is the most expensive thing in this codebase to get wrong.
///
/// These providers ROTATE the refresh token on use: spending one invalidates the previous one,
/// and presenting a spent one commonly makes the provider revoke the whole grant. So a second,
/// concurrent refresh does not merely waste a call — it can cost the user access to a paid
/// account, with no local corruption to point at and no way back but signing in again.
///
/// Every credential here is invented.
final class SyncEngineTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func account(_ id: String, reference: String? = nil) -> ProviderAccount {
        ProviderAccount(
            id: id,
            provider: .codex,
            externalAccountID: "ext-\(id)",
            email: "\(id)@example.com",
            displayName: nil,
            plan: nil,
            credentialReference: reference ?? "ref-\(id)",
            createdAt: Date(timeIntervalSince1970: 0),
            lastSuccessfulSync: nil)
    }

    private func expired(_ token: String) -> OAuthCredentials {
        OAuthCredentials(
            accessToken: token,
            refreshToken: "refresh-1",
            expiresAt: Date(timeIntervalSince1970: 0))
    }

    /// Counts exchanges and models rotation: presenting a spent refresh token is fatal.
    private actor RotatingProvider: SyncProvider {
        nonisolated let providerID = "codex"

        private var liveRefreshToken = "refresh-1"
        private var generation = 1
        private(set) var exchanges = 0
        private(set) var fetches = 0
        var rejectFetchUntilRefreshed = false
        /// A provider that does not rotate: its response omits the refresh token entirely.
        var omitsRefreshTokenInResponse = false

        struct GrantRevoked: Error {}

        func refresh(credentials: OAuthCredentials) async throws -> OAuthCredentials {
            exchanges += 1
            guard credentials.refreshToken == liveRefreshToken else {
                // What a real provider does with a reused token: the whole grant goes.
                throw GrantRevoked()
            }
            generation += 1
            rejectFetchUntilRefreshed = false

            if omitsRefreshTokenInResponse {
                return OAuthCredentials(
                    accessToken: "access-\(generation)",
                    refreshToken: nil,
                    expiresAt: Date(timeIntervalSince1970: 4_000_000_000))
            }

            liveRefreshToken = "refresh-\(generation)"
            return OAuthCredentials(
                accessToken: "access-\(generation)",
                refreshToken: liveRefreshToken,
                expiresAt: Date(timeIntervalSince1970: 4_000_000_000))
        }

        func fetchUsage(
            credentials: OAuthCredentials,
            attributes: [String: String]
        ) async throws -> UsageResult {
            fetches += 1
            if rejectFetchUntilRefreshed { throw ProviderError.unauthorised }
            return UsageResult(
                windows: [], resetCredits: [], resetCreditCount: nil,
                applicableResetCreditCount: nil, plan: nil)
        }

        func setRejectingUntilRefreshed() { rejectFetchUntilRefreshed = true }
        func setOmittingRefreshToken() { omitsRefreshTokenInResponse = true }
    }

    /// Rotates like the one above, but its response never states an expiry — the case that
    /// exposed the inherited-expiry loop.
    private actor SilentExpiryProvider: SyncProvider {
        nonisolated let providerID = "codex"
        private var liveRefreshToken = "refresh-1"
        private var generation = 1
        private(set) var exchanges = 0

        struct GrantRevoked: Error {}

        func refresh(credentials: OAuthCredentials) async throws -> OAuthCredentials {
            exchanges += 1
            guard credentials.refreshToken == liveRefreshToken else { throw GrantRevoked() }
            generation += 1
            liveRefreshToken = "refresh-\(generation)"
            // No `expires_in` in the response, which is a shape real endpoints do serve.
            return OAuthCredentials(
                accessToken: "access-\(generation)", refreshToken: liveRefreshToken)
        }

        func fetchUsage(
            credentials: OAuthCredentials, attributes: [String: String]
        ) async throws -> UsageResult {
            UsageResult()
        }
    }

    /// Accepts every read and refuses every write, so a rotation can be made to succeed while
    /// the pair that came out of it cannot be stored.
    private actor UnwritableCredentialStore: CredentialStore {
        struct Unwritable: Error {}
        private var stored: [String: OAuthCredentials]
        private(set) var attemptedSaves = 0
        /// Counted separately from `save` so a test can prove WHICH write the engine used.
        private(set) var attemptedUpdates = 0

        init(_ stored: [String: OAuthCredentials]) { self.stored = stored }

        func load(reference: String) async throws -> OAuthCredentials? { stored[reference] }
        func save(_ credentials: OAuthCredentials, reference: String) async throws {
            attemptedSaves += 1
            throw Unwritable()
        }
        /// Refuses exactly as `save` does, and counts the same way: the engine persists
        /// rotations through this method now, and the point of the double is that no write
        /// of either kind can succeed.
        func updateIfPresent(
            _ credentials: OAuthCredentials, reference: String
        ) async throws -> Bool {
            attemptedSaves += 1
            attemptedUpdates += 1
            throw Unwritable()
        }
        func delete(reference: String) async throws {}
        func removeAll() async throws {}
        func allReferences() async throws -> [String] { Array(stored.keys) }
    }

    private actor RecordingSink: SyncSink {
        private(set) var outcomes: [SyncOutcome] = []
        func record(_ outcome: SyncOutcome, at time: Date) async { outcomes.append(outcome) }
    }

    private func engine(
        provider: RotatingProvider,
        store: InMemoryCredentialStore,
        sink: RecordingSink
    ) -> SyncEngine {
        SyncEngine(
            providers: ["codex": provider],
            credentials: store,
            sink: sink,
            now: { [now] in now })
    }

    // MARK: - The rotation race

    func testTwoAccountsSharingACredentialExchangeOnlyOnce() async throws {
        // The reason the lock is keyed by credential reference and not by account: two accounts
        // can point at one credential, and both refreshing means one presents a spent token.
        let store = InMemoryCredentialStore(credentials: ["shared": expired("access-1")])
        let provider = RotatingProvider()
        let sink = RecordingSink()

        let outcomes = try await engine(provider: provider, store: store, sink: sink).sync(
            accounts: [
                account("a", reference: "shared"),
                account("b", reference: "shared"),
            ])

        let exchanges = await provider.exchanges
        XCTAssertEqual(exchanges, 1)
        XCTAssertEqual(outcomes.count, 2)
        for outcome in outcomes {
            guard case .success = outcome else { return XCTFail("expected success: \(outcome)") }
        }
    }

    func testTheSecondCallerReReadsRatherThanTrustingWhatItLoaded() async throws {
        // A caller that loaded the credential before waiting must not exchange again on the
        // strength of that stale copy — that is exactly the double refresh that strands an
        // account. It re-reads, finds the pair the first exchange saved, and uses it.
        let store = InMemoryCredentialStore(credentials: ["shared": expired("access-1")])
        let provider = RotatingProvider()

        _ = try await engine(provider: provider, store: store, sink: RecordingSink()).sync(
            accounts: [
                account("a", reference: "shared"),
                account("b", reference: "shared"),
                account("c", reference: "shared"),
            ])

        let exchanges = await provider.exchanges
        XCTAssertEqual(exchanges, 1)
        let saved = try await store.load(reference: "shared")
        XCTAssertEqual(saved?.accessToken, "access-2")
    }

    func testTheRefreshedPairIsPersistedBeforeItIsUsed() async throws {
        // If the process dies between the exchange and the fetch, the old refresh token is
        // already dead. A pair that was never written back loses the account outright.
        let store = InMemoryCredentialStore(credentials: ["ref-a": expired("access-1")])
        let provider = RotatingProvider()

        _ = try await engine(provider: provider, store: store, sink: RecordingSink())
            .sync(accounts: [account("a")])

        let saved = try await store.load(reference: "ref-a")
        XCTAssertEqual(saved?.accessToken, "access-2")
        XCTAssertEqual(saved?.refreshToken, "refresh-2")
    }

    // MARK: - What a refresh response may omit

    func testAProviderThatDoesNotRotateKeepsItsRefreshToken() async throws {
        // Providers that do not rotate simply omit `refresh_token` from the response. Storing
        // that response as-is deletes the token the account depends on — the account is lost
        // although nothing revoked it, which looks exactly like a revocation.
        let store = InMemoryCredentialStore(credentials: ["ref-a": expired("access-1")])
        let provider = RotatingProvider()
        await provider.setOmittingRefreshToken()

        _ = try await engine(provider: provider, store: store, sink: RecordingSink())
            .sync(accounts: [account("a")])

        let saved = try await store.load(reference: "ref-a")
        XCTAssertEqual(saved?.accessToken, "access-2")
        XCTAssertEqual(saved?.refreshToken, "refresh-1")
    }

    // MARK: - When to exchange at all

    func testAValidCredentialIsNotExchanged() async throws {
        // Refreshing a live token spends a rotation for nothing, and with these providers a
        // spent rotation is not free.
        let store = InMemoryCredentialStore(credentials: [
            "ref-a": OAuthCredentials(
                accessToken: "access-1", refreshToken: "refresh-1",
                expiresAt: Date(timeIntervalSince1970: 4_000_000_000)),
        ])
        let provider = RotatingProvider()

        _ = try await engine(provider: provider, store: store, sink: RecordingSink())
            .sync(accounts: [account("a")])

        let exchanges = await provider.exchanges
        XCTAssertEqual(exchanges, 0)
    }

    func testAnUnknownExpiryIsNotRefreshedProactively() async throws {
        // Several of these providers return no `expires_in`. Treating that as expired would
        // burn a rotation on every single sync.
        let store = InMemoryCredentialStore(credentials: [
            "ref-a": OAuthCredentials(
                accessToken: "access-1", refreshToken: "refresh-1", expiresAt: nil),
        ])
        let provider = RotatingProvider()

        _ = try await engine(provider: provider, store: store, sink: RecordingSink())
            .sync(accounts: [account("a")])

        let exchanges = await provider.exchanges
        XCTAssertEqual(exchanges, 0)
    }

    func testARejectionTriggersTheExchangeThatTheClockNeverWould() async throws {
        // The other half of that policy, and what makes the conservative half safe. An account
        // whose provider never states an expiry would otherwise 401 for ever, silently: the
        // proactive branch can never fire for it. The provider saying "this token is dead" is
        // better evidence than any clock.
        let store = InMemoryCredentialStore(credentials: [
            "ref-a": OAuthCredentials(
                accessToken: "access-1", refreshToken: "refresh-1", expiresAt: nil),
        ])
        let provider = RotatingProvider()
        await provider.setRejectingUntilRefreshed()

        let outcomes = try await engine(provider: provider, store: store, sink: RecordingSink())
            .sync(accounts: [account("a")])

        let exchanges = await provider.exchanges
        XCTAssertEqual(exchanges, 1)
        guard case .success = outcomes.first else {
            return XCTFail("expected the retry to succeed: \(String(describing: outcomes.first))")
        }
    }

    // MARK: - Isolation

    func testOneAccountFailingDoesNotAffectAnother() async throws {
        // A batch is not a transaction. One provider being unwell must not blank every other
        // account on the screen.
        let store = InMemoryCredentialStore(credentials: ["ref-b": expired("access-1")])
        let provider = RotatingProvider()

        // "a" has no stored credential at all; "b" does.
        let outcomes = try await engine(provider: provider, store: store, sink: RecordingSink())
            .sync(accounts: [account("a"), account("b")])

        XCTAssertEqual(outcomes.count, 2)
        guard case .failure(let failedID, _) = outcomes[0] else {
            return XCTFail("expected the first account to fail: \(outcomes[0])")
        }
        XCTAssertEqual(failedID, "a")
        guard case .success(let okID, _) = outcomes[1] else {
            return XCTFail("expected the second account to succeed: \(outcomes[1])")
        }
        XCTAssertEqual(okID, "b")
    }

    func testAnUnknownProviderFailsOnlyItsOwnAccount() async throws {
        let store = InMemoryCredentialStore(credentials: ["ref-a": expired("access-1")])
        let engine = SyncEngine(
            providers: [:], credentials: store, sink: RecordingSink(), now: { [now] in now })

        let outcomes = try await engine.sync(accounts: [account("a")])

        guard case .failure = outcomes.first else {
            return XCTFail("expected a failure: \(String(describing: outcomes.first))")
        }
    }

    func testEveryOutcomeReachesTheSink() async throws {
        let store = InMemoryCredentialStore(credentials: ["ref-b": expired("access-1")])
        let sink = RecordingSink()

        _ = try await engine(provider: RotatingProvider(), store: store, sink: sink)
            .sync(accounts: [account("a"), account("b")])

        let recorded = await sink.outcomes.count
        XCTAssertEqual(recorded, 2)
    }

    // MARK: - What must never leak

    func testAFailureMessageCarriesNoTokenMaterial() async throws {
        let store = InMemoryCredentialStore(credentials: [
            "ref-a": OAuthCredentials(
                accessToken: "synthetic-secret-access",
                refreshToken: "synthetic-secret-refresh",
                expiresAt: Date(timeIntervalSince1970: 0)),
        ])
        let provider = RotatingProvider()
        // A stored refresh token the provider does not recognise: the grant is revoked.
        let outcomes = try await engine(provider: provider, store: store, sink: RecordingSink())
            .sync(accounts: [account("a")])

        guard case .failure(_, let message) = outcomes.first else {
            return XCTFail("expected a failure: \(String(describing: outcomes.first))")
        }
        XCTAssertFalse(message.contains("synthetic-secret-access"))
        XCTAssertFalse(message.contains("synthetic-secret-refresh"))
    }
}

// MARK: - What a refresh response that states no expiry must not cause

extension SyncEngineTests {

    func testAResponseWithNoExpiryDoesNotRefreshOnEverySync() async throws {
        // The stored pair is expired and the provider's response omits `expires_in`. Carrying
        // the old expiry forward would stamp a dead timestamp onto a brand-new access token, so
        // the very next sync would judge it expired and exchange again — burning a rotation per
        // sync, for ever, over a field the provider simply did not mention.
        let provider = SilentExpiryProvider()
        let store = InMemoryCredentialStore(credentials: [
            "ref-a": OAuthCredentials(
                accessToken: "access-1", refreshToken: "refresh-1",
                expiresAt: Date(timeIntervalSince1970: 0)),
        ])
        let engine = SyncEngine(
            providers: ["codex": provider], credentials: store,
            sink: RecordingSink(), now: { [now] in now })

        _ = try await engine.sync(accounts: [account("a")])
        _ = try await engine.sync(accounts: [account("a")])
        _ = try await engine.sync(accounts: [account("a")])

        let exchanges = await provider.exchanges
        XCTAssertEqual(exchanges, 1, "an unstated expiry must not mean 'expired'")
    }

    func testARotationThatCannotBeStoredStillCompletesTheSync() async throws {
        // By the time the save runs, the OLD refresh token is already dead: a failed write does
        // not leave things as they were, it loses the account. Throwing would discard the only
        // working pair in existence and turn a storage fault into a signed-out paid account
        // within the same second. The sign-out is deferred to the next launch instead.
        let provider = RotatingProvider()
        let store = UnwritableCredentialStore([
            "ref-a": OAuthCredentials(
                accessToken: "access-1", refreshToken: "refresh-1",
                expiresAt: Date(timeIntervalSince1970: 0)),
        ])
        let sink = RecordingSink()
        let engine = SyncEngine(
            providers: ["codex": provider], credentials: store, sink: sink,
            now: { [now] in now })

        let outcomes = try await engine.sync(accounts: [account("a")])

        guard case .success = outcomes[0] else {
            return XCTFail("an unwritable store must not fail the account: \(outcomes[0])")
        }
        // Retried rather than surrendered on the first refusal, since the plausible causes are
        // momentary and the cost of not writing is the whole account.
        let attempts = await store.attemptedSaves
        XCTAssertGreaterThan(attempts, 1)

        // And every one of them went through the update-only path. `save` is an upsert: were
        // the engine still persisting rotations with it, a refresh completing after the user
        // removed the account would put the deleted credential back.
        let updates = await store.attemptedUpdates
        XCTAssertEqual(updates, attempts, "rotations must persist update-only, never upsert")
    }

    // MARK: - A removal must beat a refresh that is already running

    func testRotationDoesNotRecreateACredentialThatWasRemoved() async throws {
        // The defect: `persist` used `save`, an upsert. A refresh that began before the user
        // removed an account finished afterwards and wrote its rotated pair back, inserting
        // the credential the removal had just deleted. The account was gone from the list
        // while a working token stayed in the keychain, referenced by nothing and cleaned up
        // by nothing — the opposite of what the user asked for when they removed it.
        let store = InMemoryCredentialStore(credentials: [:])

        let recreated = try await store.updateIfPresent(expired("access-9"), reference: "ref-gone")

        XCTAssertFalse(recreated, "an absent reference must not be brought back")
        let references = try await store.allReferences()
        XCTAssertEqual(references, [], "update-only must never insert")
    }

    func testUpdateIfPresentReplacesWhatIsAlreadyThere() async throws {
        // The other half of the contract: when the account still exists, a rotation must land.
        // A method that never wrote anything would pass the test above and lose every token.
        let store = InMemoryCredentialStore(credentials: ["ref-a": expired("access-1")])

        let updated = try await store.updateIfPresent(expired("access-2"), reference: "ref-a")

        XCTAssertTrue(updated)
        let stored = try await store.load(reference: "ref-a")
        XCTAssertEqual(stored?.accessToken, "access-2")
    }
}
