package com.usagelimits.providers.claude

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Claude
import com.usagelimits.core.network.ProviderException
import com.usagelimits.core.oauth.LoopbackServer
import com.usagelimits.core.oauth.Pkce
import com.usagelimits.core.oauth.PkceCodes
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Claude / Anthropic subscription.
 *
 * Login is the authorization-code flow with PKCE, redirecting to a loopback listener on the
 * fixed port Anthropic's client registration pins (RFC 8252 §7.3). The browser does the
 * authenticating, so the app never sees the user's password.
 *
 * **Known risk, accepted deliberately.** CLIProxyAPI — the reference this provider was read
 * from — wraps its Claude requests in uTLS TLS-fingerprint mimicry plus strict header
 * ordering, because Anthropic sits behind bot detection. This app deliberately does NOT do
 * that: it sends a plain, honest HTTPS request. If Anthropic's edge rejects it, the correct
 * outcome is a clear error surfaced to the user, NOT an evasion attempt.
 */
class ClaudeProvider(
    private val http: HttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : UsageProvider {

    override val providerId = ProviderId.CLAUDE

    // LoginChallenge.Redirect carries only the URL and the redirect target, and the token
    // exchange happens in a second call that still needs the verifier and the state. They are
    // therefore held on the instance across the browser round-trip. They stay in memory, are
    // never persisted or logged, and are cleared the moment the redirect comes back.
    private var server: LoopbackServer? = null
    private var pendingCodes: PkceCodes? = null
    private var pendingState: String? = null

    override suspend fun beginLogin(): LoginChallenge {
        // Bind the port BEFORE the authorization URL is handed back, so a conflict is
        // reported instead of the browser being sent to mint a real authorization code
        // that then has nowhere to land — or worse, lands on whatever squatted the port.
        val boundServer = LoopbackServer(Claude.REDIRECT_PORT).also { it.start() }

        val codes = Pkce.generate()
        val state = Pkce.generateState()
        pendingCodes = codes
        pendingState = state
        server = boundServer

        val params = linkedMapOf(
            // Selects the code-returning variant of the authorize endpoint. Without it the
            // flow fails later and opaquely, so it is first and explicit.
            "code" to "true",
            "client_id" to Claude.CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to Claude.REDIRECT_URI,
            "scope" to Claude.SCOPE,
            "code_challenge" to codes.codeChallenge,
            "code_challenge_method" to codes.codeChallengeMethod,
            "state" to state,
        )

        val authorizationUrl = params.entries.joinToString(
            separator = "&",
            prefix = "${Claude.AUTHORIZE_URL}?",
        ) { (name, value) -> "${urlEncode(name)}=${urlEncode(value)}" }

        return LoginChallenge.Redirect(
            authorizationUrl = authorizationUrl,
            redirectUri = Claude.REDIRECT_URI,
        )
    }

    override suspend fun completeLogin(
        challenge: LoginChallenge,
        userInput: String?,
    ): OAuthCredentials {
        require(challenge is LoginChallenge.Redirect) { "Claude uses the redirect flow" }

        val codes = pendingCodes
        val expectedState = pendingState
        if (codes == null || expectedState == null) {
            throw ProviderException.Unexpected("Login was not started on this provider instance")
        }

        val listener = server
            ?: throw ProviderException.Unexpected("login was not started")
        val response = try {
            listener.awaitRedirect(REDIRECT_TIMEOUT_MS)
        } finally {
            // One redirect, one attempt: the port is released and the verifier discarded even
            // when the browser never comes back.
            listener.close()
            server = null
            pendingCodes = null
            pendingState = null
        }

        response.error?.let { error ->
            throw ProviderException.LoginCancelled(response.errorDescription ?: error)
        }
        val rawCode = response.code
            ?: throw ProviderException.LoginCancelled("No authorization code was returned")

        // Anthropic sometimes hands back "code#state" in a single parameter. The token
        // endpoint rejects the code with the fragment still attached, and when a fragment is
        // present it — not the query parameter — is the state the exchange must echo.
        val code = rawCode.substringBefore('#')
        val fragmentState = rawCode.substringAfter('#', "").takeIf { it.isNotBlank() }

        // The CSRF control for the whole flow. Every state value the browser returned, through
        // whichever channel, has to be the one this instance generated.
        val returnedStates = listOfNotNull(response.state, fragmentState)
        if (returnedStates.isEmpty() ||
            returnedStates.any { !Pkce.constantTimeEquals(expectedState, it) }
        ) {
            throw ProviderException.Unexpected("state mismatch")
        }

        return exchangeCode(
            code = code,
            codeVerifier = codes.codeVerifier,
            state = fragmentState ?: expectedState,
        )
    }

    // Internal so the one-time-grant rule on this exchange can be tested directly; the only
    // other route in is a complete device or loopback login, which no unit test can drive.
    internal suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        state: String,
    ): OAuthCredentials {
        val response = http.request(
            url = Claude.TOKEN_URL,
            method = "POST",
            headers = tokenHeaders(),
            // Anthropic's token endpoint takes JSON, not the form encoding RFC 6749 specifies
            // and the other providers here use.
            body = HttpClient.jsonBody(
                jsonText(
                    buildJsonObject {
                        put("grant_type", "authorization_code")
                        put("code", code)
                        put("redirect_uri", Claude.REDIRECT_URI)
                        put("client_id", Claude.CLIENT_ID)
                        put("code_verifier", codeVerifier)
                        put("state", state)
                    },
                ),
            ),
            // An authorization code is spent by ARRIVING at the endpoint, exactly like a
            // rotating refresh grant — and the refresh path already says so. This one did
            // not, so a 502 from something in front of the token endpoint had the client
            // re-present the code, which the server then refused as consumed, and a sign-in
            // that had in fact succeeded reported a failure.
            oneTimeGrant = true,
        )
        return toCredentials(JsonSupport.parseObject(response.body))
    }

    override suspend fun refresh(credentials: OAuthCredentials): OAuthCredentials {
        val refreshToken = credentials.refreshToken
            ?: throw ProviderException.Unauthorized("No refresh token stored")

        val response = http.request(
            url = Claude.TOKEN_URL,
            method = "POST",
            headers = tokenHeaders(),
            body = HttpClient.jsonBody(
                jsonText(
                    buildJsonObject {
                        put("grant_type", "refresh_token")
                        put("refresh_token", refreshToken)
                        put("client_id", Claude.CLIENT_ID)
                        put("scope", Claude.SCOPE)
                    },
                ),
            ),
            // A dead refresh token, not a malformed request: see `badRequestMeansExpired`.
            badRequestMeansExpired = true,
            // A rotating refresh grant is spent on arrival; see HttpClient.oneTimeGrant.
            oneTimeGrant = true,
        )

        val refreshed = toCredentials(JsonSupport.parseObject(response.body))
        // A refresh response may omit the refresh token, meaning "keep using the old one".
        return refreshed.copy(refreshToken = refreshed.refreshToken ?: refreshToken)
    }

    override suspend fun fetchProfile(credentials: OAuthCredentials): ProviderProfile {
        val response = http.request(url = Claude.PROFILE_URL, headers = apiHeaders(credentials))
        val payload = JsonSupport.parseObject(response.body)
        val account = JsonSupport.obj(payload, "account")

        val email = JsonSupport.string(account, "email")
        // The uuid is the stable key. Email is only a fallback so an account whose profile
        // omits the uuid can still be stored and re-synced instead of failing to add.
        val accountId = JsonSupport.string(account, "uuid")
            ?: email
            ?: throw ProviderException.MalformedPayload("profile had no account identifier")

        return ProviderProfile(
            externalAccountId = accountId,
            email = email,
            displayName = JsonSupport.string(account, "display_name", "displayName")
                ?: JsonSupport.string(account, "full_name", "fullName"),
            plan = ClaudeUsageParser.parsePlan(payload),
        )
    }

    override suspend fun fetchUsage(
        account: ProviderAccount,
        credentials: OAuthCredentials,
    ): UsageResult {
        val response = http.request(url = Claude.USAGE_URL, headers = apiHeaders(credentials))
        val payload = JsonSupport.parseObject(response.body)
        // Claude exposes no reset credits, so the list stays empty rather than being faked.
        return UsageResult(windows = ClaudeUsageParser.parse(payload, nowMs()))
    }

    /** Headers for the OAuth-authenticated read endpoints (profile and usage). */
    private fun apiHeaders(credentials: OAuthCredentials): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${credentials.accessToken}",
        // Gates the OAuth-token surface of api.anthropic.com; without it these paths 404.
        "anthropic-beta" to Claude.BETA_HEADER,
        "Content-Type" to "application/json",
        "Accept" to "application/json",
    )

    private fun tokenHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "User-Agent" to TOKEN_USER_AGENT,
    )

    private fun toCredentials(payload: JsonObject): OAuthCredentials {
        val accessToken = JsonSupport.string(payload, "access_token", "accessToken")
            ?: throw ProviderException.MalformedPayload("token response had no access_token")
        val expiresIn = JsonSupport.long(payload, "expires_in", "expiresIn")
        return OAuthCredentials(
            accessToken = accessToken,
            refreshToken = JsonSupport.string(payload, "refresh_token", "refreshToken"),
            idToken = JsonSupport.string(payload, "id_token", "idToken"),
            expiresAt = JsonSupport.expiryAfterSeconds(expiresIn, nowMs()),
        )
    }

    private fun jsonText(payload: JsonObject): String =
        JsonSupport.json.encodeToString(JsonObject.serializer(), payload)

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        /** How long the user has in the browser before the loopback listener gives up. */
        const val REDIRECT_TIMEOUT_MS = 5L * 60 * 1000

        /**
         * The token endpoint refuses requests with no user agent, and the first-party client
         * is an axios app. Sent only on the two token calls, where it is required; the read
         * endpoints get no such header.
         */
        const val TOKEN_USER_AGENT = "axios/1.15.2"
    }
}
