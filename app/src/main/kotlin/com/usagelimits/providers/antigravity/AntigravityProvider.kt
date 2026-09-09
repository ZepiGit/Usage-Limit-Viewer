package com.usagelimits.providers.antigravity

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Antigravity
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
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder

/**
 * Google Antigravity.
 *
 * Login is Google's ordinary authorization-code flow with PKCE, received on a loopback
 * redirect (RFC 8252 §7.3). Unlike Codex there is no device-code alternative for this client,
 * and the redirect URI is pinned in Google's client registration, so the app has to bind
 * [Antigravity.REDIRECT_PORT] and hand the user off to a real browser.
 *
 * Reading quota takes two steps rather than one: the summary endpoint is addressed by GCP
 * project, and the project backing an Antigravity subscription is not something the user
 * knows or types. It is resolved once at login via `loadCodeAssist` and stored on the account
 * as [ATTR_PROJECT_ID].
 */
class AntigravityProvider(
    private val http: HttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : UsageProvider {

    override val providerId = ProviderId.ANTIGRAVITY

    /**
     * PKCE pair and CSRF state for the login currently in flight.
     *
     * Held here rather than inside [LoginChallenge.Redirect] because the challenge is passed
     * through the UI layer, and the verifier is the one value in this flow that must never
     * leave the process. `@Volatile` because begin and complete may run on different threads.
     */
    @Volatile
    private var pendingPkce: PkceCodes? = null

    @Volatile
    private var pendingState: String? = null

    override suspend fun beginLogin(): LoginChallenge {
        val codes = Pkce.generate()
        val state = Pkce.generateState()
        pendingPkce = codes
        pendingState = state

        return LoginChallenge.Redirect(
            authorizationUrl = authorizationUrl(codes, state),
            redirectUri = Antigravity.REDIRECT_URI,
        )
    }

    /**
     * `access_type=offline` and `prompt=consent` are both load-bearing.
     *
     * Without the first Google issues no refresh token at all; without the second it withholds
     * one on every login after the first, because it assumes the caller kept the original.
     * This app stores credentials per account and a re-login means the old set is gone, so it
     * has to ask for consent again each time or the account silently expires in an hour.
     */
    private fun authorizationUrl(codes: PkceCodes, state: String): String {
        val params = mapOf(
            "client_id" to Antigravity.CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to Antigravity.REDIRECT_URI,
            "scope" to Antigravity.SCOPES.joinToString(" "),
            "state" to state,
            "code_challenge" to codes.codeChallenge,
            "code_challenge_method" to codes.codeChallengeMethod,
            "access_type" to "offline",
            "prompt" to "consent",
        )
        return params.entries.joinToString("&", prefix = "${Antigravity.AUTH_ENDPOINT}?") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
    }

    override suspend fun completeLogin(
        challenge: LoginChallenge,
        redirectResponse: String?,
    ): OAuthCredentials {
        require(challenge is LoginChallenge.Redirect) { "Antigravity uses the redirect flow" }

        val codes = pendingPkce
        val expectedState = pendingState
        if (codes == null || expectedState == null) {
            throw ProviderException.LoginCancelled("No login in progress")
        }

        val server = LoopbackServer(Antigravity.REDIRECT_PORT)
        val redirect = try {
            server.start()
            server.awaitRedirect(REDIRECT_TIMEOUT_MS)
        } finally {
            // The socket must not outlive the attempt: a stale listener would make the next
            // login fail to bind the pinned port.
            server.close()
        }

        redirect.error?.let {
            throw ProviderException.LoginCancelled(redirect.errorDescription ?: it)
        }

        val returnedState = redirect.state
            ?: throw ProviderException.LoginCancelled("Redirect carried no state")
        if (!Pkce.constantTimeEquals(expectedState, returnedState)) {
            throw ProviderException.LoginCancelled("Redirect state did not match the request")
        }

        val code = redirect.code
            ?: throw ProviderException.LoginCancelled("Redirect carried no authorization code")

        return exchangeCode(code, codes.codeVerifier).also {
            pendingPkce = null
            pendingState = null
        }
    }

    /**
     * Exchanges the authorization code.
     *
     * `client_secret` is Google's *public* installed-application secret (RFC 8252 §8.5): it
     * ships inside the desktop client and is not treated as confidential by Google, whose
     * token endpoint nonetheless rejects the exchange without it for this client type. PKCE
     * is what actually protects the flow — the code is worthless without the verifier that
     * never left this device. The Antigravity quota scopes are bound to this specific client
     * id, so registering a fresh Android client would simply not be able to reach them.
     */
    private suspend fun exchangeCode(code: String, codeVerifier: String): OAuthCredentials {
        val response = http.request(
            url = Antigravity.TOKEN_ENDPOINT,
            method = "POST",
            headers = mapOf("Accept" to "application/json"),
            body = HttpClient.formBody(
                mapOf(
                    "code" to code,
                    "client_id" to Antigravity.CLIENT_ID,
                    "client_secret" to Antigravity.CLIENT_SECRET,
                    "redirect_uri" to Antigravity.REDIRECT_URI,
                    "grant_type" to "authorization_code",
                    "code_verifier" to codeVerifier,
                ),
            ),
        )
        return toCredentials(JsonSupport.parseObject(response.body))
    }

    override suspend fun refresh(credentials: OAuthCredentials): OAuthCredentials {
        val refreshToken = credentials.refreshToken
            ?: throw ProviderException.Unauthorized("No refresh token stored")

        val response = http.request(
            url = Antigravity.TOKEN_ENDPOINT,
            method = "POST",
            headers = mapOf("Accept" to "application/json"),
            body = HttpClient.formBody(
                mapOf(
                    "client_id" to Antigravity.CLIENT_ID,
                    "client_secret" to Antigravity.CLIENT_SECRET,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token",
                ),
            ),
        )

        // Google never returns a refresh token on a refresh; the original stays valid until it
        // is revoked, so carry it forward or the account would deauthenticate after one hour.
        return toCredentials(JsonSupport.parseObject(response.body))
            .copy(refreshToken = refreshToken)
    }

    override suspend fun fetchProfile(credentials: OAuthCredentials): ProviderProfile {
        val response = http.request(
            url = Antigravity.USERINFO_ENDPOINT,
            headers = mapOf(
                "Authorization" to "Bearer ${credentials.accessToken}",
                "Accept" to "application/json",
            ),
        )
        val payload = JsonSupport.parseObject(response.body)

        val email = JsonSupport.string(payload, "email")
        // Google's `id` is the stable subject; the address can be changed on the account.
        val accountId = JsonSupport.string(payload, "id")
            ?: email
            ?: throw ProviderException.MalformedPayload("userinfo had no account identifier")

        return ProviderProfile(
            externalAccountId = accountId,
            email = email,
            displayName = JsonSupport.string(payload, "name"),
            // Antigravity does not report a plan name; the quota groups are the plan signal.
            plan = null,
            attributes = mapOf(ATTR_PROJECT_ID to resolveProjectId(credentials)),
        )
    }

    /**
     * Resolves the GCP project the subscription's quota is billed against.
     *
     * Done at login and stored, because the quota summary is addressed by project and there
     * is no lookup from an account to a project at fetch time. A failure here is fatal to the
     * account rather than to one refresh: without a project id the app can authenticate but
     * can never read a number.
     */
    private suspend fun resolveProjectId(credentials: OAuthCredentials): String {
        val response = http.request(
            url = Antigravity.LOAD_CODE_ASSIST_URL,
            method = "POST",
            headers = authenticatedJsonHeaders(credentials),
            body = HttpClient.jsonBody(LOAD_CODE_ASSIST_BODY),
        )
        val payload = JsonSupport.parseObject(response.body)

        return JsonSupport.string(payload, "cloudaicompanionProject", "cloudaicompanion_project")
            ?: JsonSupport.string(payload, "projectId", "project_id")
            ?: JsonSupport.string(payload, "project")
            ?: throw ProviderException.MalformedPayload(
                "loadCodeAssist returned no GCP project id; this account may not have Antigravity enabled",
            )
    }

    override suspend fun fetchUsage(
        account: ProviderAccount,
        credentials: OAuthCredentials,
    ): UsageResult {
        val projectId = account.attributes[ATTR_PROJECT_ID]
            ?: throw ProviderException.MalformedPayload("no project id for this account")

        val body = HttpClient.jsonBody(
            JsonSupport.json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject { put("project", projectId) },
            ),
        )

        // The quota hosts are rolled out at different speeds and which one serves a given
        // account varies, so they are tried in order and only the last failure is reported.
        var lastError: ProviderException? = null
        for (url in Antigravity.QUOTA_URLS) {
            try {
                val response = http.request(
                    url = url,
                    method = "POST",
                    headers = authenticatedJsonHeaders(credentials),
                    body = body,
                )
                val payload = JsonSupport.parseObject(response.body)
                return UsageResult(windows = AntigravityQuotaParser.parse(payload, nowMs()))
            } catch (e: ProviderException) {
                lastError = e
            }
        }
        throw lastError ?: ProviderException.Unexpected("no Antigravity quota host configured")
    }

    private fun authenticatedJsonHeaders(credentials: OAuthCredentials): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${credentials.accessToken}",
        "Content-Type" to "application/json",
        // The internal endpoints vary their response by client; this is a compatibility
        // marker, not an attempt to disguise the app as something else.
        "User-Agent" to Antigravity.USER_AGENT,
    )

    private fun toCredentials(payload: JsonObject): OAuthCredentials {
        val accessToken = JsonSupport.string(payload, "access_token", "accessToken")
            ?: throw ProviderException.MalformedPayload("token response had no access_token")
        val expiresIn = JsonSupport.long(payload, "expires_in", "expiresIn")

        return OAuthCredentials(
            accessToken = accessToken,
            refreshToken = JsonSupport.string(payload, "refresh_token", "refreshToken"),
            idToken = JsonSupport.string(payload, "id_token", "idToken"),
            expiresAt = expiresIn?.let { nowMs() + it * 1000 },
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        /** Account attribute holding the GCP project the quota summary is queried against. */
        const val ATTR_PROJECT_ID = "project_id"

        /** Google's consent screen is slow and the user may switch accounts; five minutes. */
        private const val REDIRECT_TIMEOUT_MS = 5L * 60 * 1000

        /**
         * `loadCodeAssist` requires a metadata block naming the calling IDE. The unspecified
         * values are what a non-IDE client sends; the endpoint rejects an empty body.
         */
        private val LOAD_CODE_ASSIST_BODY = JsonSupport.json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                putJsonObject("metadata") {
                    // ideType selects which product's onboarding record Cloud Code returns.
                    // IDE_UNSPECIFIED is the *Gemini Code Assist* value; sending it here
                    // returns the Gemini view, whose project carries none of the Antigravity
                    // quota groups. CLIProxyAPI-Quota-Inspector keeps both maps side by side
                    // (providers.go:43-52), which is what makes the distinction unambiguous.
                    put("ideType", "ANTIGRAVITY")
                    put("platform", "PLATFORM_UNSPECIFIED")
                    put("pluginType", "GEMINI")
                }
            },
        )
    }
}
