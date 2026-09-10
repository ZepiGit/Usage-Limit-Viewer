import Foundation

// The fetching layer of the usage readers: each adapter pulls raw JSON from one provider's
// undocumented endpoints and hands it to the matching parser. None of these endpoints is a
// published contract, so every adapter is written to sacrifice a section of the result rather
// than discard the whole refresh when an endpoint changes shape, disappears or starts
// refusing the token.

// MARK: - Result model

/// Everything a single provider refresh could salvage.
///
/// Empty and nil fields mean "the endpoint did not say", which callers must show as unknown
/// rather than as zero; a refresh that loses one section still returns the sections it kept.
public struct UsageResult: Sendable {
    /// The quota windows the provider reported. An empty list is a valid answer — nothing to
    /// show — as distinct from a failure, which throws instead of returning.
    public let windows: [UsageWindow]

    /// Rate-limit reset credits, for the providers that expose them; only Codex does.
    public let resetCredits: [ResetCredit]

    /// How many of those credits the provider still counts as available, when it publishes a
    /// figure at all. Nil means "no count was available"; zero means "none left".
    public let resetCreditCount: Int?

    /// The subset of credits the provider considers applicable right now, with the same
    /// nil-versus-zero distinction as `resetCreditCount`.
    public let applicableResetCreditCount: Int?

    /// The plan name the provider reported, if it reports one.
    public let plan: String?

    public init(
        windows: [UsageWindow] = [],
        resetCredits: [ResetCredit] = [],
        resetCreditCount: Int? = nil,
        applicableResetCreditCount: Int? = nil,
        plan: String? = nil
    ) {
        self.windows = windows
        self.resetCredits = resetCredits
        self.resetCreditCount = resetCreditCount
        self.applicableResetCreditCount = applicableResetCreditCount
        self.plan = plan
    }
}

// MARK: - Provider contract

// A provider adapter conforms to `SyncProvider` (see `Sync/SyncEngine.swift`), which is the one
// contract: read usage, and exchange a refresh token. There is deliberately no second,
// fetch-only protocol. There was one, and the split cost nothing but bugs — the engine could
// only be handed something that refreshes, so a "usage client" that did not was a type that
// compiled and could never be wired in. The exchange half of each adapter lives in
// `ProviderTokenRefresh.swift`.
//
// Implementations prefer shrinking the result to throwing: only a rejected credential or a
// total absence of answers justifies an error.

// MARK: - Errors

/// Why a refresh could not produce a result at all.
///
/// `LocalizedError`, because these messages are what an account card shows when a sync fails.
/// Without it the engine falls back to `String(describing:)` and a user reading their own screen
/// is told "unauthorised", which names the HTTP status rather than the thing they can do about
/// it. Every description is built from endpoint names and status codes the app chose; no payload,
/// header or token is ever interpolated into one.
public enum ProviderError: Error, LocalizedError {
    /// The endpoint answered, but not with a JSON object any parser can consume.
    case malformedPayload(String)
    /// The credential was rejected (HTTP 401/403); refreshing the token is the only fix the
    /// caller can make.
    case unauthorised
    /// The endpoint was unreachable, answered with an error status, or had nothing for this
    /// account.
    case noData(String)

    public var errorDescription: String? {
        switch self {
        case .malformedPayload(let detail):
            return "The provider answered in a shape this app does not understand (\(detail))."
        case .unauthorised:
            return "This account needs signing in again."
        case .noData(let detail):
            return "No usage could be read (\(detail))."
        }
    }
}

// MARK: - Shared HTTP plumbing

/// Status and body checks shared by every adapter.
///
/// Diagnostics carry only endpoint names and HTTP status codes — never a header, token or
/// account identifier — because these errors end up in logs and user interfaces.
enum ProviderHTTP {
    /// Enforces the one rule every endpoint shares, that only a 2xx body may be parsed,
    /// while keeping 401/403 distinct because the caller can repair those alone by
    /// refreshing the token.
    static func ensureSuccess(_ response: HTTPResponse, endpoint: String) throws {
        try translate(status: response.status, endpoint: endpoint)
    }

