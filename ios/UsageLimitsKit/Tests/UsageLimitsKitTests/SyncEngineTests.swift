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
