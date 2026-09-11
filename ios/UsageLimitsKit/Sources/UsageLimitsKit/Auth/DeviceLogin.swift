import Foundation

/// Signing in on a phone, for the providers whose flow a phone can actually complete.
///
/// Two of the four cannot be signed into from iOS at all today, and this file deliberately does
/// not pretend otherwise. Claude and Antigravity issue their authorisation codes to a
/// `http://localhost:PORT/...` redirect, which is a desktop CLI's loopback listener — an iOS app
/// cannot bind a port and receive one. The custom scheme that `ASWebAuthenticationSession` would
/// use has to be registered with each provider first, and it is not. Offering a button that
/// starts a flow which cannot finish is worse than saying so.
///
/// Codex and xAI use the device authorisation grant, which is designed for exactly this: the
/// device shows a short code, the user approves it in a browser anywhere, and the device polls.
/// No redirect, no listener, no registration.

// MARK: - The challenge

/// What the user is shown, and everything needed to finish without the flow holding state.
///
/// The continuation is opaque and provider-specific — Codex's device-auth id, xAI's device code
/// and resolved token endpoint. It rides in the value rather than in the coordinator so a login
/// can survive the object that started it, and so nothing has to be re-discovered later against
/// an endpoint that may have moved in between.
public struct DeviceLoginChallenge: Sendable, Equatable {

    /// The short code the user types. This is the only part ever displayed.
    public let userCode: String

    /// Where they type it. Opened in the user's own browser.
    public let verificationURI: String

    /// The same page with the code already filled in, when the provider offers one.
    public let verificationURIComplete: String?

    public let expiresAt: Date

    /// Never below the provider's stated interval, and never below this kit's own floor: polling
    /// faster than a server allows earns a ban, not data.
    public let pollInterval: TimeInterval

    /// Provider-specific, never displayed, never logged.
    public let continuation: [String: String]

    public init(
        userCode: String,
        verificationURI: String,
        verificationURIComplete: String? = nil,
        expiresAt: Date,
        pollInterval: TimeInterval,
        continuation: [String: String]
    ) {
        self.userCode = userCode
        self.verificationURI = verificationURI
        self.verificationURIComplete = verificationURIComplete
        self.expiresAt = expiresAt
        self.pollInterval = pollInterval
        self.continuation = continuation
    }
}

/// Why a login did not produce credentials.
public enum DeviceLoginError: Error, LocalizedError, Equatable {

    /// The provider's response did not carry what the flow needs.
    case malformedResponse(String)

    /// The user did not approve before the code expired.
    case expired

    /// The user declined, or the provider refused the grant.
    case declined(String)

    /// This provider cannot be signed into from this platform. Carries the reason, because the
    /// user is owed one.
    case unsupportedOnThisPlatform(String)

    public var errorDescription: String? {
        switch self {
        case .malformedResponse(let detail):
            return "The provider answered in a shape this app does not understand (\(detail))."
        case .expired:
            return "The sign-in code expired before it was approved."
        case .declined(let reason):
            return "The provider refused the sign-in (\(reason))."
        case .unsupportedOnThisPlatform(let reason):
            return reason
        }
    }
}

/// One provider's device-grant sign-in.
public protocol DeviceLoginProvider: Sendable {
    var providerID: ProviderID { get }

    /// Asks the provider for a code to show the user.
    func begin() async throws -> DeviceLoginChallenge

    /// Polls until the user approves, the code expires, or the provider refuses.
    ///
    /// Long-running by nature — a user has to reach a browser — so it is cancellable at every
    /// wait, and cancellation means "the screen was closed", not "the login failed".
    func complete(_ challenge: DeviceLoginChallenge) async throws -> OAuthCredentials

    /// Who the credentials belong to.
    func profile(_ credentials: OAuthCredentials) async throws -> ProviderProfile
}

/// The floor under every poll interval, whatever a provider claims.
enum DeviceLoginTiming {
    static let minimumPollInterval: TimeInterval = 5
    /// Used when a provider states no expiry of its own.
    static let defaultLifetime: TimeInterval = 15 * 60