    /// Sends a request and reports the outcome in the provider's own terms.
    ///
    /// Every adapter goes through here rather than calling the HTTP client directly, because
    /// `UsageHTTPClient` THROWS on a non-2xx rather than returning one. Checking
    /// `response.status` for a 401 afterwards was therefore unreachable code: a rejected
    /// credential surfaced as an opaque `HTTPError.status(401, …)` and the caller lost the one
    /// signal it could act on. The distinction earns its keep because only a rejection is
    /// repairable — by refreshing the token — where every other failure is something to wait
    /// out or report.
    static func request(
        _ client: UsageHTTPClient,
        url: String,
        method: String = "GET",
        headers: [String: String] = [:],
        body: Data? = nil,
        endpoint: String,
        retries: Int? = nil
    ) async throws -> HTTPResponse {
        do {
            let response = try await client.request(
                url: url, method: method, headers: headers, body: body, retries: retries)
            try translate(status: response.status, endpoint: endpoint)
            return response
        } catch let error as HTTPError {
            switch error {
            case .status(let code, _):
                try translate(status: code, endpoint: endpoint)
                throw ProviderError.noData("\(endpoint) answered HTTP \(code)")
            case .rateLimited:
                throw ProviderError.noData("\(endpoint) is rate limiting this account")
            default:
                throw ProviderError.noData("\(endpoint) could not be reached")
            }
        }
    }

    private static func translate(status: Int, endpoint: String) throws {
        if status == 401 || status == 403 {
            throw ProviderError.unauthorised
        }
        guard (200...299).contains(status) else {
            throw ProviderError.noData("\(endpoint) answered HTTP \(status)")
        }
    }

    /// Turns a response body into a JSON object, or reports that it is not one, so callers
    /// never have to guess what a parser will accept.
    static func decodeObject(_ body: String, endpoint: String) throws -> [String: Any] {
        guard
            let data = body.data(using: .utf8),
            let object = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any]
        else {
            throw ProviderError.malformedPayload("\(endpoint) did not return a JSON object")
        }
        return object
    }
}

// MARK: - Codex (ChatGPT)

/// Reads ChatGPT/Codex quota from the undocumented `wham` backend.
///
/// The usage payload is the primary source and also carries a stale copy of the reset-credit
/// list; the dedicated reset-credit endpoint is newer and authoritative, so it is preferred
/// whenever it answers and the embedded copy covers the occasions when it does not.
public struct CodexClient: SyncProvider, Sendable {
    public let providerID = "codex"

    private static let usageURL = "https://chatgpt.com/backend-api/wham/usage"
    private static let resetCreditsURL = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits"

    /// Shared with the token exchange in `ProviderTokenRefresh.swift`, so one adapter makes one
    /// kind of request through one client.
    let httpClient: UsageHTTPClient

    /// Injected rather than read from the clock, so a test can pin the instant an access token
    /// is stamped as expiring at.
    let now: @Sendable () -> Date

    public init(httpClient: UsageHTTPClient, now: @Sendable @escaping () -> Date = { Date() }) {
        self.httpClient = httpClient
        self.now = now
    }

    public func fetchUsage(credentials: OAuthCredentials, attributes: [String: String]) async throws -> UsageResult {
        var headers: [String: String] = [
            "Authorization": "Bearer \(credentials.accessToken)",
            "Accept": "application/json",
            "Content-Type": "application/json",
            // The backend refuses requests that carry no client-like signature, so a stable
            // product-style agent is declared once here.
            "User-Agent": "UsageLimitsKit/1.0",
        ]
        // Team and enterprise plans scope quota to an account id, while personal plans reject
        // the header outright; it therefore travels only when the caller actually has one.
        if let accountID = attributes["chatgpt_account_id"], !accountID.isEmpty {
            headers["chatgpt-account-id"] = accountID
        }

        let usageResponse = try await ProviderHTTP.request(
            httpClient,
            url: Self.usageURL,
            method: "GET",
            headers: headers,
            body: nil,
            endpoint: "codex usage"
        )
        let usagePayload = try ProviderHTTP.decodeObject(usageResponse.body, endpoint: "codex usage")
        let windows = CodexUsageParser.parse(usagePayload, now: now())
        let plan = CodexUsageParser.parsePlan(usagePayload)

        let credits = try await fetchResetCredits(headers: headers, usagePayload: usagePayload)

        // The two sources are not the same payload with the same fields. The dedicated endpoint
        // returns `credits`, `available_count` and `total_earned_count` and NO applicable count;
        // the copy embedded in the usage payload returns both counts and no `credits` array.
        // Reading the applicable count off whichever source answered meant it existed only on
        // the FALLBACK path — the redeem button lost its gate precisely when the authoritative
        // call succeeded.
        let embedded = usagePayload["rate_limit_reset_credits"] as? [String: Any]

        return UsageResult(
            windows: windows,
            resetCredits: credits.rows,
            resetCreditCount: credits.available,
            applicableResetCreditCount: CodexUsageParser.applicableCreditCount(embedded),
            plan: plan
        )
    }

