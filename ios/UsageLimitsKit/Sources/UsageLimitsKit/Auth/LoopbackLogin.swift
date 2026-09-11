import Foundation

/// The authorisation-code half of sign-in, for the two providers that redirect to loopback.
///
/// Claude and Antigravity issue their code to `http://localhost:PORT/...` because their official
/// clients are desktop CLIs. That is not an obstacle to a phone once you notice what RFC 8252
/// §7.3 actually says: loopback IS the redirect for a native app that cannot register a scheme,
/// and an iOS app can bind 127.0.0.1. It works because `ASWebAuthenticationSession` presents in
/// process — the app stays foregrounded and the socket stays alive while the user signs in.
///
/// The app opens `challenge.url`, this waits for the redirect, and the code is exchanged for
/// tokens. PKCE is what protects the exchange: the verifier never leaves the device, so a code
/// intercepted on the way back is worth nothing on its own.

/// What the app needs in order to present the sign-in.
public struct LoopbackChallenge: Sendable {
    /// The provider's own sign-in page. Opened in a browser, never rendered by the app.
    public let url: URL

    /// The port the listener is bound to, so a caller can say which one is in use if it is.
    public let port: UInt16

    let provider: ProviderID
    let verifier: String
    let state: String
    let path: String
    let redirectURI: String

    /// Reads the authorisation code out of a callback URL, checking it belongs to THIS attempt.
    ///
    /// Exposed as a method rather than by making `state` public, so a caller cannot compare the
    /// state itself — and cannot get the comparison wrong. The check is the same one the
    /// listener applies, through the same function: constant time, and before anything else in
    /// the URL is read.
    public func code(fromCallback url: URL) throws -> String {
        try OAuthFlows.code(from: url, expectedState: state)
    }

    init(
        url: URL,
        port: UInt16,
        provider: ProviderID,
        verifier: String,
        state: String,
        path: String,
        redirectURI: String
    ) {
        self.url = url
        self.port = port
        self.provider = provider
        self.verifier = verifier
        self.state = state
        self.path = path
        self.redirectURI = redirectURI
    }
}

/// One provider's loopback sign-in.
public struct LoopbackLogin: Sendable {

    /// The registered redirect for each provider, split into the parts a listener needs.
    ///
    /// The port is FIXED, not ephemeral, because these redirect URIs are registered with the
    /// provider and an exact match is required — asking for a different port simply fails the
    /// authorisation. That is also why a port already in use is a real failure with a real
    /// message rather than something to route around.
    struct Redirect {
        let uri: String
        let host: String
        let port: UInt16
        let path: String

        init?(_ uri: String) {
            guard let components = URLComponents(string: uri),
                  let host = components.host,
                  let port = components.port,
                  let port16 = UInt16(exactly: port)
            else {
                return nil
            }
            self.uri = uri
            self.host = host
            self.port = port16
            self.path = components.path.isEmpty ? "/" : components.path
        }
    }

    public let provider: ProviderID
    private let httpClient: UsageHTTPClient
    private let now: @Sendable () -> Date

    public init(
        provider: ProviderID,
        httpClient: UsageHTTPClient,
        now: @Sendable @escaping () -> Date = { Date() }
    ) {
        self.provider = provider
        self.httpClient = httpClient
        self.now = now
    }

    /// Builds the sign-in URL and everything needed to finish.
    ///
    /// The PKCE pair and the state are generated here and held in the returned value rather than
    /// on this type, so a login can outlive the object that started it and two logins cannot
    /// share a verifier.
    public func begin() throws -> LoopbackChallenge {
        guard let redirect = Self.redirect(for: provider) else {
            throw DeviceLoginError.unsupportedOnThisPlatform(
                "This provider does not use a loopback sign-in.")
        }

        let pkce = PKCE.generate()
        let state = Self.randomState()

        let request = AuthorizationRequest(
            authorizationEndpoint: Self.authorizeEndpoint(for: provider),
            clientID: Self.clientID(for: provider),
            redirectURI: redirect.uri,
            scope: Self.scope(for: provider),
            state: state,
            pkce: pkce,
            extraParameters: Self.extraParameters(for: provider))

        guard let url = request.url else {
            throw DeviceLoginError.malformedResponse("the sign-in URL could not be built")
        }

        return LoopbackChallenge(
            url: url,
            port: redirect.port,
            provider: provider,
            verifier: pkce.verifier,
            state: state,
            path: redirect.path,
            redirectURI: redirect.uri)
    }

