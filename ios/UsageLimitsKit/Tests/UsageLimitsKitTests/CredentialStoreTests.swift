import XCTest
@testable import UsageLimitsKit

/// The expiry rule and the in-memory store, both of which run on Linux.
///
/// The keychain implementation cannot be exercised here — it needs a real Security framework
/// and a provisioned device — so what is tested is the part that decides *when* a refresh is
/// attempted, which is where a wrong answer is expensive: too eager burns a rotating refresh
/// token on every call, too lax leaves the app making requests with a dead one.
///
/// No test here contains a real token. Every value is invented.
final class CredentialStoreTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func credentials(expiresAt: Date?) -> OAuthCredentials {
        OAuthCredentials(
            accessToken: "synthetic-access",
            refreshToken: "synthetic-refresh",
            idToken: nil,
            expiresAt: expiresAt,
            scope: "openid email")
    }

    // MARK: - Expiry

    func testAnUnknownExpiryIsNotTreatedAsExpired() {
        // Several of these providers return no `expires_in` at all. Treating that as expired
        // would refresh on every single call — and because refresh tokens rotate on use, a
        // needless refresh is not free: it invalidates the token the app is holding.
        XCTAssertFalse(credentials(expiresAt: nil).isExpired(now: now))
    }

    func testATokenIsExpiredOnceItsInstantHasPassed() {
        XCTAssertTrue(credentials(expiresAt: now.addingTimeInterval(-1)).isExpired(now: now))
    }

    func testTheLeewayRefreshesJustBeforeTheDeadline() {
        // A token that expires in thirty seconds will very likely be dead by the time the
        // request it is attached to reaches the provider.
        let almostDue = credentials(expiresAt: now.addingTimeInterval(30))

        XCTAssertTrue(almostDue.isExpired(now: now))
        XCTAssertFalse(almostDue.isExpired(now: now, leeway: 0))
    }

    func testAComfortablyValidTokenIsLeftAlone() {
        XCTAssertFalse(credentials(expiresAt: now.addingTimeInterval(3_600)).isExpired(now: now))
    }

    // MARK: - The in-memory store

    func testSavingThenLoadingReturnsWhatWentIn() async throws {
        let store = InMemoryCredentialStore()
        let saved = credentials(expiresAt: now.addingTimeInterval(3_600))

        try await store.save(saved, reference: "codex-a")

        let loaded = try await store.load(reference: "codex-a")
        XCTAssertEqual(loaded, saved)
    }

    func testSavingTwiceReplacesRatherThanFailing() async throws {
        // Every token refresh is an overwrite. A store that rejected the second write would
        // strand the account on the credentials it had at sign-in.
        let store = InMemoryCredentialStore()
        try await store.save(credentials(expiresAt: now), reference: "codex-a")

        let rotated = OAuthCredentials(
            accessToken: "synthetic-rotated",
            refreshToken: "synthetic-refresh-2",
            expiresAt: now.addingTimeInterval(3_600))
        try await store.save(rotated, reference: "codex-a")

        let loaded = try await store.load(reference: "codex-a")
        XCTAssertEqual(loaded, rotated)
    }

    func testAMissingReferenceLoadsAsNilRatherThanThrowing() async throws {
        let store = InMemoryCredentialStore()

        let missing = try await store.load(reference: "never-saved")
        XCTAssertNil(missing)
    }

    func testDeletingSomethingAbsentSucceeds() async throws {
        // Sign-out runs this after a failed sign-in as readily as after a good one, and a
        // throw there would leave the account half-removed.
        let store = InMemoryCredentialStore()

        try await store.delete(reference: "never-saved")
    }

    func testDeletingRemovesOnlyTheNamedReference() async throws {
        let store = InMemoryCredentialStore()
        try await store.save(credentials(expiresAt: now), reference: "codex-a")
        try await store.save(credentials(expiresAt: now), reference: "claude-b")

        try await store.delete(reference: "codex-a")

        let deleted = try await store.load(reference: "codex-a")
        let kept = try await store.load(reference: "claude-b")
        XCTAssertNil(deleted)
        XCTAssertNotNil(kept)
    }

    func testReferencesComeBackInAStableOrder() async throws {
        // Sorted rather than in insertion order, so a caller cannot come to depend on an order
        // that a restart would not reproduce.
        let store = InMemoryCredentialStore()
        for reference in ["xai-c", "codex-a", "claude-b"] {
            try await store.save(credentials(expiresAt: now), reference: reference)
        }

        let references = try await store.allReferences()
        XCTAssertEqual(references, ["claude-b", "codex-a", "xai-c"])
    }

    func testTheSeedingInitialiserBuildsAWholeStoreAtOnce() async throws {
        let store = InMemoryCredentialStore(
            credentials: ["codex-a": credentials(expiresAt: now.addingTimeInterval(60))])

        let references = try await store.allReferences()
        XCTAssertEqual(references, ["codex-a"])
    }

    // MARK: - What must never leak

    func testAStoreErrorCarriesNoTokenMaterial() {
        // The only failure the store can report is a status code and a codec failure. Neither
        // can carry a token, which is what makes it safe to surface an error to the UI.
        let failure = CredentialStoreError.encodingFailed

        XCTAssertFalse("\(failure)".contains("synthetic"))
    }
}