    /// Sleeps for a provider-supplied interval without trusting it.
    ///
    /// `UInt64(seconds * 1_000_000_000)` TRAPS once the product leaves UInt64's range, and an
    /// `"interval": 20000000000` in a device response is enough — the floor below the interval
    /// was the only check, and a floor says nothing about a ceiling. A crash mid sign-in is
    /// the worst answer available here; a malformed-response error is one the flow already
    /// knows how to show.
    static func sleep(seconds: TimeInterval) async throws {
        guard seconds.isFinite, seconds >= 0,
              let nanos = UInt64(exactly: (seconds * 1_000_000_000).rounded(.towardZero))
        else {
            throw DeviceLoginError.malformedResponse("invalid poll interval")
        }
        try await Task.sleep(nanoseconds: nanos)
    }
}

// MARK: - Codex

/// OpenAI's device flow, which is not RFC 8628.
///
/// It has its own endpoints, a JSON body rather than a form, and it signals "not yet approved"
/// with 403 or 404 rather than an `authorization_pending` body. The approval yields an
/// authorisation code AND the PKCE verifier the provider generated for it, which are then
/// exchanged at the ordinary token endpoint. That the provider generates the PKCE pair means
/// PKCE here is not the client-binding guarantee it usually is — recorded rather than glossed,
/// because the flow looks like PKCE and is not doing PKCE's job.
public struct CodexDeviceLogin: DeviceLoginProvider {
    public let providerID = ProviderID.codex

    private let httpClient: UsageHTTPClient
    private let now: @Sendable () -> Date

    public init(httpClient: UsageHTTPClient, now: @Sendable @escaping () -> Date = { Date() }) {
        self.httpClient = httpClient
        self.now = now
    }

    private var headers: [String: String] {
        [
            "Accept": "application/json",
            "Content-Type": "application/json",
            "User-Agent": ProviderEndpoints.Codex.userAgent,
        ]
    }

    public func begin() async throws -> DeviceLoginChallenge {
        let body = try JSONSerialization.data(
            withJSONObject: ["client_id": ProviderEndpoints.Codex.clientID], options: [])
        let response = try await ProviderHTTP.request(
            httpClient,
            url: ProviderEndpoints.Codex.deviceUserCodeURL,
            method: "POST", headers: headers, body: body,
            endpoint: "codex device")
        let payload = try ProviderHTTP.decodeObject(response.body, endpoint: "codex device")

        // Upstream has shipped both spellings of the code field.
        guard let userCode = JSONSupport.string(payload, "user_code", "usercode") else {
            throw DeviceLoginError.malformedResponse("no user code")
        }
        guard let deviceAuthID = JSONSupport.string(payload, "device_auth_id", "deviceAuthId") else {
            throw DeviceLoginError.malformedResponse("no device_auth_id")
        }

        let interval = JSONSupport.int64(payload, "interval").map(TimeInterval.init) ?? 0
        return DeviceLoginChallenge(
            userCode: userCode,
            verificationURI: ProviderEndpoints.Codex.deviceVerificationURL,
            expiresAt: now().addingTimeInterval(DeviceLoginTiming.defaultLifetime),
            pollInterval: max(interval, DeviceLoginTiming.minimumPollInterval),
            // Never displayed: it identifies the pending authorisation, not the user.
            continuation: ["device_auth_id": deviceAuthID, "user_code": userCode])
    }

    public func complete(_ challenge: DeviceLoginChallenge) async throws -> OAuthCredentials {
        guard let deviceAuthID = challenge.continuation["device_auth_id"],
              let userCode = challenge.continuation["user_code"]
        else {
            throw DeviceLoginError.malformedResponse("the challenge carries no device id")
        }

        let body = try JSONSerialization.data(
            withJSONObject: ["device_auth_id": deviceAuthID, "user_code": userCode], options: [])

        while now() < challenge.expiresAt {
            try Task.checkCancellation()

            let payload: [String: Any]?
            do {
                let response = try await ProviderHTTP.request(
                    httpClient,
                    url: ProviderEndpoints.Codex.deviceTokenURL,
                    method: "POST", headers: headers, body: body,
                    endpoint: "codex device token")
                payload = try? ProviderHTTP.decodeObject(
                    response.body, endpoint: "codex device token")
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                // 403 and 404 both mean "not approved yet" here, and every other failure is
                // equally worth another poll: the deadline is what ends this loop, not one bad
                // response. A user walking to another device is the normal case.
                payload = nil
            }

            if let payload,
               let code = JSONSupport.string(payload, "authorization_code", "authorizationCode"),
               let verifier = JSONSupport.string(payload, "code_verifier", "codeVerifier") {
                return try await exchange(code: code, verifier: verifier)
            }

            try await DeviceLoginTiming.sleep(seconds: challenge.pollInterval)
        }
        throw DeviceLoginError.expired
    }