    /// Trades an authorisation code for tokens.
    ///
    /// Separated from waiting for the code so the socket work — which exists only where
    /// `Network` does — is not in the way of testing the exchange itself.
    public func exchange(
        code: String,
        challenge: LoopbackChallenge
    ) async throws -> OAuthCredentials {
        switch provider {
        case .claude:
            // Anthropic's token endpoint takes JSON, not the form encoding RFC 6749 specifies.
            let body = try JSONSerialization.data(
                withJSONObject: [
                    "grant_type": "authorization_code",
                    "code": code,
                    "redirect_uri": challenge.redirectURI,
                    "client_id": ProviderEndpoints.Claude.clientID,
                    "code_verifier": challenge.verifier,
                    "state": challenge.state,
                ],
                options: [])
            return try await TokenExchange.post(
                httpClient,
                url: ProviderEndpoints.Claude.tokenURL,
                headers: ["Accept": "application/json", "Content-Type": "application/json"],
                body: body,
                endpoint: "claude token",
                now: now())

        case .antigravity:
            return try await TokenExchange.post(
                httpClient,
                url: ProviderEndpoints.Antigravity.tokenEndpoint,
                headers: [
                    "Accept": "application/json",
                    "Content-Type": "application/x-www-form-urlencoded",
                ],
                body: TokenExchange.formBody([
                    "grant_type": "authorization_code",
                    "code": code,
                    "redirect_uri": challenge.redirectURI,
                    "client_id": ProviderEndpoints.Antigravity.clientID,
                    // Google's "installed application" secret. Public by design — RFC 8252 §8.5
                    // and Google's own documentation treat it as such, and it ships in the
                    // desktop client — but required, because the token endpoint rejects the
                    // exchange without it for this client type. PKCE is what protects the flow.
                    "client_secret": ProviderEndpoints.Antigravity.clientSecret,
                    "code_verifier": challenge.verifier,
                ]),
                endpoint: "google token",
                now: now())

        case .codex, .xai, .kimi:
            throw DeviceLoginError.unsupportedOnThisPlatform(
                "This provider signs in with a device code, not a redirect.")
        }
    }

    /// Who the credentials belong to.
    public func profile(_ credentials: OAuthCredentials) async throws -> ProviderProfile {
        switch provider {
        case .claude:
            let response = try await ProviderHTTP.request(
                httpClient,
                url: ProviderEndpoints.Claude.profileURL,
                headers: [
                    "Authorization": "Bearer \(credentials.accessToken)",
                    "Accept": "application/json",
                    "anthropic-beta": ProviderEndpoints.Claude.betaHeader,
                ],
                endpoint: "claude profile")
            let payload = try ProviderHTTP.decodeObject(
                response.body, endpoint: "claude profile")
            let account = JSONSupport.object(payload, "account")
            let email = JSONSupport.string(account, "email")

            // The uuid is the stable key; the address is only a fallback so an account whose
            // profile omits the uuid can still be stored rather than failing to add.
            guard let id = JSONSupport.string(account, "uuid") ?? email else {
                throw DeviceLoginError.malformedResponse("the profile names no account")
            }
            return ProviderProfile(
                externalAccountID: id,
                email: email,
                // `full_name` as the fallback Android has always read; without it the same
                // profile named the account on one phone and left it blank on the other.
                displayName: JSONSupport.string(account, "display_name", "displayName")
                    ?? JSONSupport.string(account, "full_name", "fullName"),
                plan: ClaudeUsageParser.parsePlan(payload))

        case .antigravity:
            let response = try await ProviderHTTP.request(
                httpClient,
                url: ProviderEndpoints.Antigravity.userInfoEndpoint,
                headers: [
                    "Authorization": "Bearer \(credentials.accessToken)",
                    "Accept": "application/json",
                ],
                endpoint: "google userinfo")
            let payload = try ProviderHTTP.decodeObject(
                response.body, endpoint: "google userinfo")

            guard let id = JSONSupport.string(payload, "id", "sub") else {
                throw DeviceLoginError.malformedResponse("the profile names no account")
            }

            // The quota RPC is addressed by GCP project, and nothing maps an account to its
            // project at fetch time — so it is resolved here, once, and stored. Without it
            // the account authenticates and then fails every refresh with "project_id
            // attribute is required", which is exactly what every Antigravity account added
            // on iOS did: the profile was built with an empty attribute map while Android
            // has resolved the project at login all along.
            let (projectID, tier) = try await resolveProject(credentials: credentials)
            return ProviderProfile(
                externalAccountID: id,
                email: JSONSupport.string(payload, "email"),
                displayName: JSONSupport.string(payload, "name"),
                plan: tier,
                attributes: ["project_id": projectID])

        case .codex, .xai, .kimi:
            throw DeviceLoginError.unsupportedOnThisPlatform(
                "This provider signs in with a device code, not a redirect.")
        }
    }