    /// Spends one reset credit, resetting the account's rate limit immediately.
    ///
    /// The one thing this app does that changes anything at a provider, so it is called only
    /// from an explicit, confirmed action and never as part of a refresh.
    ///
    /// `redeem_request_id` is a fresh UUID per attempt, which is what makes the call idempotent
    /// on the provider's side: a request replayed with the same id will not spend a second
    /// credit. For the same reason the request is sent with no retries — a network error after
    /// the server has already committed the spend is indistinguishable from one before it, and
    /// a retry would risk burning a second credit to avoid reporting a first one that worked.
    public func redeemResetCredit(
        credentials: OAuthCredentials,
        attributes: [String: String],
        requestID: String = UUID().uuidString
    ) async throws {
        var headers: [String: String] = [
            "Authorization": "Bearer \(credentials.accessToken)",
            "Accept": "application/json",
            "Content-Type": "application/json",
            "User-Agent": ProviderEndpoints.Codex.userAgent,
        ]
        if let accountID = attributes["chatgpt_account_id"], !accountID.isEmpty {
            headers[ProviderEndpoints.Codex.accountIDHeader] = accountID
        }
        // Only the reset-credit endpoints take these; the usage endpoint does not.
        headers.merge(ProviderEndpoints.Codex.resetCreditHeaders) { current, _ in current }

        let body = try JSONSerialization.data(
            withJSONObject: ["redeem_request_id": requestID], options: [])

        _ = try await ProviderHTTP.request(
            httpClient,
            url: ProviderEndpoints.Codex.resetCreditsConsumeURL,
            method: "POST",
            headers: headers,
            body: body,
            endpoint: "codex redeem",
            retries: 0)
    }

    /// Fetches reset credits from their dedicated endpoint, falling back to the copy embedded
    /// in the usage payload.
    ///
    /// The dedicated endpoint is newer than the usage payload and is the likelier of the two
    /// to change, so a failure here must cost only the credit rows: the usage refresh has
    /// already succeeded and remains worth showing. Every failure — including an
    /// authorisation rejection, which would be odd given the call above just succeeded with
    /// the same token — degrades to the slightly stale embedded copy rather than discarding
    /// the whole result.
    private func fetchResetCredits(
        headers: [String: String],
        usagePayload: [String: Any]
    ) async throws -> (rows: [ResetCredit], available: Int?) {
        do {
            let response = try await ProviderHTTP.request(
                httpClient,
                url: Self.resetCreditsURL,
                method: "GET",
                headers: headers,
                body: nil,
                endpoint: "codex reset credits"
            )
            let payload = try ProviderHTTP.decodeObject(response.body, endpoint: "codex reset credits")
            return (
                rows: CodexUsageParser.parseResetCredits(payload),
                available: CodexUsageParser.availableCreditCount(payload)
            )
        } catch let error as CancellationError {
            // Cancellation is the caller's decision, not an endpoint fault to absorb.
            throw error
        } catch {
            let embedded = usagePayload["rate_limit_reset_credits"] as? [String: Any]
            return (
                rows: CodexUsageParser.parseEmbeddedResetCredits(usagePayload),
                available: CodexUsageParser.availableCreditCount(embedded)
            )
        }
    }
}

// MARK: - Claude (Anthropic)

/// Reads the Claude subscription usage console over its OAuth-only routes.
public struct ClaudeClient: SyncProvider, Sendable {
    public let providerID = "claude"

    /// Shared with the token exchange in `ProviderTokenRefresh.swift`, so one adapter makes one
    /// kind of request through one client.
    let httpClient: UsageHTTPClient

    /// Injected rather than read from the clock, so a test can pin the instant an access token
    /// is stamped as expiring at.
    let now: @Sendable () -> Date

    public init(httpClient: UsageHTTPClient, now: @Sendable @escaping () -> Date = { Date() }) {
        self.httpClient = httpClient
        self.now = now
    }

