import Foundation

// The token-exchange half of each provider adapter: turning a refresh token into a working
// access token. Kept beside the fetching half rather than inside it because the two fail for
// unrelated reasons — a quota endpoint changing shape is a bad afternoon, a token endpoint
// changing shape signs every account out — and because the rules below are shared by all four
// providers while the fetch paths have nothing in common.
//
// None of these clients carries a confidential secret. Three send a public client id and
// nothing else; Google's installed-application secret is public by design (see
// `ProviderEndpoints.Antigravity.clientSecret`).

// MARK: - Shared token plumbing

/// The parts of an OAuth token exchange every provider does the same way.
enum TokenExchange {

    /// Encodes `application/x-www-form-urlencoded`, matching Java's `URLEncoder` — which is what
    /// the Android client sends, and therefore what these endpoints are known to accept. Space
    /// becomes `+` rather than `%20`, which matters for the scope parameter.
    static func formBody(_ fields: [String: String]) -> Data {
        // Sorted so a request is reproducible and a test can assert on the whole body rather
        // than picking it apart; no endpoint here cares about parameter order.
        let encoded = fields
            .sorted { $0.key < $1.key }
            .map { "\(escape($0.key))=\(escape($0.value))" }
            .joined(separator: "&")
        return Data(encoded.utf8)
    }

    /// `URLEncoder`'s alphabet: unreserved characters plus `*`, with space as `+`.
    private static func escape(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "*-._")
        return value
            .addingPercentEncoding(withAllowedCharacters: allowed)?
            .replacingOccurrences(of: "%20", with: "+")
            ?? value
    }

    /// Reads a token response into credentials.
    ///
    /// A missing `refresh_token` is left nil rather than filled in, because nil is exactly what
    /// the response said: providers that do not rotate omit the field, meaning "keep the one you
    /// have". The engine merges the result over the stored pair, so the old token survives
    /// without every adapter having to remember to carry it.
    ///
    /// `expires_in` is converted to an absolute instant at the moment of the exchange. A relative
    /// value starts decaying as soon as it is stored and would make every later expiry check
    /// depend on a fetch time nothing records.
    static func credentials(
        from payload: [String: Any],
        endpoint: String,
        now: Date
    ) throws -> OAuthCredentials {
        guard let accessToken = JSONSupport.string(payload, "access_token", "accessToken"),
              !accessToken.isEmpty
        else {
            // Deliberately names only the endpoint. A token response that failed to parse is
            // exactly the payload that must never be echoed into a message.
            throw ProviderError.malformedPayload("\(endpoint) returned no access_token")
        }

        let expiresIn = JSONSupport.int64(payload, "expires_in", "expiresIn")
        return OAuthCredentials(
            accessToken: accessToken,
            refreshToken: JSONSupport.string(payload, "refresh_token", "refreshToken"),
            idToken: JSONSupport.string(payload, "id_token", "idToken"),
            expiresAt: expiresIn.map { now.addingTimeInterval(TimeInterval($0)) },
            scope: JSONSupport.string(payload, "scope"))
    }

    /// The refresh token an exchange needs, or the error that says the account must sign in again.
    static func refreshToken(of credentials: OAuthCredentials) throws -> String {
        guard let token = credentials.refreshToken, !token.isEmpty else {
            throw ProviderError.unauthorised
        }
        return token
    }

    /// Posts a token request and decodes the response.
    ///
    /// A token endpoint answers a spent, revoked or malformed refresh token with 400
    /// (`invalid_grant`) rather than 401, so 400 is folded in with the rejections here. That is
    /// not true of the quota endpoints — a 400 there is a bad request, not a dead credential —
    /// which is why this translation lives with the exchange rather than in the shared status
    /// handling.
    static func post(
        _ client: UsageHTTPClient,
        url: String,
        headers: [String: String],
        body: Data,
        endpoint: String,
        now: Date
    ) async throws -> OAuthCredentials {
        do {
            let response = try await client.request(
                url: url, method: "POST", headers: headers, body: body)
            try rejectFailure(status: response.status, endpoint: endpoint)
            let payload = try ProviderHTTP.decodeObject(response.body, endpoint: endpoint)
            return try credentials(from: payload, endpoint: endpoint, now: now)
        } catch let error as HTTPError {
            switch error {
            case .status(let code, _):
                try rejectFailure(status: code, endpoint: endpoint)
                throw ProviderError.noData("\(endpoint) answered HTTP \(code)")
            case .rateLimited:
                throw ProviderError.noData("\(endpoint) is rate limiting this account")
            default:
                throw ProviderError.noData("\(endpoint) could not be reached")
            }
        }
    }

    private static func rejectFailure(status: Int, endpoint: String) throws {
        if status == 400 || status == 401 || status == 403 {
            throw ProviderError.unauthorised
        }
        guard (200...299).contains(status) else {
            throw ProviderError.noData("\(endpoint) answered HTTP \(status)")
        }
    }
}

