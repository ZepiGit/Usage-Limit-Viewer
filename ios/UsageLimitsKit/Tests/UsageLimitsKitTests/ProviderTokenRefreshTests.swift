import XCTest
@testable import UsageLimitsKit

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The token exchange, which is the half of a provider adapter that can lose an account.
///
/// A quota endpoint changing shape costs one refresh. A token endpoint mishandled costs the
/// sign-in: these providers retire the refresh token on use, so a request built wrongly, a
/// response read wrongly, or a rotated token dropped on the floor leaves the user with an
/// account that reads as revoked when nothing revoked it. Every token here is synthetic.
final class ProviderTokenRefreshTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private let stored = OAuthCredentials(
        accessToken: "old-access",
        refreshToken: "old-refresh",
        idToken: "old-id",
        expiresAt: Date(timeIntervalSince1970: 1_756_000_000),
        scope: "old scope")

    /// Replies from a script and keeps every request, so a test can assert on what went out —
    /// which for a token exchange matters as much as what came back.
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

    private func client(_ replies: [(status: Int, body: String)]) -> (UsageHTTPClient, Transport) {
        let transport = Transport(replies)
        return (UsageHTTPClient(transport: transport, maxRetries: 0, now: { [now] in now }),
                transport)
    }

    private func body(of request: URLRequest) -> String {
        String(data: request.httpBody ?? Data(), encoding: .utf8) ?? ""
    }

    /// A rotating response: a new access token AND a new refresh token, which is what these
    /// providers actually send.
    private let rotated = """
    { "access_token": "new-access", "refresh_token": "new-refresh",
      "id_token": "new-id", "expires_in": 3600, "scope": "granted scope" }
    """

    // MARK: - Reading the response

    func testARotatedPairIsRead() async throws {
        let (http, _) = client([(200, rotated)])

        let refreshed = try await CodexClient(httpClient: http, now: { [now] in now })
            .refresh(credentials: stored)

        XCTAssertEqual(refreshed.accessToken, "new-access")
        XCTAssertEqual(refreshed.refreshToken, "new-refresh")
        XCTAssertEqual(refreshed.idToken, "new-id")
        XCTAssertEqual(refreshed.scope, "granted scope")
    }

    func testExpiryIsStampedAgainstTheClockAtTheExchange() async throws {
        // `expires_in` is relative and starts decaying the moment it is stored. Recording it as
        // an absolute instant is what lets a later expiry check mean anything.
        let (http, _) = client([(200, rotated)])

        let refreshed = try await CodexClient(httpClient: http, now: { [now] in now })
            .refresh(credentials: stored)

        XCTAssertEqual(refreshed.expiresAt, now.addingTimeInterval(3_600))
    }

    func testAnOmittedRefreshTokenIsNilRatherThanInvented() async throws {
        // Nil is exactly what the response said: providers that do not rotate omit the field,
        // meaning "keep the one you have", and the engine's merge carries the stored token
        // forward. An adapter that filled the gap in would hide a provider that HAD rotated and
        // simply failed to say so.
        let (http, _) = client([(200, #"{"access_token": "new-access", "expires_in": 3600}"#)])

        let refreshed = try await CodexClient(httpClient: http, now: { [now] in now })
            .refresh(credentials: stored)

        XCTAssertNil(refreshed.refreshToken)
        XCTAssertEqual(stored.merging(refreshed: refreshed).refreshToken, "old-refresh")
    }

    func testAResponseWithoutAnAccessTokenIsMalformedRatherThanEmpty() async throws {
        let (http, _) = client([(200, #"{"token_type": "Bearer"}"#)])

        do {
            _ = try await CodexClient(httpClient: http, now: { [now] in now })
                .refresh(credentials: stored)
            XCTFail("a token response with no access token must not be accepted")
        } catch let error as ProviderError {
            guard case .malformedPayload = error else {
                return XCTFail("expected malformedPayload, got \(error)")
            }
        }
    }

    func testTheFailureMessageCarriesNoPayload() async throws {
        // These messages are shown on the account card. A token response is the one payload
        // that must never be echoed into one.
        let (http, _) = client([(200, #"{"secret_looking_field": "sk-should-never-appear"}"#)])

        do {
            _ = try await CodexClient(httpClient: http, now: { [now] in now })
                .refresh(credentials: stored)
            XCTFail("expected a failure")
        } catch {
            let message = (error as? LocalizedError)?.errorDescription ?? String(describing: error)
            XCTAssertFalse(message.contains("sk-should-never-appear"))
        }
    }

    // MARK: - Rejections

    func testAnAccountWithNoRefreshTokenFailsWithoutAskingTheNetwork() async throws {
        let (http, transport) = client([])
        let accessOnly = OAuthCredentials(accessToken: "only-access")

        do {
            _ = try await CodexClient(httpClient: http, now: { [now] in now })
                .refresh(credentials: accessOnly)
            XCTFail("there is nothing to exchange")
        } catch let error as ProviderError {
            guard case .unauthorised = error else {
                return XCTFail("expected unauthorised, got \(error)")
            }
        }

        let sent = await transport.requests
        XCTAssertTrue(sent.isEmpty)
    }

    func testASpentRefreshTokenReadsAsARejectionRatherThanAnOutage() async throws {
        // A token endpoint answers a spent, revoked or malformed refresh token with 400
        // `invalid_grant`, not 401. Reading that as "no data" would tell a user to wait for a
        // provider outage that is not happening, when the fix is to sign in again.
        let (http, _) = client([(400, #"{"error": "invalid_grant"}"#)])

        do {
            _ = try await CodexClient(httpClient: http, now: { [now] in now })
                .refresh(credentials: stored)
            XCTFail("expected a rejection")
        } catch let error as ProviderError {
            guard case .unauthorised = error else {
                return XCTFail("expected unauthorised, got \(error)")
            }
        }
    }

    func testARejectionSaysWhatTheUserCanDo() {
        // The engine renders the error onto the account card. Without a description a user is
        // told "unauthorised", which names an HTTP status rather than the remedy.
        //
        // Asserted against the CONSTANT rather than a literal copy of the sentence. This test
        // held its own spelling while the notification evaluator matched on a different one,
        // so it passed for months over a "reconnect this account" notification that could
        // never fire. A literal here is what let the two ends disagree unnoticed.
        XCTAssertEqual(
            ProviderError.unauthorised.errorDescription,
            NotificationEvaluator.signInExpiredMessage)
        XCTAssertFalse(
            NotificationEvaluator.signInExpiredMessage.isEmpty,
            "a user told nothing is a user told 'unauthorised'")
    }

    // MARK: - What each provider sends

    func testCodexAsksForTheRefreshScopeNotTheAuthorizeScope() async throws {
        // The refresh grant is rejected outright if it asks for more than it was granted, and
        // the quota endpoints need none of the device flow's extra scopes.
        let (http, transport) = client([(200, rotated)])

        _ = try await CodexClient(httpClient: http, now: { [now] in now })
            .refresh(credentials: stored)

        let sent = await transport.requests
        let request = try XCTUnwrap(sent.first)
        XCTAssertEqual(request.url?.absoluteString, ProviderEndpoints.Codex.tokenURL)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertTrue(body(of: request).contains("grant_type=refresh_token"))
        XCTAssertTrue(body(of: request).contains("refresh_token=old-refresh"))
        XCTAssertTrue(body(of: request).contains("client_id=\(ProviderEndpoints.Codex.clientID)"))
        // Spaces travel as `+`: this is form encoding, matching what the Android client sends
        // and therefore what this endpoint is known to accept.
        XCTAssertTrue(body(of: request).contains("scope=openid+profile+email"))
    }

    func testClaudeSendsJSONRatherThanFormEncoding() async throws {
        // Anthropic's token endpoint takes JSON, unlike the RFC 6749 form encoding the other
        // three use. Sending a form body here fails the exchange outright.
        let (http, transport) = client([(200, rotated)])

        _ = try await ClaudeClient(httpClient: http, now: { [now] in now })
            .refresh(credentials: stored)

        let sent = await transport.requests
        let request = try XCTUnwrap(sent.first)
        XCTAssertEqual(request.url?.absoluteString, ProviderEndpoints.Claude.tokenURL)
        let payload = try XCTUnwrap(
            JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: Any])
        XCTAssertEqual(payload["grant_type"] as? String, "refresh_token")
        XCTAssertEqual(payload["refresh_token"] as? String, "old-refresh")
        XCTAssertEqual(payload["client_id"] as? String, ProviderEndpoints.Claude.clientID)
    }

    func testGoogleKeepsTheOriginalRefreshTokenItNeverReturns() async throws {
        // Google issues no refresh token on a refresh grant; the original stays valid until it
        // is revoked. Storing the response as it stands would deauthenticate the account an
        // hour later, which looks exactly like a revocation.
        let (http, _) = client([(200, #"{"access_token": "new-access", "expires_in": 3599}"#)])

        let refreshed = try await AntigravityClient(httpClient: http, now: { [now] in now })
            .refresh(credentials: stored)

        XCTAssertEqual(refreshed.accessToken, "new-access")
        XCTAssertEqual(refreshed.refreshToken, "old-refresh")
        XCTAssertEqual(refreshed.idToken, "old-id")
    }

    // MARK: - xAI discovery

    private let discovery = """
    { "issuer": "https://auth.x.ai",
      "token_endpoint": "https://auth.x.ai/oauth2/token",
      "device_authorization_endpoint": "https://auth.x.ai/oauth2/device" }
    """

    func testXaiPostsToTheDiscoveredEndpoint() async throws {
        let (http, transport) = client([(200, discovery), (200, rotated)])

        let refreshed = try await XaiClient(httpClient: http, now: { [now] in now })
            .refresh(credentials: stored)

        XCTAssertEqual(refreshed.accessToken, "new-access")
        let sent = await transport.requests
        XCTAssertEqual(sent.count, 2)
        XCTAssertEqual(sent.first?.url?.absoluteString, ProviderEndpoints.Xai.discoveryURL)
        XCTAssertEqual(sent.last?.url?.absoluteString, "https://auth.x.ai/oauth2/token")
    }

    func testXaiRefusesADiscoveredEndpointOffTheIssuerBeforeSendingAnything() async throws {
        // The discovery document is network-supplied data naming a URL this app is about to
        // post a refresh token to. The check has to happen before the request, not after it:
        // afterwards the token is already gone.
        let (http, transport) = client([
            (200, #"{"token_endpoint": "https://auth.evil.example/oauth2/token"}"#),
        ])

        do {
            _ = try await XaiClient(httpClient: http, now: { [now] in now })
                .refresh(credentials: stored)
            XCTFail("a token endpoint outside x.ai must be refused")
        } catch let error as ProviderError {
            guard case .malformedPayload = error else {
                return XCTFail("expected malformedPayload, got \(error)")
            }
        }

        let sent = await transport.requests
        XCTAssertEqual(sent.count, 1, "only discovery should have been sent")
    }

    func testTheLookalikeHostsAreTheOnesTheAnchorExistsFor() throws {
        // A suffix check without the leading dot accepts `notx.ai`; one that does not anchor at
        // the end of the name accepts `x.ai.example.com`. Either sends the account's refresh
        // token to whoever registered the lookalike.
        XCTAssertEqual(
            try XaiClient.validated("https://auth.x.ai/oauth2/token"),
            "https://auth.x.ai/oauth2/token")
        XCTAssertEqual(try XaiClient.validated("https://x.ai/token"), "https://x.ai/token")

        for hostile in [
            "https://notx.ai/token",
            "https://x.ai.example.com/token",
            "https://evil.example/x.ai/token",
            "http://auth.x.ai/token",
        ] {
            XCTAssertThrowsError(try XaiClient.validated(hostile), hostile)
        }
    }

    func testCredentialsInADiscoveredURLAreRefused() {
        // `https://x.ai@evil.example` is already caught by the host check — the host is
        // evil.example — but `https://anything@auth.x.ai` passes it, and some HTTP stacks turn
        // that userinfo into a Basic-auth header on a request carrying a refresh token.
        XCTAssertThrowsError(try XaiClient.validated("https://someone@auth.x.ai/token"))
        XCTAssertThrowsError(try XaiClient.validated("https://a:b@auth.x.ai/token"))
    }

    func testTheCodeCarryingSignInPageKeepsItsQuery() {
        // `verification_uri_complete` is the sign-in page with the user's code already in it.
        // Stripping the query would turn the one URL that saves them typing into the one that
        // does not work.
        XCTAssertEqual(
            try XaiClient.validated("https://auth.x.ai/device?user_code=WXYZ"),
            "https://auth.x.ai/device?user_code=WXYZ")
    }

    func testTheRefusedEndpointIsNotEchoedBack() {
        // The rejected URL is attacker-controllable and this message reaches the account card.
        do {
            _ = try XaiClient.validated("https://phish.example/steal?note=marker-string")
            XCTFail("expected a refusal")
        } catch {
            let message = (error as? LocalizedError)?.errorDescription ?? String(describing: error)
            XCTAssertFalse(message.contains("marker-string"))
        }
    }

    // MARK: - Form encoding

    func testFormEncodingEscapesWhatWouldOtherwiseReshapeTheRequest() {
        let encoded = String(
            data: TokenExchange.formBody(["refresh_token": "a&b=c d+e", "client_id": "x"]),
            encoding: .utf8)

        // `&` and `=` inside a value would otherwise split it into extra parameters, and a
        // literal `+` would decode back as a space — a different token.
        XCTAssertEqual(encoded, "client_id=x&refresh_token=a%26b%3Dc+d%2Be")
    }
}