    public func fetchUsage(credentials: OAuthCredentials, attributes: [String: String]) async throws -> UsageResult {
        let response = try await httpClient.request(
            url: "https://api.anthropic.com/api/oauth/usage",
            method: "GET",
            headers: [
                "Authorization": "Bearer \(credentials.accessToken)",
                "Accept": "application/json",
                // The /api/oauth paths exist only behind this feature flag; without it the
                // gateway answers 404 as though the routes were not there at all.
                "anthropic-beta": "oauth-2025-04-20",
            ],
            body: nil
        )
        try ProviderHTTP.ensureSuccess(response, endpoint: "claude usage")
        let payload = try ProviderHTTP.decodeObject(response.body, endpoint: "claude usage")
        return UsageResult(windows: ClaudeUsageParser.parse(payload, now: now()))
    }
}

// MARK: - Antigravity (Google)

/// Reads Antigravity quota summaries from the quota service's sharded hosts.
///
/// Google routes each account to exactly one shard but publishes no way of asking which, so
/// the known hosts are polled in order and an empty acknowledgement from one simply passes
/// the baton to the next.
public struct AntigravityClient: SyncProvider, Sendable {
    public let providerID = "antigravity"

    /// The shared list, not a private copy of it. This client carried its own two-host list
    /// that silently shadowed `ProviderEndpoints.Antigravity.quotaURLs`, which has three —
    /// so an account routed to the sandbox shard fell out of the loop on iOS and showed an
    /// error card while Android, using the full list, showed its buckets. One list, one
    /// place to update it.
    private static var quotaHosts: [String] { ProviderEndpoints.Antigravity.quotaURLs }

    /// Shared with the token exchange in `ProviderTokenRefresh.swift`, so one adapter makes one
    /// kind of request through one client.
    let httpClient: UsageHTTPClient

    /// Injected rather than read from the clock, so a test can pin the instant an access token
    /// is stamped as expiring at.
    let now: @Sendable () -> Date

    public init(httpClient: UsageHTTPClient, now: @Sendable @escaping () -> Date = { Date() }) {
        self.httpClient = httpClient
        self.now = now
    }

    public func fetchUsage(credentials: OAuthCredentials, attributes: [String: String]) async throws -> UsageResult {
        guard let projectID = attributes["project_id"], !projectID.isEmpty else {
            // The quota RPC is scoped to a project; without one there is no honest request
            // to send, and guessing would report some other project's quota.
            throw ProviderError.noData("project_id attribute is required for the quota summary")
        }
        let headers: [String: String] = [
            "Authorization": "Bearer \(credentials.accessToken)",
            "Accept": "application/json",
            "Content-Type": "application/json",
        ]
        // Serialised rather than interpolated so a project id containing quotes or braces
        // cannot quietly turn into a different request.
        let requestBody = try JSONSerialization.data(withJSONObject: ["project": projectID], options: [])

        var authRejection: ProviderError?
        var firstFailure: Error?

        for url in Self.quotaHosts {
            let response: HTTPResponse
            do {
                response = try await ProviderHTTP.request(
                    httpClient,
                    url: url,
                    method: "POST",
                    headers: headers,
                    body: requestBody,
                    endpoint: "antigravity quota"
                )
            } catch let error as CancellationError {
                throw error
            } catch {
                // A shard being unreachable or misbehaving reflects on the shard; the next
                // one may answer perfectly well.
                if firstFailure == nil { firstFailure = error }
                continue
            }

            if response.status == 401 || response.status == 403 {
                // Recorded rather than thrown at once: a stale credential is the usual
                // cause, but a retired shard also rejects perfectly good tokens, and the
                // remaining hosts are the cheapest way to tell the two apart.
                if authRejection == nil { authRejection = .unauthorised }
                continue
            }
            guard (200...299).contains(response.status) else {
                if firstFailure == nil {
                    firstFailure = ProviderError.noData("quota host answered HTTP \(response.status)")
                }
                continue
            }

            // An empty 2xx from a host means "this account is not routed here", NOT "this
            // account has no quota": every account lives on exactly one shard while the
            // others acknowledge politely with an empty summary. Declaring the account
            // quota-less on the first empty answer would misreport healthy accounts, so the
            // remaining hosts are tried and the account is only reported unserved once all
            // of them have come back empty.
            let summary = response.body.trimmingCharacters(in: .whitespacesAndNewlines)
            if summary.isEmpty {
                continue
            }

            do {
                let payload = try ProviderHTTP.decodeObject(response.body, endpoint: "quota summary")
                let windows = AntigravityQuotaParser.parse(payload, now: now())
                if windows.isEmpty {
                    // A well-formed summary carrying no windows is the same "not routed
                    // here" signal as an empty body, so it counts as empty too.
                    continue
                }
                return UsageResult(windows: windows)
            } catch let error as CancellationError {
                throw error
            } catch {
                if firstFailure == nil { firstFailure = error }
                continue
            }
        }

        // An authorisation rejection outranks the other failures because it is the only one
        // the caller can fix unilaterally, and a needless token refresh costs less than a
        // wrong diagnosis.
        if let authRejection {
            throw authRejection
        }
        if let firstFailure {
            throw firstFailure
        }
        // Every host acknowledged with an empty summary: the account is served by no shard
        // we know of, which is a different statement from "no quota left".
        throw ProviderError.noData("every quota host returned an empty summary")
    }
}