// MARK: - Codex

extension CodexClient {
    public func refresh(credentials: OAuthCredentials) async throws -> OAuthCredentials {
        try await TokenExchange.post(
            httpClient,
            url: ProviderEndpoints.Codex.tokenURL,
            headers: [
                "Accept": "application/json",
                "Content-Type": "application/x-www-form-urlencoded",
                "User-Agent": ProviderEndpoints.Codex.userAgent,
            ],
            body: TokenExchange.formBody([
                "client_id": ProviderEndpoints.Codex.clientID,
                "grant_type": "refresh_token",
                "refresh_token": try TokenExchange.refreshToken(of: credentials),
                // Narrower than the scope the device flow authorised. The refresh grant is
                // rejected outright if it asks for more than it is entitled to, and the quota
                // endpoints need none of the extra scopes.
                "scope": ProviderEndpoints.Codex.refreshScope,
            ]),
            endpoint: "codex token",
            now: now())
    }
}

// MARK: - Claude

extension ClaudeClient {
    public func refresh(credentials: OAuthCredentials) async throws -> OAuthCredentials {
        // Anthropic's token endpoint takes JSON, not the form encoding RFC 6749 specifies and
        // the other three providers here use. Serialised rather than interpolated, so a token
        // containing a quote cannot reshape the request.
        let body = try JSONSerialization.data(
            withJSONObject: [
                "grant_type": "refresh_token",
                "refresh_token": try TokenExchange.refreshToken(of: credentials),
                "client_id": ProviderEndpoints.Claude.clientID,
                "scope": ProviderEndpoints.Claude.scope,
            ],
            options: [])

        return try await TokenExchange.post(
            httpClient,
            url: ProviderEndpoints.Claude.tokenURL,
            headers: [
                "Accept": "application/json",
                "Content-Type": "application/json",
            ],
            body: body,
            endpoint: "claude token",
            now: now())
    }
}

// MARK: - Antigravity

extension AntigravityClient {
    public func refresh(credentials: OAuthCredentials) async throws -> OAuthCredentials {
        let stored = try TokenExchange.refreshToken(of: credentials)

        let refreshed = try await TokenExchange.post(
            httpClient,
            url: ProviderEndpoints.Antigravity.tokenEndpoint,
            headers: [
                "Accept": "application/json",
                "Content-Type": "application/x-www-form-urlencoded",
            ],
            body: TokenExchange.formBody([
                "client_id": ProviderEndpoints.Antigravity.clientID,
                "client_secret": ProviderEndpoints.Antigravity.clientSecret,
                "grant_type": "refresh_token",
                "refresh_token": stored,
            ]),
            endpoint: "google token",
            now: now())

        // Google never returns a refresh token on a refresh grant; the original stays valid
        // until it is revoked. The engine's merge would carry it forward anyway, but stating it
        // here means an adapter used on its own cannot deauthenticate an account after an hour.
        return OAuthCredentials(
            accessToken: refreshed.accessToken,
            refreshToken: refreshed.refreshToken ?? stored,
            idToken: refreshed.idToken ?? credentials.idToken,
            expiresAt: refreshed.expiresAt,
            scope: refreshed.scope ?? credentials.scope)
    }
}