    /// Trades the approved code for tokens at the ordinary token endpoint.
    private func exchange(code: String, verifier: String) async throws -> OAuthCredentials {
        try await TokenExchange.post(
            httpClient,
            url: ProviderEndpoints.Codex.tokenURL,
            headers: [
                "Accept": "application/json",
                "Content-Type": "application/x-www-form-urlencoded",
                "User-Agent": ProviderEndpoints.Codex.userAgent,
            ],
            body: TokenExchange.formBody([
                "grant_type": "authorization_code",
                "client_id": ProviderEndpoints.Codex.clientID,
                "code": code,
                // Must match what the device endpoint issued the code against, even though this
                // app never navigates there.
                "redirect_uri": ProviderEndpoints.Codex.deviceExchangeRedirectURI,
                "code_verifier": verifier,
            ]),
            endpoint: "codex token",
            now: now())
    }

    public func profile(_ credentials: OAuthCredentials) async throws -> ProviderProfile {
        let claims = JWTClaims.decode(credentials.idToken)
        let auth = JWTClaims.openAIAuth(claims)

        // The chatgpt account id is preferred, then the subject. They are different things: the
        // account id scopes a team or enterprise plan's quota, the subject identifies the user.
        let accountID = JWTClaims.string(auth, "chatgpt_account_id")
            ?? JWTClaims.string(claims, "sub")
        guard let accountID else {
            throw DeviceLoginError.malformedResponse("the ID token names no account")
        }

        // Only sent as a header when the provider actually stated one. A JWT subject is a user
        // id, not an account id, and sending it scopes the usage call to the wrong account.
        var attributes: [String: String] = [:]
        if let chatGPTAccountID = JWTClaims.string(auth, "chatgpt_account_id") {
            attributes["chatgpt_account_id"] = chatGPTAccountID
        }

        return ProviderProfile(
            externalAccountID: accountID,
            email: JWTClaims.string(claims, "email"),
            displayName: nil,
            plan: planLabel(JWTClaims.string(auth, "chatgpt_plan_type")),
            attributes: attributes)
    }
}

// MARK: - xAI

/// xAI's device flow, which IS RFC 8628.
///
/// The endpoints are discovered rather than hardcoded and every URL that comes back is checked
/// against the issuer host before it is used — including the verification page, because this app
/// is about to tell the user to sign in there. A compromised or tampered discovery response
/// pointing at a phishing page the app has just vouched for is a worse outcome than a failed
/// login.
public struct XaiDeviceLogin: DeviceLoginProvider {
    public let providerID = ProviderID.xai

    private let httpClient: UsageHTTPClient
    private let now: @Sendable () -> Date
    var waitForPoll: @Sendable (TimeInterval) async throws -> Void = {
        try await DeviceLoginTiming.sleep(seconds: $0)
    }


    public init(httpClient: UsageHTTPClient, now: @Sendable @escaping () -> Date = { Date() }) {
        self.httpClient = httpClient
        self.now = now
    }

    private var headers: [String: String] {
        var headers = [
            "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded",
            "User-Agent": ProviderEndpoints.Xai.userAgent,
        ]
        headers.merge(ProviderEndpoints.Xai.identityHeaders) { current, _ in current }
        return headers
    }

    public func begin() async throws -> DeviceLoginChallenge {
        let (deviceEndpoint, tokenEndpoint) = try await discover()

        let response = try await ProviderHTTP.request(
            httpClient,
            url: deviceEndpoint,
            method: "POST", headers: headers,
            body: TokenExchange.formBody([
                "client_id": ProviderEndpoints.Xai.clientID,
                "scope": ProviderEndpoints.Xai.scope,
            ]),
            endpoint: "xai device")
        let payload = try ProviderHTTP.decodeObject(response.body, endpoint: "xai device")

        guard let userCode = JSONSupport.string(payload, "user_code", "userCode"),
              let deviceCode = JSONSupport.string(payload, "device_code", "deviceCode"),
              let verificationURI = JSONSupport.string(
                payload, "verification_uri", "verificationUri")
        else {
            throw DeviceLoginError.malformedResponse("the device response is incomplete")
        }

        let lifetime = JSONSupport.int64(payload, "expires_in", "expiresIn").map(TimeInterval.init)
            ?? DeviceLoginTiming.defaultLifetime
        let interval = JSONSupport.int64(payload, "interval").map(TimeInterval.init) ?? 0

        return DeviceLoginChallenge(
            userCode: userCode,
            verificationURI: try XaiClient.validated(verificationURI),
            verificationURIComplete: try JSONSupport.string(
                payload, "verification_uri_complete", "verificationUriComplete")
                .map(XaiClient.validated),
            expiresAt: now().addingTimeInterval(lifetime),
            pollInterval: max(interval, DeviceLoginTiming.minimumPollInterval),
            // The token endpoint rides along so the poll cannot end up talking to an endpoint a
            // second discovery call moved under it.
            continuation: ["device_code": deviceCode, "token_endpoint": tokenEndpoint])
    }

