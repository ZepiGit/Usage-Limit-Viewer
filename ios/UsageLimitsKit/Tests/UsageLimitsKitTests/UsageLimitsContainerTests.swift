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
        XCTAssertEqual(usage.first?.account.plan, "plus")
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

    /// Refuses to delete, so the ordering inside `remove` can be observed.
    private actor RefusingCredentialStore: CredentialStore {
        private struct Refused: Error {}
        private var stored: [String: OAuthCredentials] = [:]

        func load(reference: String) async throws -> OAuthCredentials? { stored[reference] }
        func save(_ credentials: OAuthCredentials, reference: String) async throws {
            stored[reference] = credentials
        }
        func delete(reference: String) async throws { throw Refused() }
        func removeAll() async throws { throw Refused() }
        func allReferences() async throws -> [String] { Array(stored.keys) }
    }
}