// MARK: - xAI

extension XaiClient {
    public func refresh(credentials: OAuthCredentials) async throws -> OAuthCredentials {
        try await TokenExchange.post(
            httpClient,
            url: try await tokenEndpoint(),
            headers: [
                "Accept": "application/json",
                "Content-Type": "application/x-www-form-urlencoded",
                "User-Agent": ProviderEndpoints.Xai.userAgent,
            ],
            body: TokenExchange.formBody([
                "grant_type": "refresh_token",
                "client_id": ProviderEndpoints.Xai.clientID,
                "refresh_token": try TokenExchange.refreshToken(of: credentials),
            ]),
            endpoint: "xai token",
            now: now())
    }

    /// Discovers the token endpoint rather than hardcoding it, then checks where it points.
    ///
    /// xAI publishes an OpenID discovery document and has moved these endpoints before, so
    /// reading it is what keeps the client working across a move. The document is fetched over
    /// TLS from the issuer, but it is still network-supplied data naming a URL this app is about
    /// to post a refresh token to — so the host is checked before a single byte is sent.
    private func tokenEndpoint() async throws -> String {
        let response = try await ProviderHTTP.request(
            httpClient,
            url: ProviderEndpoints.Xai.discoveryURL,
            headers: ["Accept": "application/json"],
            endpoint: "xai discovery")
        let payload = try ProviderHTTP.decodeObject(response.body, endpoint: "xai discovery")

        guard let endpoint = JSONSupport.string(payload, "token_endpoint", "tokenEndpoint") else {
            throw ProviderError.malformedPayload("xai discovery named no token endpoint")
        }
        return try Self.validated(endpoint)
    }

    /// HTTPS, and a host that is exactly the issuer or a dot-anchored subdomain of it.
    ///
    /// The anchor is the whole point: a suffix check without the dot accepts `notx.ai`, and one
    /// without the host being the *end* of the name accepts `x.ai.example.com`. Either would
    /// send the account's refresh token to whoever registered the lookalike.
    static func validated(_ rawURL: String) throws -> String {
        guard let components = URLComponents(string: rawURL),
              components.scheme?.lowercased() == "https",
              // Userinfo is refused outright. `https://x.ai@evil.example` is already caught by
              // the host check — URLComponents puts `evil.example` in `host` — but
              // `https://anything@auth.x.ai` passes it, and some HTTP stacks turn that userinfo
              // into a Basic-auth header on a request that is about to carry a refresh token.
              // The endpoints this validates never legitimately carry credentials in the URL.
              components.user == nil, components.password == nil,
              let host = components.host?.lowercased(),
              host == ProviderEndpoints.Xai.issuerHost
                || host.hasSuffix(".\(ProviderEndpoints.Xai.issuerHost)")
        else {
            // The rejected URL is not echoed: it is attacker-controllable, and this message is
            // shown on the account card.
            throw ProviderError.malformedPayload(
                "xai discovery named an endpoint outside \(ProviderEndpoints.Xai.issuerHost)")
        }
        // The re-composed URL, not the string that came in. The checks above ran against the
        // PARSED view, so returning the original would send bytes nobody inspected — whatever a
        // lenient parser happened to tolerate around the parts that were checked.
        //
        // The query survives on purpose: `verification_uri_complete` is a sign-in page with the
        // user's code already in it, and stripping the query would turn the one URL that saves
        // the user typing into the one that does not work.
        guard let normalised = components.url?.absoluteString else {
            throw ProviderError.malformedPayload("xai discovery named an unusable endpoint")
        }
        return normalised
    }
}