    public func complete(_ challenge: DeviceLoginChallenge) async throws -> OAuthCredentials {
        guard let deviceCode = challenge.continuation["device_code"],
              let packedEndpoint = challenge.continuation["token_endpoint"]
        else {
            throw DeviceLoginError.malformedResponse("the challenge carries no device code")
        }
        // Re-checked rather than trusted: the challenge is a value that may have been held
        // across a suspension, and validation costs nothing.
        let tokenEndpoint = try XaiClient.validated(packedEndpoint)

        let body = TokenExchange.formBody([
            "grant_type": ProviderEndpoints.Xai.deviceCodeGrantType,
            "device_code": deviceCode,
            "client_id": ProviderEndpoints.Xai.clientID,
        ])

        // The interval OUTLIVES a slow_down. RFC 8628 §3.5 says the client "MUST increase its
        // polling interval by 5 seconds for all subsequent requests"; sleeping an extra five
        // seconds once and then resuming at the original rate polled faster than the server
        // had just asked, which is how a device flow earns a ban rather than an answer.
        var interval = challenge.pollInterval

        while now() < challenge.expiresAt {
            try Task.checkCancellation()

            let payload = try await DeviceTokenPoll.payload(
                httpClient, url: tokenEndpoint, headers: headers, body: body)

            if let payload {
                switch JSONSupport.string(payload, "error") {
                case nil:
                    return try TokenExchange.credentials(
                        from: payload, endpoint: "xai device token", now: now())
                case "authorization_pending":
                    break
                case "slow_down":
                    // The five seconds RFC 8628 §3.5 specifies, added to every poll from here on.
                    interval += 5
                case "expired_token":
                    throw DeviceLoginError.expired
                case "access_denied":
                    throw DeviceLoginError.declined("the sign-in was declined")
                case _?:
                    throw DeviceLoginError.declined("the device grant was refused")
                }
            }

            try await waitForPoll(interval)
        }
        throw DeviceLoginError.expired
    }

    public func profile(_ credentials: OAuthCredentials) async throws -> ProviderProfile {
        // The claims arrived over TLS from the token endpoint in answer to this app's own
        // request, so reading them costs no round trip and no extra exposure of the token.
        // `/v1/me` is the fallback for the case where xAI issues no ID token for these scopes.
        let claims = JWTClaims.decode(credentials.idToken)
        if let subject = JWTClaims.string(claims, "sub") {
            return ProviderProfile(
                externalAccountID: subject,
                email: JWTClaims.string(claims, "email"),
                displayName: JWTClaims.string(claims, "name", "preferred_username"))
        }

        let response = try await ProviderHTTP.request(
            httpClient,
            url: ProviderEndpoints.Xai.meURL,
            headers: ["Accept": "application/json",
                      "Authorization": "Bearer \(credentials.accessToken)"],
            endpoint: "xai me")
        let payload = try ProviderHTTP.decodeObject(response.body, endpoint: "xai me")

        guard let id = JSONSupport.string(payload, "id", "user_id", "userId", "sub") else {
            throw DeviceLoginError.malformedResponse("the profile names no account")
        }
        return ProviderProfile(
            externalAccountID: id,
            email: JSONSupport.string(payload, "email"),
            displayName: JSONSupport.string(payload, "name", "display_name", "displayName"))
    }

