import XCTest
@testable import UsageLimitsKit

/// The hash, the challenge derivation, and the two grant flows.
///
/// The SHA-256 here is hand-rolled, because CryptoKit does not exist on Linux and this package
/// must build there. That is a reasonable trade only if the implementation is checked against
/// the published vectors: a subtly wrong hash produces a well-formed challenge that every
/// provider rejects, and the failure surfaces as "login just doesn't work" with nothing to
/// point at.
///
/// No real credential appears here. Every value is either a published test vector or invented.
final class OAuthFlowsTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - SHA-256 known-answer vectors

    func testTheEmptyStringVector() {
        // FIPS 180-4 / NIST CAVP.
        XCTAssertEqual(
            hex(SHA256.hash([])),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    func testTheAbcVector() {
        XCTAssertEqual(
            hex(SHA256.hash(Array("abc".utf8))),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    func testTheTwoBlockVector() {
        // 56 bytes: the case that forces a second block, where a length-padding mistake shows.
        let input = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        XCTAssertEqual(
            hex(SHA256.hash(Array(input.utf8))),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
    }

    func testTheExactBlockBoundaryVector() {
        // Exactly 64 bytes. Padding must add a whole extra block here, and an off-by-one in the
        // "does the length fit" check lands precisely on this input.
        let input = String(repeating: "a", count: 64)
        XCTAssertEqual(
            hex(SHA256.hash(Array(input.utf8))),
            "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb")
    }

    func testTheFiftyFiveByteVector() {
        // 55 bytes is the largest input whose padding still fits in one block; 56 is the
        // smallest that does not. Both boundaries are covered.
        let input = String(repeating: "a", count: 55)
        XCTAssertEqual(
            hex(SHA256.hash(Array(input.utf8))),
            "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318")
    }

    func testAMillionAsVector() {
        // The long NIST vector, which exercises the multi-block loop properly.
        XCTAssertEqual(
            hex(SHA256.hash([UInt8](repeating: UInt8(ascii: "a"), count: 1_000_000))),
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")
    }

    // MARK: - PKCE

    func testTheChallengeIsTheBase64URLEncodedHashOfTheVerifier() {
        // RFC 7636 appendix B's worked example, which is the one value every provider's
        // implementation is also checked against.
        let verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        XCTAssertEqual(
            Base64URL.encode(SHA256.hash(Array(verifier.utf8))),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    func testAGeneratedVerifierMeetsTheLengthAndAlphabetRules() {
        // RFC 7636 §4.1: 43 to 128 characters from the unreserved set. A verifier outside that
        // is rejected by the authorisation server, not by us, so it fails at sign-in.
        let challenge = PKCE.generate(randomBytes: { count in
            (0..<count).map { UInt8($0 % 251) }
        })

        XCTAssertGreaterThanOrEqual(challenge.verifier.count, 43)
        XCTAssertLessThanOrEqual(challenge.verifier.count, 128)

        let unreserved = CharacterSet(charactersIn:
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        XCTAssertNil(challenge.verifier.rangeOfCharacter(from: unreserved.inverted))
        XCTAssertNil(challenge.challenge.rangeOfCharacter(from: unreserved.inverted))
    }

    func testTheChallengeMatchesItsOwnVerifier() {
        let challenge = PKCE.generate(randomBytes: { count in
            (0..<count).map { UInt8(($0 &* 7) % 251) }
        })

        XCTAssertEqual(
            challenge.challenge,
            Base64URL.encode(SHA256.hash(Array(challenge.verifier.utf8))))
        XCTAssertEqual(challenge.method, "S256")
    }

    func testTwoGenerationsDiffer() {
        // Not a randomness test — just that the generator actually consumes what it is given,
        // rather than returning a constant that would make every login interchangeable.
        var seed: UInt8 = 0
        let first = PKCE.generate(randomBytes: { count in
            seed &+= 1
            return (0..<count).map { _ in seed }
        })
        let second = PKCE.generate(randomBytes: { count in
            seed &+= 1
            return (0..<count).map { _ in seed }
        })

        XCTAssertNotEqual(first.verifier, second.verifier)
    }

    // MARK: - Base64URL

    func testBase64URLHasNoPaddingOrUnsafeCharacters() {
        for length in 1...8 {
            let encoded = Base64URL.encode([UInt8](repeating: 0xFB, count: length))
            XCTAssertFalse(encoded.contains("="))
            XCTAssertFalse(encoded.contains("+"))
            XCTAssertFalse(encoded.contains("/"))
        }
    }

    // MARK: - The redirect

    func testTheCodeIsReturnedWhenTheStateMatches() throws {
        let url = URL(string: "com.usagelimits://callback?code=abc123&state=xyz")!

        XCTAssertEqual(try OAuthFlows.code(from: url, expectedState: "xyz"), "abc123")
    }

    func testAMismatchedStateIsRejected() {
        // The whole point of `state`: without this check a crafted redirect can hand the app an
        // authorisation code belonging to somebody else's session.
        let url = URL(string: "com.usagelimits://callback?code=abc123&state=attacker")!

        XCTAssertThrowsError(try OAuthFlows.code(from: url, expectedState: "xyz")) { error in
            XCTAssertEqual(error as? OAuthCallbackError, .stateMismatch)
        }
    }

    func testAMissingStateIsRejectedRatherThanAccepted() {
        let url = URL(string: "com.usagelimits://callback?code=abc123")!

        XCTAssertThrowsError(try OAuthFlows.code(from: url, expectedState: "xyz"))
    }

    func testAProviderErrorIsSurfacedRatherThanReadAsAMissingCode() {
        let url = URL(string:
            "com.usagelimits://callback?error=access_denied&error_description=User+said+no&state=xyz")!

        XCTAssertThrowsError(try OAuthFlows.code(from: url, expectedState: "xyz")) { error in
            guard case .providerError(let code, _)? = error as? OAuthCallbackError else {
                return XCTFail("expected a provider error, got \(error)")
            }
            XCTAssertEqual(code, "access_denied")
        }
    }

    func testARedirectWithNeitherCodeNorErrorIsRejected() {
        let url = URL(string: "com.usagelimits://callback?state=xyz")!

        XCTAssertThrowsError(try OAuthFlows.code(from: url, expectedState: "xyz")) { error in
            XCTAssertEqual(error as? OAuthCallbackError, .missingCode)
        }
    }

    // MARK: - Device authorisation

    func testADeviceAuthorizationIsParsed() throws {
        let parsed = try XCTUnwrap(OAuthFlows.parseDeviceAuthorization([
            "device_code": "dev-123",
            "user_code": "WDJB-MJHT",
            "verification_uri": "https://example.com/device",
            "verification_uri_complete": "https://example.com/device?user_code=WDJB-MJHT",
            "expires_in": 900,
            "interval": 5,
        ], now: now))

        XCTAssertEqual(parsed.userCode, "WDJB-MJHT")
        XCTAssertEqual(parsed.expiresAt, now.addingTimeInterval(900))
        XCTAssertEqual(parsed.interval, 5)
    }

    func testAnAbsentIntervalDefaultsToFiveSeconds() throws {
        // RFC 8628 §3.2. Polling faster than the server allows earns a `slow_down` at best and
        // a ban at worst, so the default has to be the specified one.
        let parsed = try XCTUnwrap(OAuthFlows.parseDeviceAuthorization([
            "device_code": "dev-123",
            "user_code": "WDJB-MJHT",
            "verification_uri": "https://example.com/device",
            "expires_in": 900,
        ], now: now))

        XCTAssertEqual(parsed.interval, 5)
    }

    func testCamelCaseSpellingsAreAccepted() throws {
        // These providers serve both spellings depending on the endpoint.
        let parsed = try XCTUnwrap(OAuthFlows.parseDeviceAuthorization([
            "deviceCode": "dev-123",
            "userCode": "WDJB-MJHT",
            "verificationUri": "https://example.com/device",
            "expiresIn": 900,
        ], now: now))

        XCTAssertEqual(parsed.deviceCode, "dev-123")
    }

    func testAPayloadMissingTheDeviceCodeYieldsNothing() {
        XCTAssertNil(OAuthFlows.parseDeviceAuthorization([
            "user_code": "WDJB-MJHT",
            "verification_uri": "https://example.com/device",
        ], now: now))
    }

    // MARK: - Device polling

    func testPendingIsNotAFailure() {
        // The common case: the user has not finished signing in yet. Treating it as an error
        // would abandon a login the user is halfway through.
        XCTAssertEqual(
            OAuthFlows.interpretPoll(
                ["error": "authorization_pending"], now: now, currentInterval: 5),
            .pending)
    }

    func testSlowDownAddsFiveSecondsPerTheRFC() {
        XCTAssertEqual(
            OAuthFlows.interpretPoll(["error": "slow_down"], now: now, currentInterval: 5),
            .slowDown(newInterval: 10))
    }

    func testDeniedAndExpiredAreDistinctTerminalOutcomes() {
        // Different messages to the user: one means "you declined", the other "start again".
        XCTAssertEqual(
            OAuthFlows.interpretPoll(["error": "access_denied"], now: now, currentInterval: 5),
            .denied)
        XCTAssertEqual(
            OAuthFlows.interpretPoll(["error": "expired_token"], now: now, currentInterval: 5),
            .expired)
    }

    func testASuccessfulPollCarriesTheTokensAndAnAbsoluteExpiry() {
        let outcome = OAuthFlows.interpretPoll([
            "access_token": "synthetic-access",
            "refresh_token": "synthetic-refresh",
            "expires_in": 3_600,
        ], now: now, currentInterval: 5)

        guard case .success(let access, let refresh, let expiresAt) = outcome else {
            return XCTFail("expected success, got \(outcome)")
        }
        XCTAssertEqual(access, "synthetic-access")
        XCTAssertEqual(refresh, "synthetic-refresh")
        // Absolute, because a relative count starts decaying the moment it is stored.
        XCTAssertEqual(expiresAt, now.addingTimeInterval(3_600))
    }

    func testAnUnknownErrorIsAFailureRatherThanAnInfinitePoll() {
        let outcome = OAuthFlows.interpretPoll(
            ["error": "something_new"], now: now, currentInterval: 5)

        guard case .failure = outcome else {
            return XCTFail("expected a failure, got \(outcome)")
        }
    }
}
