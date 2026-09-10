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

        case .codex, .xai:
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
                displayName: JSONSupport.string(account, "display_name", "displayName"),
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
            return ProviderProfile(
                externalAccountID: id,
                email: JSONSupport.string(payload, "email"),
                displayName: JSONSupport.string(payload, "name"))

        case .codex, .xai:
            throw DeviceLoginError.unsupportedOnThisPlatform(
                "This provider signs in with a device code, not a redirect.")
        }
    }

    // MARK: - Per-provider constants

    static func redirect(for provider: ProviderID) -> Redirect? {
        switch provider {
        case .claude: return Redirect(ProviderEndpoints.Claude.redirectURI)
        case .antigravity: return Redirect(ProviderEndpoints.Antigravity.redirectURI)
        case .codex, .xai: return nil
        }
    }

    private static func authorizeEndpoint(for provider: ProviderID) -> String {
        switch provider {
        case .claude: return ProviderEndpoints.Claude.authorizeURL
        case .antigravity: return ProviderEndpoints.Antigravity.authEndpoint
        case .codex, .xai: return ""
        }
    }

    private static func clientID(for provider: ProviderID) -> String {
        switch provider {
        case .claude: return ProviderEndpoints.Claude.clientID
        case .antigravity: return ProviderEndpoints.Antigravity.clientID
        case .codex, .xai: return ""
        }
    }

    private static func scope(for provider: ProviderID) -> String {
        switch provider {
        case .claude: return ProviderEndpoints.Claude.scope
        case .antigravity: return ProviderEndpoints.Antigravity.scopes.joined(separator: " ")
        case .codex, .xai: return ""
        }
    }

    private static func extraParameters(for provider: ProviderID) -> [String: String] {
        switch provider {
        case .antigravity:
            // Google issues a refresh token only when both are asked for, and only on the FIRST
            // consent without `prompt=consent`. Without them the account works for one hour and
            // then signs itself out, which reads to a user as the app being broken.
            return ["access_type": "offline", "prompt": "consent"]
        case .claude, .codex, .xai:
            return [:]
        }
    }

    /// 32 bytes of entropy, base64url. Fatal on failure for the same reason PKCE's generator is:
    /// a predictable state is worse than no state, because the flow still completes.
    private static func randomState() -> String {
        Base64URL.encode(PKCE.secureRandom(32))
    }
}