// MARK: - xAI

/// Reads xAI billing through the CLI proxy's two overlapping routes.
///
/// The credits-flavoured route adds purchased-credit balances to the subscription figures
/// the plain route carries, and the parser's merge reconciles the overlap. Because the two
/// routes age independently, one failing must not discard the other's answer: only a double
/// failure leaves nothing to show.
public struct XaiClient: SyncProvider, Sendable {
    public let providerID = "xai"

    private static let creditsURL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
    private static let billingURL = "https://cli-chat-proxy.grok.com/v1/billing"

    /// Shared with the token exchange in `ProviderTokenRefresh.swift`, so one adapter makes one
    /// kind of request through one client.
    let httpClient: UsageHTTPClient

    /// Injected rather than read from the clock, so a test can pin the instant an access token
    /// is stamped as expiring at.
    let now: @Sendable () -> Date

    public init(httpClient: UsageHTTPClient, now: @Sendable @escaping () -> Date = { Date() }) {
        self.httpClient = httpClient
        self.now = now
    }

    public func fetchUsage(credentials: OAuthCredentials, attributes: [String: String]) async throws -> UsageResult {
        let headers: [String: String] = [
            "Authorization": "Bearer \(credentials.accessToken)",
            "Accept": "application/json",
        ]

        var creditsWindows: [UsageWindow]?
        var creditsFailure: Error?
        do {
            creditsWindows = try await fetchWindows(
                url: Self.creditsURL,
                endpoint: "credits billing",
                headers: headers,
                parse: XaiBillingParser.parseCredits
            )
        } catch let error as CancellationError {
            throw error
        } catch {
            creditsFailure = error
        }

        var billingWindows: [UsageWindow]?
        var billingFailure: Error?
        do {
            billingWindows = try await fetchWindows(
                url: Self.billingURL,
                endpoint: "billing",
                headers: headers,
                parse: XaiBillingParser.parseBilling
            )
        } catch let error as CancellationError {
            throw error
        } catch {
            billingFailure = error
        }

        if let creditsWindows, let billingWindows {
            return UsageResult(windows: XaiBillingParser.merge(creditsWindows, billingWindows))
        }
        if let creditsWindows {
            return UsageResult(windows: creditsWindows)
        }
        if let billingWindows {
            return UsageResult(windows: billingWindows)
        }
        throw Self.preferredFailure(creditsFailure, billingFailure)
    }

    /// Fetches and parses one billing route; the caller decides what its failure means.
    private func fetchWindows(
        url: String,
        endpoint: String,
        headers: [String: String],
        parse: (_ payload: [String: Any], _ now: Date) -> [UsageWindow]
    ) async throws -> [UsageWindow] {
        let response = try await ProviderHTTP.request(
            httpClient, url: url, headers: headers,
            endpoint: endpoint)
        let payload = try ProviderHTTP.decodeObject(response.body, endpoint: endpoint)
        return parse(payload, now())
    }

    /// Picks which failure to surface when both routes were tried and both failed.
    ///
    /// The authorisation rejection wins because one token refresh repairs both routes at
    /// once; anything else would have the user retry twice for a single fault.
    private static func preferredFailure(_ first: Error?, _ second: Error?) -> Error {
        for candidate in [first, second].compactMap({ $0 }) {
            if let providerError = candidate as? ProviderError, case .unauthorised = providerError {
                return providerError
            }
        }
        if let first { return first }
        if let second { return second }
        return ProviderError.noData("both billing routes failed")
    }
}