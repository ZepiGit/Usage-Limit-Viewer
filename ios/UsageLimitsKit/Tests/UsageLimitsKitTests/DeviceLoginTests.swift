import XCTest
@testable import UsageLimitsKit

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Signing in, which is the step that has to work before anything else in the app means
/// anything.
///
/// Every code and token below is synthetic. The flows are driven through a scripted transport,
/// with a pinned clock so a poll loop does not actually wait.
final class DeviceLoginTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private actor Transport: HTTPTransport {
        private var replies: [(status: Int, body: String)]
        private(set) var requests: [URLRequest] = []

        init(_ replies: [(status: Int, body: String)]) { self.replies = replies }

        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            requests.append(request)
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

    private func client(_ replies: [(status: Int, body: String)]) -> (UsageHTTPClient, Transport) {
        let transport = Transport(replies)
        return (UsageHTTPClient(transport: transport, maxRetries: 0, now: { [now] in now }),
                transport)
    }

    /// A JWT whose payload carries the given claims. Header and signature are placeholders —
    /// nothing here verifies a signature, deliberately and for reasons documented on JWTClaims.
    private func token(_ claims: [String: Any]) throws -> String {
        let payload = try JSONSerialization.data(withJSONObject: claims, options: [.sortedKeys])
        let encoded = payload.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "header.\(encoded).signature"
    }

    // MARK: - Which providers can sign in here at all

    func testTheTwoProvidersAPhoneCannotCompleteSayWhyRatherThanOfferAButton() {
        // Claude and Antigravity issue their code to a loopback address only a desktop listener
        // can receive. A button that starts a flow which cannot finish is worse than a sentence
        // explaining the constraint.
        XCTAssertNil(DeviceLoginSupport.unsupportedReason(for: .codex))
        XCTAssertNil(DeviceLoginSupport.unsupportedReason(for: .xai))
        XCTAssertNotNil(DeviceLoginSupport.unsupportedReason(for: .claude))
        XCTAssertNotNil(DeviceLoginSupport.unsupportedReason(for: .antigravity))
        XCTAssertEqual(DeviceLoginSupport.supported, [.codex, .xai])
    }

    // MARK: - JWT claims

    func testAPayloadThatIsNotAMultipleOfFourStillDecodes() throws {
        // base64url omits the padding Foundation's decoder demands. Almost no JWT payload is a
        // multiple of four characters, so getting this wrong reads as "every token is
        // unreadable" rather than as an edge case.
        let jwt = try token(["sub": "user-123", "email": "a@example.com"])

        let claims = JWTClaims.decode(jwt)

        XCTAssertEqual(JWTClaims.string(claims, "sub"), "user-123")
        XCTAssertEqual(JWTClaims.string(claims, "email"), "a@example.com")
    }

    func testSomethingThatIsNotAJWTIsNilRatherThanAGuess() {
        XCTAssertNil(JWTClaims.decode("not a jwt"))
        XCTAssertNil(JWTClaims.decode("only.two"))
        XCTAssertNil(JWTClaims.decode(nil))
    }

    // MARK: - Codex

