import XCTest
@testable import UsageLimitsKit

/// The one HTTP request this app ever answers.
///
/// It arrives on 127.0.0.1 over plain http, on a port any local process can reach, carrying an
/// authorisation code that is worth a paid subscription. Everything here is about refusing the
/// requests that are not the provider's redirect while accepting the one that is.
final class LoopbackRedirectTests: XCTestCase {

    private let path = "/callback"
    private let state = "the-state-we-issued"

    private func interpret(_ request: String) -> LoopbackRedirect.Outcome {
        LoopbackRedirect.interpret(
            request: request, expectedPath: path, expectedState: state)
    }

    private func get(_ target: String) -> String {
        "GET \(target) HTTP/1.1\r\nHost: 127.0.0.1:54545\r\nAccept: */*\r\n\r\n"
    }

    // MARK: - The request we are waiting for

    func testTheProvidersRedirectYieldsItsCode() {
        let outcome = interpret(get("/callback?code=the-code&state=\(state)"))

        XCTAssertEqual(outcome, .code("the-code"))
    }

    func testParameterOrderDoesNotMatter() {
        let outcome = interpret(get("/callback?state=\(state)&code=the-code"))

        XCTAssertEqual(outcome, .code("the-code"))
    }

    // MARK: - Requests that are not it

    func testAWrongStateIsIgnoredSoAStrangerCannotEndTheSignIn() {
        // The anti-CSRF check: a code from an attempt this app did not start is not this user's
        // code. Ignored rather than failed, and the distinction is the security property —
        // every process on the device shares 127.0.0.1, so ending the sign-in on the first
        // request that does not match hands any local process a way to break a sign-in the user
        // is halfway through, just by connecting first.
        XCTAssertEqual(interpret(get("/callback?code=the-code&state=someone-elses")), .ignored)
    }

    func testAMissingStateIsIgnored() {
        XCTAssertEqual(interpret(get("/callback?code=the-code")), .ignored)
    }

    func testTheCorrectCodeStillArrivesAfterAStrangerHasConnected() {
        // The behaviour the two above exist for, stated end to end.
        XCTAssertEqual(interpret(get("/callback?code=x&state=someone-elses")), .ignored)
        XCTAssertEqual(interpret(get("/favicon.ico")), .ignored)
        XCTAssertEqual(
            interpret(get("/callback?code=the-code&state=\(state)")), .code("the-code"))
    }

    // MARK: - Where the request claimed to be going

    func testARequestAddressedElsewhereIsIgnored() {
        // A page in the user's browser can be made to resolve a name to 127.0.0.1 and issue
        // requests at this port. The state check already stops it being mistaken for the
        // redirect; a request that never claimed to be addressed here is not examined at all.
        let rebound = "GET /callback?code=c&state=\(state) HTTP/1.1\r\n"
            + "Host: evil.example\r\n\r\n"

        XCTAssertEqual(
            LoopbackRedirect.interpret(
                request: rebound, expectedPath: path, expectedState: state, expectedPort: 54545),
            .ignored)
    }

    func testEitherSpellingOfLoopbackIsAccepted() {
        for host in ["127.0.0.1:54545", "localhost:54545", "LOCALHOST:54545"] {
            let request = "GET /callback?code=the-code&state=\(state) HTTP/1.1\r\n"
                + "Host: \(host)\r\n\r\n"

            XCTAssertEqual(
                LoopbackRedirect.interpret(
                    request: request, expectedPath: path, expectedState: state,
                    expectedPort: 54545),
                .code("the-code"),
                "\(host) should be accepted")
        }
    }

    func testAProviderErrorIsSurfacedByItsCodeAlone() {
        // The description is attacker-influenceable text arriving over plain http, and this
        // string is rendered. The code is enough to say what happened.
        let outcome = interpret(get(
            "/callback?error=access_denied&error_description=%3Cscript%3E&state=\(state)"))

        guard case .rejected(let message) = outcome else {
            return XCTFail("a provider error must be refused, got \(outcome)")
        }
        XCTAssertTrue(message.contains("access_denied"))
        XCTAssertFalse(message.contains("script"))
    }

    func testAFaviconFetchIsIgnoredRatherThanFailed() {
        // A browser asks for this unprompted. Treating it as a failed sign-in would end the
        // attempt before the user had finished it.
        XCTAssertEqual(interpret(get("/favicon.ico")), .ignored)
    }

    func testARequestToAnotherPathIsIgnored() {
        XCTAssertEqual(interpret(get("/something-else?code=x&state=\(state)")), .ignored)
    }

    func testANonGetRequestIsIgnored() {
        // Anything local can connect to this port. Only the shape the provider actually sends
        // is worth reading.
        XCTAssertEqual(
            interpret("POST /callback HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"), .ignored)
    }

    func testGarbageIsIgnoredRatherThanCrashing() {
        XCTAssertEqual(interpret(""), .ignored)
        XCTAssertEqual(interpret("\r\n\r\n"), .ignored)
        XCTAssertEqual(interpret("not http at all"), .ignored)
        XCTAssertEqual(interpret("GET"), .ignored)
    }

    // MARK: - What the browser is left showing

    func testTheSuccessPageNamesNothingSecret() {
        let response = LoopbackRedirect.successResponse()

        XCTAssertTrue(response.hasPrefix("HTTP/1.1 200 OK"))
        XCTAssertTrue(response.contains("Content-Length:"))
        // Served over plain http and kept in the browser's history.
        XCTAssertFalse(response.lowercased().contains("code"))
        XCTAssertFalse(response.contains(state))
    }

    func testTheResponseDeclaresItsOwnLengthAndClosesTheConnection() {
        // Without both, the browser sits waiting for bytes that never come and the user is left
        // looking at a spinner on a sign-in that already succeeded.
        let response = LoopbackRedirect.successResponse()
        let parts = response.components(separatedBy: "\r\n\r\n")

        XCTAssertEqual(parts.count, 2)
        XCTAssertTrue(response.contains("Connection: close"))
        XCTAssertEqual(
            parts[1].utf8.count,
            Int(response
                .components(separatedBy: "Content-Length: ")[1]
                .components(separatedBy: "\r\n")[0])!)
    }

    func testTheFailurePageEscapesWhatItShows() {
        let response = LoopbackRedirect.failureResponse(message: "<script>alert(1)</script>")

        XCTAssertFalse(response.contains("<script>"))
        XCTAssertTrue(response.contains("&lt;script&gt;"))
    }
}