    /// Which GCP project this account's Antigravity quota lives under, and its paid tier.
    ///
    /// A port of Android's `resolveProjectId`: POST `loadCodeAssist` on each host in turn,
    /// read the project id from whichever shape it arrives in — a bare string or an object
    /// carrying an `id` — and the tier name beside it. A failure here is fatal to adding the
    /// account rather than to one refresh, because an account with no project can never
    /// read a number.
    private func resolveProject(credentials: OAuthCredentials) async throws -> (String, String?) {
        let body = try JSONSerialization.data(
            withJSONObject: ["metadata": ProviderEndpoints.Antigravity.loadCodeAssistMetadata],
            options: [])
        let headers = [
            "Authorization": "Bearer \(credentials.accessToken)",
            "Accept": "application/json",
            "Content-Type": "application/json",
        ]
        var lastError: Error?

        for url in ProviderEndpoints.Antigravity.loadCodeAssistURLs {
            do {
                let response = try await ProviderHTTP.request(
                    httpClient, url: url, method: "POST", headers: headers, body: body,
                    endpoint: "antigravity loadCodeAssist")
                let payload = try ProviderHTTP.decodeObject(
                    response.body, endpoint: "antigravity loadCodeAssist")
                if let project = Self.projectID(in: payload) {
                    return (project, Self.tierName(in: payload))
                }
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                // Which host serves a given account varies; the next may answer.
                lastError = error
            }
        }

        if let lastError { throw lastError }
        throw DeviceLoginError.malformedResponse(
            "loadCodeAssist returned no GCP project id. If this account has never used "
                + "Antigravity, sign in to the desktop client once to provision it, then add it here.")
    }

    /// Either shape upstream is known to emit: `"cloudaicompanionProject": "p"` or
    /// `"cloudaicompanionProject": {"id": "p"}`. Reading only the string form fails the
    /// login with the id sitting in plain view in the response.
    static func projectID(in payload: [String: Any]) -> String? {
        let keys = ["cloudaicompanionProject", "cloudaicompanion_project", "projectId", "project_id", "project"]
        for key in keys {
            if let direct = JSONSupport.string(payload, key), !direct.isEmpty { return direct }
            if let nested = JSONSupport.object(payload, key),
               let id = JSONSupport.string(nested, "id", "projectId", "project_id"), !id.isEmpty {
                return id
            }
        }
        return nil
    }

    static func tierName(in payload: [String: Any]) -> String? {
        for key in ["currentTier", "current_tier", "paidTier", "paid_tier"] {
            guard let tier = JSONSupport.object(payload, key) else { continue }
            if let name = JSONSupport.string(tier, "name"), !name.isEmpty { return name }
            if let id = JSONSupport.string(tier, "id"), !id.isEmpty { return id }
        }
        return nil
    }

    // MARK: - Per-provider constants

    static func redirect(for provider: ProviderID) -> Redirect? {
        switch provider {
        case .claude: return Redirect(ProviderEndpoints.Claude.redirectURI)
        case .antigravity: return Redirect(ProviderEndpoints.Antigravity.redirectURI)
        case .codex, .xai, .kimi: return nil
        }
    }

    private static func authorizeEndpoint(for provider: ProviderID) -> String {
        switch provider {
        case .claude: return ProviderEndpoints.Claude.authorizeURL
        case .antigravity: return ProviderEndpoints.Antigravity.authEndpoint
        case .codex, .xai, .kimi: return ""
        }
    }

    private static func clientID(for provider: ProviderID) -> String {
        switch provider {
        case .claude: return ProviderEndpoints.Claude.clientID
        case .antigravity: return ProviderEndpoints.Antigravity.clientID
        case .codex, .xai, .kimi: return ""
        }
    }

    private static func scope(for provider: ProviderID) -> String {
        switch provider {
        case .claude: return ProviderEndpoints.Claude.scope
        case .antigravity: return ProviderEndpoints.Antigravity.scopes.joined(separator: " ")
        case .codex, .xai, .kimi: return ""
        }
    }

    private static func extraParameters(for provider: ProviderID) -> [String: String] {
        switch provider {
        case .antigravity:
            // Google issues a refresh token only when both are asked for, and only on the FIRST
            // consent without `prompt=consent`. Without them the account works for one hour and
            // then signs itself out, which reads to a user as the app being broken.
            return ["access_type": "offline", "prompt": "consent"]
        case .claude, .codex, .xai, .kimi:
            return [:]
        }
    }

    /// 32 bytes of entropy, base64url. Fatal on failure for the same reason PKCE's generator is:
    /// a predictable state is worse than no state, because the flow still completes.
    private static func randomState() -> String {
        Base64URL.encode(PKCE.secureRandom(32))
    }
}