    func testCodexShowsTheCodeAndKeepsTheDeviceIdOutOfSight() async throws {
        let (http, _) = client([
            (200, #"{"user_code": "ABCD-EFGH", "device_auth_id": "internal-id", "interval": 5}"#),
        ])

        let challenge = try await CodexDeviceLogin(httpClient: http, now: { [now] in now }).begin()

        XCTAssertEqual(challenge.userCode, "ABCD-EFGH")
        XCTAssertEqual(challenge.verificationURI, ProviderEndpoints.Codex.deviceVerificationURL)
        // The device id identifies the pending authorisation, not the user, and is never shown.
        XCTAssertFalse(challenge.userCode.contains("internal-id"))
        XCTAssertEqual(challenge.continuation["device_auth_id"], "internal-id")
    }

    func testCodexAcceptsEitherSpellingOfTheCodeField() async throws {
        // Upstream has shipped both.
        let (http, _) = client([
            (200, #"{"usercode": "WXYZ-1234", "deviceAuthId": "internal-id"}"#),
        ])

        let challenge = try await CodexDeviceLogin(httpClient: http, now: { [now] in now }).begin()

        XCTAssertEqual(challenge.userCode, "WXYZ-1234")
    }

    func testCodexNeverPollsFasterThanTheFloor() async throws {
        // A provider stating no interval, or a silly one, must not turn the poll into a hot loop
        // — that earns a ban rather than an answer.
        let (http, _) = client([(200, #"{"user_code": "A", "device_auth_id": "b", "interval": 0}"#)])

        let challenge = try await CodexDeviceLogin(httpClient: http, now: { [now] in now }).begin()

        XCTAssertEqual(challenge.pollInterval, DeviceLoginTiming.minimumPollInterval)
    }

    func testCodexExchangesTheApprovedCodeForTokens() async throws {
        let (http, transport) = client([
            (200, #"{"authorization_code": "approved-code", "code_verifier": "the-verifier"}"#),
            (200, #"{"access_token": "new-access", "refresh_token": "new-refresh", "expires_in": 3600}"#),
        ])
        let challenge = DeviceLoginChallenge(
            userCode: "ABCD", verificationURI: "https://example.invalid",
            expiresAt: now.addingTimeInterval(600), pollInterval: 0,
            continuation: ["device_auth_id": "d", "user_code": "ABCD"])

        let credentials = try await CodexDeviceLogin(httpClient: http, now: { [now] in now })
            .complete(challenge)

        XCTAssertEqual(credentials.accessToken, "new-access")
        XCTAssertEqual(credentials.refreshToken, "new-refresh")

        let sent = await transport.requests
        let exchange = try XCTUnwrap(sent.last)
        let body = String(data: exchange.httpBody ?? Data(), encoding: .utf8) ?? ""
        XCTAssertTrue(body.contains("grant_type=authorization_code"))
        XCTAssertTrue(body.contains("code_verifier=the-verifier"))
        // Must match what the device endpoint issued the code against, even though the app never
        // navigates there.
        XCTAssertTrue(body.contains("redirect_uri="))
    }

    func testCodexTreatsARejectionAsNotYetApproved() async throws {
        // OpenAI signals "waiting for the user" with 403/404 rather than an
        // `authorization_pending` body, so a failed status has to keep the loop alive — the
        // deadline is what ends it.
        let (http, _) = client([
            (403, "not yet"),
            (200, #"{"authorization_code": "approved-code", "code_verifier": "v"}"#),
            (200, #"{"access_token": "new-access"}"#),
        ])
        let challenge = DeviceLoginChallenge(
            userCode: "ABCD", verificationURI: "https://example.invalid",
            expiresAt: now.addingTimeInterval(600), pollInterval: 0,
            continuation: ["device_auth_id": "d", "user_code": "ABCD"])

        let credentials = try await CodexDeviceLogin(httpClient: http, now: { [now] in now })
            .complete(challenge)

        XCTAssertEqual(credentials.accessToken, "new-access")
    }

    func testCodexGivesUpWhenTheCodeExpires() async throws {
        let (http, _) = client([(403, "not yet")])
        let challenge = DeviceLoginChallenge(
            userCode: "ABCD", verificationURI: "https://example.invalid",
            // Already past: the loop must not run at all.
            expiresAt: now.addingTimeInterval(-1), pollInterval: 0,
            continuation: ["device_auth_id": "d", "user_code": "ABCD"])

        do {
            _ = try await CodexDeviceLogin(httpClient: http, now: { [now] in now })
                .complete(challenge)
            XCTFail("an expired code must not resolve")
        } catch let error as DeviceLoginError {
            XCTAssertEqual(error, .expired)
        }
    }

    func testCodexPrefersTheAccountIdOverTheSubject() async throws {
        // They are different things: the account id scopes a team plan's quota, the subject
        // identifies the user. Sending a subject as an account id asks for the wrong account.
        let (http, _) = client([])
        let jwt = try token([
            "sub": "user-123",
            "email": "a@example.com",
            "https://api.openai.com/auth": [
                "chatgpt_account_id": "acct-789", "chatgpt_plan_type": "plus",
            ],
        ])

        let profile = try await CodexDeviceLogin(httpClient: http, now: { [now] in now })
            .profile(OAuthCredentials(accessToken: "a", idToken: jwt))

        XCTAssertEqual(profile.externalAccountID, "acct-789")
        XCTAssertEqual(profile.plan, "plus")
        XCTAssertEqual(profile.attributes["chatgpt_account_id"], "acct-789")
    }

    func testCodexSendsNoAccountHeaderWhenTheProviderNamedNoAccount() async throws {
        // A personal plan states no account id, and the usage endpoint rejects the header
        // outright — so a JWT subject must not be smuggled in as one.
        let (http, _) = client([])
        let jwt = try token(["sub": "user-123"])

        let profile = try await CodexDeviceLogin(httpClient: http, now: { [now] in now })
            .profile(OAuthCredentials(accessToken: "a", idToken: jwt))

        XCTAssertEqual(profile.externalAccountID, "user-123")
        XCTAssertTrue(profile.attributes.isEmpty)
    }

    // MARK: - xAI

    private let discovery = """
    { "token_endpoint": "https://auth.x.ai/oauth2/token",
      "device_authorization_endpoint": "https://auth.x.ai/oauth2/device" }
    """

    func testXaiDiscoversItsEndpointsAndShowsTheCode() async throws {
        let (http, transport) = client([
            (200, discovery),
            (200, #"{"user_code": "WXYZ", "device_code": "dev-1", "verification_uri": "https://auth.x.ai/device", "interval": 5, "expires_in": 900}"#),
        ])

        let challenge = try await XaiDeviceLogin(httpClient: http, now: { [now] in now }).begin()

        XCTAssertEqual(challenge.userCode, "WXYZ")
        XCTAssertEqual(challenge.verificationURI, "https://auth.x.ai/device")
        XCTAssertEqual(challenge.expiresAt, now.addingTimeInterval(900))
        let sent = await transport.requests
        XCTAssertEqual(sent.first?.url?.absoluteString, ProviderEndpoints.Xai.discoveryURL)
    }

    func testXaiRefusesAVerificationPageOffTheIssuer() async throws {
        // This app is about to tell the user to sign in at that URL. A tampered discovery
        // response pointing at a phishing page the app has just vouched for is worse than a
        // failed login.
        let (http, _) = client([
            (200, discovery),
            (200, #"{"user_code": "WXYZ", "device_code": "dev-1", "verification_uri": "https://auth.evil.example/device"}"#),
        ])

        do {
            _ = try await XaiDeviceLogin(httpClient: http, now: { [now] in now }).begin()
            XCTFail("a verification page outside x.ai must be refused")
        } catch let error as ProviderError {
            guard case .malformedPayload = error else {
                return XCTFail("expected malformedPayload, got \(error)")
            }
        }
    }

    func testXaiKeepsWaitingWhileAuthorizationIsPending() async throws {
        let (http, _) = client([
            (200, #"{"error": "authorization_pending"}"#),
            (200, #"{"access_token": "new-access", "expires_in": 3600}"#),
        ])
        let challenge = DeviceLoginChallenge(
            userCode: "WXYZ", verificationURI: "https://auth.x.ai/device",
            expiresAt: now.addingTimeInterval(600), pollInterval: 0,
            continuation: ["device_code": "dev-1",
                           "token_endpoint": "https://auth.x.ai/oauth2/token"])

        let credentials = try await XaiDeviceLogin(httpClient: http, now: { [now] in now })
            .complete(challenge)

        XCTAssertEqual(credentials.accessToken, "new-access")
    }

    func testXaiStopsWhenTheUserDeclines() async throws {
        // A refusal is terminal. Continuing to poll after one wastes the user's time and the
        // provider's patience.
        let (http, _) = client([(200, #"{"error": "access_denied"}"#)])
        let challenge = DeviceLoginChallenge(
            userCode: "WXYZ", verificationURI: "https://auth.x.ai/device",
            expiresAt: now.addingTimeInterval(600), pollInterval: 0,
            continuation: ["device_code": "dev-1",
                           "token_endpoint": "https://auth.x.ai/oauth2/token"])

        do {
            _ = try await XaiDeviceLogin(httpClient: http, now: { [now] in now })
                .complete(challenge)
            XCTFail("a declined sign-in must not resolve")
        } catch let error as DeviceLoginError {
            XCTAssertEqual(error, .declined("the sign-in was declined"))
        }
    }

    func testXaiWillNotPollAnEndpointSmuggledIntoTheChallenge() async throws {
        // The challenge is a value that may have been held across a suspension, so the endpoint
        // it carries is re-checked rather than trusted.
        let (http, transport) = client([(200, #"{"access_token": "leaked"}"#)])
        let challenge = DeviceLoginChallenge(
            userCode: "WXYZ", verificationURI: "https://auth.x.ai/device",
            expiresAt: now.addingTimeInterval(600), pollInterval: 0,
            continuation: ["device_code": "dev-1",
                           "token_endpoint": "https://auth.evil.example/oauth2/token"])

        do {
            _ = try await XaiDeviceLogin(httpClient: http, now: { [now] in now })
                .complete(challenge)
            XCTFail("the device code must not be sent off the issuer")
        } catch is ProviderError {
            // expected
        }

        let sent = await transport.requests
        XCTAssertTrue(sent.isEmpty, "nothing should have been sent")
    }
}
