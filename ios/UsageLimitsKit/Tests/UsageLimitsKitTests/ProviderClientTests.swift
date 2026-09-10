import XCTest
@testable import UsageLimitsKit

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The fetch-and-assemble layer, driven by the payloads a live account actually returned.
///
/// These go through a scripted transport rather than the network, but the bodies are the ones
/// captured from real endpoints — which is the point. Every defect this project has had was an
/// assumption about payload shape, and a client tested against a body I invented would confirm
/// my own assumption rather than the provider's behaviour.
///
/// Bodies are lightly trimmed and all identifiers are synthetic; no token appears anywhere.
final class ProviderClientTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private let credentials = OAuthCredentials(
        accessToken: "synthetic-access", refreshToken: "synthetic-refresh")

    /// Replies from a script, and records what was asked.
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

    // MARK: - Codex

    /// The `/wham/usage` body a live Plus account returned, trimmed.
    private let codexUsage = """
    { "plan_type": "plus",
      "rate_limit": { "allowed": false, "limit_reached": true,
        "primary_window": { "used_percent": 100, "limit_window_seconds": 604800,
                            "reset_after_seconds": 469200, "reset_at": 1789457449 },
        "secondary_window": null },
      "additional_rate_limits": null,
      "rate_limit_reset_credits": { "available_count": 2, "applicable_available_count": 0 } }
    """

    /// The dedicated credits endpoint, which carries different fields from the embedded copy.
    private let codexCredits = """
    { "credits": [], "available_count": 2, "total_earned_count": 3,
      "immediate_reset_purchase_eligible": false, "history_enabled": true }
    """

    func testCodexReadsEachCountFromTheSourceThatCarriesIt() async throws {
        // The two sources are not the same payload. The dedicated endpoint reports `credits`
        // and `available_count` and NO applicable count; the copy embedded in the usage payload
        // reports both counts and no `credits` array. Reading the applicable count off
        // whichever answered left the redeem button ungated exactly when the authoritative call
        // succeeded.
        let (http, _) = client([(200, codexUsage), (200, codexCredits)])

        let result = try await CodexClient(httpClient: http)
            .fetchUsage(credentials: credentials, attributes: [:])

        XCTAssertEqual(result.resetCreditCount, 2)
        XCTAssertEqual(result.applicableResetCreditCount, 0)
        XCTAssertEqual(result.plan, "Plus")
    }

    func testCodexClassifiesAWeeklyWindowInThePrimarySlot() async throws {
        // The live payload's primary window declares 604800 seconds with a null secondary.
        // Reading by position would label a week as a five-hour limit.
        let (http, _) = client([(200, codexUsage), (200, codexCredits)])

        let result = try await CodexClient(httpClient: http)
            .fetchUsage(credentials: credentials, attributes: [:])

        XCTAssertEqual(result.windows.count, 1)
        XCTAssertEqual(result.windows.first?.category, .weekly)
        XCTAssertEqual(result.windows.first?.resetAt, Date(timeIntervalSince1970: 1_789_457_449))
    }

    func testAFailedCreditsCallCostsTheCreditRowsNotTheRefresh() async throws {
        // The whole usage refresh must survive the optional second call failing; the embedded
        // copy is what keeps a count on screen.
        let (http, _) = client([(200, codexUsage), (500, "upstream is unwell")])

        let result = try await CodexClient(httpClient: http)
            .fetchUsage(credentials: credentials, attributes: [:])

        XCTAssertEqual(result.windows.count, 1)
        XCTAssertEqual(result.resetCreditCount, 2)
    }

    func testTheAccountHeaderIsSentOnlyWhenThereIsOne() async throws {
        let (withID, withIDTransport) = client([(200, codexUsage), (200, codexCredits)])
        _ = try await CodexClient(httpClient: withID)
            .fetchUsage(credentials: credentials, attributes: ["chatgpt_account_id": "acct-123"])

        let (without, withoutTransport) = client([(200, codexUsage), (200, codexCredits)])
        _ = try await CodexClient(httpClient: without)
            .fetchUsage(credentials: credentials, attributes: [:])

        let sentWith = await withIDTransport.requests
        let sentWithout = await withoutTransport.requests
        XCTAssertEqual(
            sentWith.first?.value(forHTTPHeaderField: "chatgpt-account-id"), "acct-123")
        // Sending an empty header is not the same as sending none; some endpoints treat a blank
        // value as a request for an account that does not exist.
        XCTAssertNil(sentWithout.first?.value(forHTTPHeaderField: "chatgpt-account-id"))
    }

    func testARejectedCredentialIsDistinguishableSoItCanBeRepaired() async {
        let (http, _) = client([(401, "unauthorised")])

        do {
            _ = try await CodexClient(httpClient: http)
                .fetchUsage(credentials: credentials, attributes: [:])
            XCTFail("expected a rejection")
        } catch let error as ProviderError {
            guard case .unauthorised = error else { return XCTFail("wrong error: \(error)") }
        } catch {
            XCTFail("wrong error: \(error)")
        }
    }

    // MARK: - Claude

    func testClaudeSendsTheBetaHeaderThatGatesTheEndpoint() async throws {
        // Without it these paths 404, which reads as "the endpoint moved" rather than "you
        // forgot a header".
        let (http, transport) = client([(200, #"{"five_hour":{"utilization":21.0}}"#)])

        _ = try await ClaudeClient(httpClient: http)
            .fetchUsage(credentials: credentials, attributes: [:])

        let sent = await transport.requests
        XCTAssertEqual(
            sent.first?.value(forHTTPHeaderField: "anthropic-beta"), "oauth-2025-04-20")
    }

    // MARK: - Antigravity

    func testAnEmptyAnswerFromOneHostTriesTheNext() async throws {
        // An empty 2xx means this host does not serve the account, not that the account has no
        // quota. Returning there would render zero windows, which the model reads as an error.
        let (http, transport) = client([
            (200, #"{"groups":[]}"#),
            (200, """
            { "groups": [ { "displayName": "Gemini Models", "buckets": [
                { "bucketId": "gemini-weekly", "window": "weekly",
                  "resetTime": "2026-09-10T18:54:44Z", "remainingFraction": 0.53 } ] } ] }
            """),
        ])

        let result = try await AntigravityClient(httpClient: http)
            .fetchUsage(credentials: credentials, attributes: ["project_id": "aicode-consumers"])

        XCTAssertEqual(result.windows.count, 1)
        let attempts = await transport.requests.count
        XCTAssertEqual(attempts, 2)
    }

    func testEveryHostAnsweringEmptyIsReportedRatherThanShownAsNoQuota() async {
        let (http, _) = client([(200, #"{"groups":[]}"#), (200, #"{"groups":[]}"#),
                                (200, #"{"groups":[]}"#), (200, #"{"groups":[]}"#)])

        do {
            _ = try await AntigravityClient(httpClient: http)
                .fetchUsage(credentials: credentials, attributes: ["project_id": "p"])
            XCTFail("expected the empty answer to be reported")
        } catch let error as ProviderError {
            guard case .noData = error else { return XCTFail("wrong error: \(error)") }
        } catch {
            XCTFail("wrong error: \(error)")
        }
    }

    func testAMissingProjectIsAnErrorRatherThanARequestForNothing() async {
        let (http, _) = client([])

        do {
            _ = try await AntigravityClient(httpClient: http)
                .fetchUsage(credentials: credentials, attributes: [:])
            XCTFail("expected a missing-project error")
        } catch let error as ProviderError {
            guard case .noData = error else { return XCTFail("wrong error: \(error)") }
        } catch {
            XCTFail("wrong error: \(error)")
        }
    }

    // MARK: - xAI

    func testXaiMergesBothBillingViews() async throws {
        // Captured shapes: the enum period type and `{"val": n}` cents.
        let (http, _) = client([
            (200, """
            { "config": { "currentPeriod": { "type": "USAGE_PERIOD_TYPE_WEEKLY",
                                             "start": "2026-09-03T18:32:26Z",
                                             "end": "2026-09-10T18:32:26Z" },
                          "creditUsagePercent": 64.0, "onDemandCap": { "val": 0 } } }
            """),
            (200, """
            { "config": { "monthlyLimit": { "val": 1000 }, "used": { "val": 341 },
                          "onDemandCap": { "val": 0 } } }
            """),
        ])

        let result = try await XaiClient(httpClient: http)
            .fetchUsage(credentials: credentials, attributes: [:])

        let ids = Set(result.windows.map(\.id))
        XCTAssertTrue(ids.contains("xai-credits"))
        XCTAssertTrue(ids.contains("xai-monthly"))
        // A zero on-demand cap is not a window.
        XCTAssertFalse(ids.contains("xai-on-demand"))
    }

    func testOneXaiViewSucceedingIsStillAResult() async throws {
        // Reporting a failure because the second of two optional views was unavailable would
        // blank an account whose numbers the app actually has.
        let (http, _) = client([
            (200, #"{"config":{"creditUsagePercent":64.0}}"#),
            (503, "unavailable"),
        ])

        let result = try await XaiClient(httpClient: http)
            .fetchUsage(credentials: credentials, attributes: [:])

        XCTAssertFalse(result.windows.isEmpty)
    }

    func testBothXaiViewsFailingIsReported() async {
        let (http, _) = client([(503, "unavailable"), (503, "unavailable")])

        do {
            _ = try await XaiClient(httpClient: http)
                .fetchUsage(credentials: credentials, attributes: [:])
            XCTFail("expected a failure when neither view answered")
        } catch {
            XCTAssertFalse("\(error)".contains("synthetic-access"))
        }
    }
}
