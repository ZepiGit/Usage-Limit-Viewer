import XCTest
@testable import UsageLimitsKit

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Signing in to the two providers that redirect to loopback.
///
/// The request this builds is the one the user's browser is sent to, so the parts that matter
/// are which endpoint, which redirect, and whether the parameters that decide long-term access
/// are present. Every value below is synthetic.
final class LoopbackLoginTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private actor Transport: HTTPTransport {
        private var replies: [(status: Int, body: String)]
        private(set) var requests: [URLRequest] = []

        init(_ replies: [(status: Int, body: String)]) { self.replies = replies }

        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            requests.append(request)
            let reply: (status: Int, body: String) =
                replies.isEmpty ? (status: 200, body: "{}") : replies.removeFirst()
            let response = HTTPURLResponse(
                url: request.url ?? URL(string: "https://example.invalid")!,
                statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: [:])!
            return (Data(reply.body.utf8), response)
        }
    }

    private func login(
        _ provider: ProviderID,
        replies: [(status: Int, body: String)] = []
    ) -> (LoopbackLogin, Transport) {
        let transport = Transport(replies)
        let http = UsageHTTPClient(transport: transport, maxRetries: 0, now: { [now] in now })
        return (LoopbackLogin(provider: provider, httpClient: http, now: { [now] in now }),
                transport)
    }

    // MARK: - The redirect each provider registered

    func testEachRedirectSplitsIntoAPortAndAPathTheListenerCanUse() throws {
        let claude = try XCTUnwrap(LoopbackLogin.redirect(for: .claude))
        XCTAssertEqual(claude.host, "localhost")
        XCTAssertEqual(claude.port, 54545)
        XCTAssertEqual(claude.path, "/callback")

        let antigravity = try XCTUnwrap(LoopbackLogin.redirect(for: .antigravity))
        XCTAssertEqual(antigravity.port, 51121)
        XCTAssertEqual(antigravity.path, "/oauth-callback")
    }

    func testTheDeviceCodeProvidersHaveNoLoopbackRedirect() {
        XCTAssertNil(LoopbackLogin.redirect(for: .codex))
        XCTAssertNil(LoopbackLogin.redirect(for: .xai))
    }

    // MARK: - The URL the user's browser is sent to

    func testTheSignInURLPointsAtTheProvidersOwnPage() throws {
        let (subject, _) = login(.claude)

        let challenge = try subject.begin()

        let url = challenge.url.absoluteString
        XCTAssertTrue(url.hasPrefix(ProviderEndpoints.Claude.authorizeURL))
        XCTAssertTrue(url.contains("response_type=code"))
        XCTAssertTrue(url.contains("code_challenge_method=S256"))
        XCTAssertEqual(challenge.port, 54545)
    }

    func testGoogleIsAskedForOfflineAccessAndConsent() throws {
        // Google issues a refresh token only when both are asked for. Without them the account
        // works for one hour and then signs itself out, which reads as the app being broken.
        let (subject, _) = login(.antigravity)

        let url = try subject.begin().url.absoluteString

        XCTAssertTrue(url.contains("access_type=offline"))
        XCTAssertTrue(url.contains("prompt=consent"))
    }

    func testTwoAttemptsNeverShareAVerifierOrAState() throws {
        // A verifier reused across attempts turns PKCE into decoration, and a reused state
        // makes the anti-CSRF check meaningless.
        let (subject, _) = login(.claude)

        let first = try subject.begin()
        let second = try subject.begin()

        XCTAssertNotEqual(first.verifier, second.verifier)
        XCTAssertNotEqual(first.state, second.state)
    }

    // MARK: - The exchange

    func testClaudeExchangesTheCodeAsJSON() async throws {
        // Anthropic's token endpoint takes JSON, not the form encoding RFC 6749 specifies.
        let (subject, transport) = login(
            .claude,
            replies: [(200, #"{"access_token":"acc","refresh_token":"ref","expires_in":3600}"#)])
        let challenge = try subject.begin()

        let credentials = try await subject.exchange(code: "the-code", challenge: challenge)

        XCTAssertEqual(credentials.accessToken, "acc")
        let sent = await transport.requests
        let request = try XCTUnwrap(sent.first)
        XCTAssertEqual(request.url?.absoluteString, ProviderEndpoints.Claude.tokenURL)
        let payload = try XCTUnwrap(
            JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: Any])
        XCTAssertEqual(payload["grant_type"] as? String, "authorization_code")
        XCTAssertEqual(payload["code"] as? String, "the-code")
        // Must match the redirect the code was issued against, byte for byte.
        XCTAssertEqual(payload["redirect_uri"] as? String, ProviderEndpoints.Claude.redirectURI)
        XCTAssertEqual(payload["code_verifier"] as? String, challenge.verifier)
    }

    func testGoogleExchangesTheCodeAsAForm() async throws {
        let (subject, transport) = login(
            .antigravity,
            replies: [(200, #"{"access_token":"acc","refresh_token":"ref","expires_in":3600}"#)])
        let challenge = try subject.begin()

        _ = try await subject.exchange(code: "the-code", challenge: challenge)

        let sent = await transport.requests
        let body = String(data: try XCTUnwrap(sent.first?.httpBody), encoding: .utf8) ?? ""
        XCTAssertTrue(body.contains("grant_type=authorization_code"))
        XCTAssertTrue(body.contains("code=the-code"))
        XCTAssertTrue(body.contains("code_verifier=\(challenge.verifier)"))
    }

    func testADeviceCodeProviderRefusesTheRedirectFlow() async throws {
        let (subject, _) = login(.codex)
        let (claude, _) = login(.claude)
        let challenge = try claude.begin()

        do {
            _ = try await subject.exchange(code: "x", challenge: challenge)
            XCTFail("Codex does not sign in by redirect")
        } catch let error as DeviceLoginError {
            guard case .unsupportedOnThisPlatform = error else {
                return XCTFail("expected unsupportedOnThisPlatform, got \(error)")
            }
        }
    }

    // MARK: - Who the account belongs to

    func testClaudeProfilePrefersTheStableIdentifier() async throws {
        // The address can change while the account does not, and two accounts can share one.
        let (subject, _) = login(
            .claude,
            replies: [(200, #"{"account":{"uuid":"acct-1","email":"a@example.com"},"organization":{"rate_limit_tier":"default_claude_max_5x"}}"#)])

        let profile = try await subject.profile(OAuthCredentials(accessToken: "a"))

        XCTAssertEqual(profile.externalAccountID, "acct-1")
        XCTAssertEqual(profile.email, "a@example.com")
        XCTAssertEqual(profile.plan, "Max 5×")
    }

    func testAProfileNamingNoAccountIsARefusalRatherThanABlankRow() async throws {
        let (subject, _) = login(.antigravity, replies: [(200, "{}")])

        do {
            _ = try await subject.profile(OAuthCredentials(accessToken: "a"))
            XCTFail("an account with no identifier cannot be stored")
        } catch let error as DeviceLoginError {
            guard case .malformedResponse = error else {
                return XCTFail("expected malformedResponse, got \(error)")
            }
        }
    }

    // MARK: - Reading a callback the browser handed back

    func testTheChallengeReadsItsOwnCallback() throws {
        // The interception path: the sign-in session may complete with the callback URL rather
        // than the redirect ever being issued. The challenge does the reading, so the caller
        // never handles the state and cannot get the comparison wrong.
        let (subject, _) = login(.claude)
        let challenge = try subject.begin()
        let state = challenge.url.absoluteString
            .components(separatedBy: "state=")[1]
            .components(separatedBy: "&")[0]

        let callback = try XCTUnwrap(
            URL(string: "http://localhost:54545/callback?code=the-code&state=\(state)"))

        XCTAssertEqual(try challenge.code(fromCallback: callback), "the-code")
    }

    func testACallbackFromAnotherAttemptIsRefused() throws {
        let (subject, _) = login(.claude)
        let challenge = try subject.begin()
        let callback = try XCTUnwrap(
            URL(string: "http://localhost:54545/callback?code=the-code&state=someone-elses"))

        XCTAssertThrowsError(try challenge.code(fromCallback: callback))
    }
}