    /// Reads the issuer's discovery document and checks both endpoints before returning them.
    private func discover() async throws -> (device: String, token: String) {
        let response = try await ProviderHTTP.request(
            httpClient,
            url: ProviderEndpoints.Xai.discoveryURL,
            headers: ["Accept": "application/json"],
            endpoint: "xai discovery")
        let payload = try ProviderHTTP.decodeObject(response.body, endpoint: "xai discovery")

        guard let device = JSONSupport.string(
                payload, "device_authorization_endpoint", "deviceAuthorizationEndpoint"),
              let token = JSONSupport.string(payload, "token_endpoint", "tokenEndpoint")
        else {
            throw DeviceLoginError.malformedResponse("the discovery document is incomplete")
        }
        return (try XaiClient.validated(device), try XaiClient.validated(token))
    }
}

// MARK: - Kimi

/// Kimi Code's device flow, which IS RFC 8628, driven under this app's own name.
///
/// The identity headers are the point. Kimi's client id is public and shared by every program
/// that runs this flow; what Moonshot gates on, and forbids spoofing, is the platform header that
/// names the caller. This sends the app's own — never `kimi_cli` — and the coding API may refuse
/// it with `403 access_terminated` until Moonshot has allowlisted the name. The pasted key
/// (`UsageLimitsContainer.completePastedKeyLogin`) stays as the other way in for that case.
public struct KimiDeviceLogin: DeviceLoginProvider {
    public let providerID = ProviderID.kimi

    private let httpClient: UsageHTTPClient
    private let now: @Sendable () -> Date
    var waitForPoll: @Sendable (TimeInterval) async throws -> Void = {
        try await DeviceLoginTiming.sleep(seconds: $0)
    }

    public init(httpClient: UsageHTTPClient, now: @Sendable @escaping () -> Date = { Date() }) {
        self.httpClient = httpClient
        self.now = now
    }

    private var headers: [String: String] {
        var headers = [
            "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded",
        ]
        headers.merge(ProviderEndpoints.Kimi.identityHeaders) { current, _ in current }
        return headers
    }

    public func begin() async throws -> DeviceLoginChallenge {
        let response = try await ProviderHTTP.request(
            httpClient,
            url: ProviderEndpoints.Kimi.deviceCodeURL,
            method: "POST", headers: headers,
            body: TokenExchange.formBody(["client_id": ProviderEndpoints.Kimi.clientID]),
            endpoint: "kimi device")
        let payload = try ProviderHTTP.decodeObject(response.body, endpoint: "kimi device")

        let complete = JSONSupport.string(
            payload, "verification_uri_complete", "verificationUriComplete")
        guard let userCode = JSONSupport.string(payload, "user_code", "userCode"),
              let deviceCode = JSONSupport.string(payload, "device_code", "deviceCode"),
              let verificationURI = JSONSupport.string(
                payload, "verification_uri", "verificationUri") ?? complete
        else {
            throw DeviceLoginError.malformedResponse("the device response is incomplete")
        }

        let lifetime = JSONSupport.int64(payload, "expires_in", "expiresIn").map(TimeInterval.init)
            ?? DeviceLoginTiming.defaultLifetime
        let interval = JSONSupport.int64(payload, "interval").map(TimeInterval.init) ?? 0

        return DeviceLoginChallenge(
            userCode: userCode,
            verificationURI: verificationURI,
            verificationURIComplete: complete,
            expiresAt: now().addingTimeInterval(lifetime),
            pollInterval: max(interval, DeviceLoginTiming.minimumPollInterval),
            continuation: ["device_code": deviceCode])
    }

    public func complete(_ challenge: DeviceLoginChallenge) async throws -> OAuthCredentials {
        guard let deviceCode = challenge.continuation["device_code"] else {
            throw DeviceLoginError.malformedResponse("the challenge carries no device code")
        }

        let body = TokenExchange.formBody([
            "grant_type": ProviderEndpoints.Kimi.deviceCodeGrantType,
            "device_code": deviceCode,
            "client_id": ProviderEndpoints.Kimi.clientID,
        ])

        // The interval outlives a slow_down, as RFC 8628 §3.5 requires.
        var interval = challenge.pollInterval

        while now() < challenge.expiresAt {
            try Task.checkCancellation()

            let payload = try await DeviceTokenPoll.payload(
                httpClient, url: ProviderEndpoints.Kimi.tokenURL, headers: headers, body: body)

            if let payload {
                switch JSONSupport.string(payload, "error") {
                case nil:
                    return try TokenExchange.credentials(
                        from: payload, endpoint: "kimi device token", now: now())
                case "authorization_pending":
                    break
                case "slow_down":
                    interval += 5
                case "expired_token":
                    throw DeviceLoginError.expired
                case "access_denied":
                    throw DeviceLoginError.declined("the sign-in was declined")
                case _?:
                    throw DeviceLoginError.declined("the device grant was refused")
                }
            }

            try await waitForPoll(interval)
        }
        throw DeviceLoginError.expired
    }

