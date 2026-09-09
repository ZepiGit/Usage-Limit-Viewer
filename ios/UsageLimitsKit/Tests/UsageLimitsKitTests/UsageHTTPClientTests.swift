import XCTest
@testable import UsageLimitsKit

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A transport that answers from a script instead of a network.
///
/// The whole reason `HTTPTransport` exists: retry behaviour, backoff and URL admission are the
/// parts of this client that can be wrong in a way nobody notices, and none of them can be
/// tested against a real endpoint without making the test flaky.
private actor ScriptedTransport: HTTPTransport {

    enum Step {
        case respond(status: Int, body: String, headers: [String: String] = [:])
        case fail(Error)
    }

    private var steps: [Step]
    private(set) var sent: [URLRequest] = []

    init(_ steps: [Step]) { self.steps = steps }

    var attempts: Int { sent.count }

    func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
        sent.append(request)
        let step = steps.isEmpty ? .respond(status: 200, body: "{}") : steps.removeFirst()

        switch step {
        case .fail(let error):
            throw error
        case .respond(let status, let body, let headers):
            let response = HTTPURLResponse(
                url: request.url ?? URL(string: "https://example.invalid")!,
                statusCode: status,
                httpVersion: "HTTP/1.1",
                headerFields: headers)!
            return (Data(body.utf8), response)
        }
    }
}

private struct TransportFailure: Error {}

final class UsageHTTPClientTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func client(
        _ steps: [ScriptedTransport.Step],
        maxRetries: Int = 2
    ) -> (UsageHTTPClient, ScriptedTransport) {
        let transport = ScriptedTransport(steps)
        let client = UsageHTTPClient(
            transport: transport,
            maxRetries: maxRetries,
            now: { [now] in now })
        return (client, transport)
    }

    // MARK: - What may be dialled at all

    func testPlainHTTPIsRefused() async {
        let (client, _) = client([])

        do {
            _ = try await client.request(url: "http://api.example.com/usage")
            XCTFail("plain http must not be dialled")
        } catch let error as HTTPError {
            guard case .insecureURL = error else { return XCTFail("wrong error: \(error)") }
        } catch {
            XCTFail("wrong error: \(error)")
        }
    }

    func testALoopbackQueryParameterDoesNotLaunderAnInsecureURL() async {
        // The reason the check reads the parsed host and not the raw string: a substring match
        // on "localhost" would wave this through to an attacker-controlled host in the clear.
        let (client, _) = client([])

        do {
            _ = try await client.request(url: "http://evil.example/?x=http://localhost")
            XCTFail("a loopback-looking query must not admit an external host")
        } catch let error as HTTPError {
            guard case .insecureURL = error else { return XCTFail("wrong error: \(error)") }
        } catch {
            XCTFail("wrong error: \(error)")
        }
    }

    func testALoopbackUserinfoDecoyDoesNotLaunderAnInsecureURL() async {
        // `http://localhost@evil.example/` reaches evil.example. Anything reading the string
        // left-to-right sees "localhost" first.
        let (client, _) = client([])

        do {
            _ = try await client.request(url: "http://localhost@evil.example/")
            XCTFail("userinfo must not be mistaken for the host")
        } catch let error as HTTPError {
            guard case .insecureURL = error else { return XCTFail("wrong error: \(error)") }
        } catch {
            XCTFail("wrong error: \(error)")
        }
    }

    func testLoopbackHTTPIsAllowedForTheOAuthReceiver() async throws {
        // The redirect receiver binds a plain http listener on the device itself; that traffic
        // never leaves the handset, and there is no way to give it a certificate.
        let (client, _) = client([.respond(status: 200, body: "ok")])

        let response = try await client.request(url: "http://127.0.0.1:1455/auth/callback")

        XCTAssertEqual(response.status, 200)
    }

    func testAnUnparseableTargetIsRejected() async {
        let (client, _) = client([])

        do {
            _ = try await client.request(url: "not a url at all")
            XCTFail("an unparseable target must not be dialled")
        } catch let error as HTTPError {
            switch error {
            case .invalidURL, .insecureURL: break
            default: XCTFail("wrong error: \(error)")
            }
        } catch {
            XCTFail("wrong error: \(error)")
        }
    }

    // MARK: - Retrying only what retrying can fix

    func testAFailedAttemptIsRetriedAndCanSucceed() async throws {
        let (client, transport) = client([
            .fail(TransportFailure()),
            .respond(status: 200, body: #"{"ok":true}"#),
        ])

        let response = try await client.request(url: "https://api.example.com/usage")

        XCTAssertEqual(response.status, 200)
        let attempts = await transport.attempts
        XCTAssertEqual(attempts, 2)
    }

    func testAServerErrorIsRetried() async throws {
        let (client, transport) = client([
            .respond(status: 503, body: "unavailable"),
            .respond(status: 200, body: "ok"),
        ])

        _ = try await client.request(url: "https://api.example.com/usage")

        let attempts = await transport.attempts
        XCTAssertEqual(attempts, 2)
    }

    func testAClientErrorIsNotRetried() async {
        // A 404 or a 400 will fail identically on the second attempt. Retrying it spends the
        // budget that a genuinely transient failure needed.
        let (client, transport) = client([
            .respond(status: 404, body: "no such thing"),
            .respond(status: 200, body: "ok"),
        ])

        do {
            _ = try await client.request(url: "https://api.example.com/usage")
            XCTFail("a 404 must surface rather than be retried into a success")
        } catch {
            let attempts = await transport.attempts
            XCTAssertEqual(attempts, 1)
        }
    }

    func testTheRetryBudgetIsFinite() async {
        let (client, transport) = client(
            [.fail(TransportFailure()), .fail(TransportFailure()), .fail(TransportFailure()),
             .fail(TransportFailure()), .fail(TransportFailure())],
            maxRetries: 2)

        do {
            _ = try await client.request(url: "https://api.example.com/usage")
            XCTFail("a permanently failing transport must eventually give up")
        } catch {
            // One attempt plus two retries.
            let attempts = await transport.attempts
            XCTAssertEqual(attempts, 3)
        }
    }

    func testNoRetriesMeansExactlyOneAttempt() async {
        let (client, transport) = client([.fail(TransportFailure())], maxRetries: 0)

        do {
            _ = try await client.request(url: "https://api.example.com/usage")
            XCTFail("expected the failure to surface")
        } catch {
            let attempts = await transport.attempts
            XCTAssertEqual(attempts, 1)
        }
    }

    // MARK: - Retry-After, which the server controls

    func testAnAbsurdRetryAfterDoesNotParkTheApp() async {
        // A header saying 86400 must not hold a background refresh for a day. The value is
        // attacker-influenced input, so it is bounded rather than obeyed.
        let (client, _) = client(
            [.respond(status: 429, body: "slow down", headers: ["Retry-After": "86400"])],
            maxRetries: 0)

        let started = Date()
        do {
            _ = try await client.request(url: "https://api.example.com/usage")
            XCTFail("expected a rate-limited failure")
        } catch let error as HTTPError {
            guard case .rateLimited(let retryAfter) = error else {
                return XCTFail("wrong error: \(error)")
            }
            // Whatever is reported back is already bounded, so a caller acting on the hint
            // cannot be talked into a day-long wait either.
            if let retryAfter { XCTAssertLessThanOrEqual(retryAfter, 30) }
        } catch {
            XCTFail("wrong error: \(error)")
        }
        XCTAssertLessThan(Date().timeIntervalSince(started), 10)
    }

    func testA429SurfacesAsRateLimitedRatherThanAsAPlainStatus() async {
        let (client, _) = client([.respond(status: 429, body: "slow down")], maxRetries: 0)

        do {
            _ = try await client.request(url: "https://api.example.com/usage")
            XCTFail("expected a rate-limited failure")
        } catch let error as HTTPError {
            guard case .rateLimited = error else { return XCTFail("wrong error: \(error)") }
        } catch {
            XCTFail("wrong error: \(error)")
        }
    }

    // MARK: - What an error may carry

    func testAnErrorBodyIsTruncated() async {
        // The body of a failed request can be large and is not ours. It is carried for
        // diagnosis, so it is bounded; request headers, which hold the bearer token, are never
        // carried at all.
        let (client, _) = client(
            [.respond(status: 400, body: String(repeating: "x", count: 10_000))],
            maxRetries: 0)

        do {
            _ = try await client.request(url: "https://api.example.com/usage")
            XCTFail("expected a status failure")
        } catch let error as HTTPError {
            guard case .status(_, let body) = error else { return XCTFail("wrong error: \(error)") }
            XCTAssertLessThan(body.count, 10_000)
        } catch {
            XCTFail("wrong error: \(error)")
        }
    }

    func testTheAuthorizationHeaderNeverAppearsInAnError() async {
        let (client, _) = client([.respond(status: 401, body: "unauthorised")], maxRetries: 0)

        do {
            _ = try await client.request(
                url: "https://api.example.com/usage",
                headers: ["Authorization": "Bearer synthetic-token-value"])
            XCTFail("expected a status failure")
        } catch {
            XCTAssertFalse("\(error)".contains("synthetic-token-value"))
        }
    }

    // MARK: - The request itself

    func testHeadersAndMethodReachTheTransport() async throws {
        let (client, transport) = client([.respond(status: 200, body: "ok")])

        _ = try await client.request(
            url: "https://api.example.com/usage",
            method: "POST",
            headers: ["X-Test": "value"],
            body: Data("{}".utf8))

        let sent = await transport.sent
        XCTAssertEqual(sent.first?.httpMethod, "POST")
        XCTAssertEqual(sent.first?.value(forHTTPHeaderField: "X-Test"), "value")
        XCTAssertEqual(sent.first?.httpBody, Data("{}".utf8))
    }

    func testResponseHeadersAreReturned() async throws {
        let (client, _) = client([
            .respond(status: 200, body: "ok", headers: ["X-Rate-Remaining": "42"]),
        ])

        let response = try await client.request(url: "https://api.example.com/usage")

        XCTAssertEqual(response.body, "ok")
        XCTAssertTrue(response.headers.contains { $0.value == "42" })
    }
}