    /// Who the credentials belong to: what the usage response names, else a digest of the
    /// credential — the same rule the pasted key follows, so one payload names one account.
    public func profile(_ credentials: OAuthCredentials) async throws -> ProviderProfile {
        let usage = try await KimiClient(httpClient: httpClient)
            .fetchUsage(credentials: credentials, attributes: [:])
        let named = usage.accountIdentity?.trimmingCharacters(in: .whitespaces) ?? ""
        return ProviderProfile(
            externalAccountID: named.isEmpty
                ? UsageLimitsContainer.pastedKeyIdentity(credentials.accessToken)
                : named,
            email: nil,
            displayName: nil,
            plan: nil)
    }
}

// MARK: - The providers a phone cannot sign into

/// How each provider signs in.
///
/// All four can, which was not true when this file was written. Claude and Antigravity redirect
/// to `http://localhost:PORT/...`, and the first reading of that was that a phone cannot receive
/// it — so the app said so and offered no button. That reading was wrong: RFC 8252 §7.3 names
/// loopback as the redirect for exactly this case, an iOS app can bind 127.0.0.1, and
/// `ASWebAuthenticationSession` keeps the app foregrounded while the browser loads it. The
/// listener lives in `LoopbackListener` and the flow in `LoopbackLogin`.
public enum LoginStyle: Sendable, Equatable {
    /// The provider shows a short code the user approves in a browser. No redirect to receive.
    case deviceCode

    /// The provider redirects to a loopback address this app listens on.
    case loopbackRedirect

    /// No flow at all: the user creates a key on the provider's console and pastes it.
    ///
    /// No provider's default any more — Kimi Code signs in with its device flow — but still
    /// the shape of Kimi's second way in, offered under the flow. See docs/providers-kimi.md.
    case pastedKey
}

public enum DeviceLoginSupport {

    public static func style(for provider: ProviderID) -> LoginStyle {
        switch provider {
        case .xai, .kimi: return .deviceCode
        // Codex runs the CLI's own browser flow on its registered redirect; the device flow
        // is kept as the fallback the app switches to when that port is taken.
        case .codex, .claude, .antigravity: return .loopbackRedirect
        }
    }

    /// Whether the provider ALSO takes a key the user pastes, beside its flow.
    ///
    /// Kimi Code: the flow is the default, but the coding API admits only programs Moonshot
    /// has allowlisted by name, and a key from the user's console works either way.
    public static func acceptsPastedKey(_ provider: ProviderID) -> Bool { provider == .kimi }

    /// Nil for every provider now. Kept because "can this be signed into here" is a question the
    /// UI asks, and answering it through a function leaves one place to change if a provider
    /// ever withdraws a flow.
    public static func unsupportedReason(for provider: ProviderID) -> String? { nil }

    public static var supported: [ProviderID] { ProviderID.allCases }
}

/// Keeps OAuth protocol errors available without passing raw error bodies to the UI.
private enum DeviceTokenPoll {
    static func payload(
        _ client: UsageHTTPClient, url: String, headers: [String: String], body: Data
    ) async throws -> [String: Any]? {
        let text: String
        let failedStatus: Bool
        do {
            let response = try await client.request(
                url: url, method: "POST", headers: headers, body: body, retries: 0)
            text = response.body
            failedStatus = false
        } catch HTTPError.status(let code, let body) where code == 400 || code == 403 {
            text = body
            failedStatus = true
        } catch HTTPError.status(let code, _) where (500...599).contains(code) {
            return nil
        } catch HTTPError.transport {
            return nil
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw DeviceLoginError.declined("the device endpoint refused the request")
        }
        let payload = try ProviderHTTP.decodeObject(text, endpoint: "device token")
        if failedStatus && JSONSupport.string(payload, "error") == nil {
            throw DeviceLoginError.malformedResponse("device error response had no error code")
        }
        return payload
    }
}
