package com.usagelimits.providers.xai

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Xai
import com.usagelimits.core.network.ProviderException
import com.usagelimits.core.oauth.JwtClaims
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.net.URI

/**
 * xAI / Grok subscription.
 *
 * Login is the RFC 8628 device authorization grant, which is the best of the four flows here
 * on a phone: there is no redirect, so no loopback port to bind and nothing to survive the app
 * being backgrounded while the browser is open. The user types a short code on x.ai and the
 * app polls.
 *
 * Endpoints are not hardcoded — they come from xAI's OIDC discovery document. That is a
 * document fetched over the network, so both endpoints it names are checked against
 * [validateEndpoint] before a single byte is sent to them: a tampered or stale discovery
 * response must not be able to redirect token traffic to a host of its choosing.
 */
class XaiProvider(
    private val http: HttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : UsageProvider {

    override val providerId = ProviderId.XAI

    /** xAI has no reset-credit facility; the app does not invent one. */
    override val supportsResetCredits = false

    /**
     * Accepts an endpoint only if it is an https URL on x.ai or a subdomain of it, and returns
     * it unchanged so callers can use the result directly.
     *
     * This is the control that makes runtime discovery safe. Without it, anything that could
     * influence the discovery document — a compromised CDN, a captive portal, a stale cached
     * response — could name its own `token_endpoint` and the app would post the refresh token
     * straight to it. Plain http is refused for the same reason [HttpClient] refuses it:
     * bearer material never crosses an unencrypted hop.
     *
     * Deliberately pure and network-free so the rule is directly unit-testable.
     */
    internal fun validateEndpoint(rawUrl: String, field: String): String {
        // java.net.URI, not android.net.Uri: this has to run in a plain JVM unit test.
        val uri = runCatching { URI(rawUrl) }.getOrNull()
            ?: throw ProviderException.Unexpected("$field is not a valid URL")

        if (!"https".equals(uri.scheme, ignoreCase = true)) {
            throw ProviderException.Unexpected("$field must be an https URL")
        }

        val host = uri.host?.lowercase()
            ?: throw ProviderException.Unexpected("$field has no host")

        // Exact match or a dot-anchored suffix. The anchor is what stops "notx.ai" and
        // "x.ai.example.com" from passing as xAI hosts.
        if (host != ISSUER_HOST && !host.endsWith(".$ISSUER_HOST")) {
            throw ProviderException.Unexpected("$field is not an $ISSUER_HOST endpoint")
        }

        return rawUrl
    }

    /** Resolves the device authorization and token endpoints. Both are validated first. */
    private suspend fun discover(): Pair<String, String> {
        val response = http.request(url = Xai.DISCOVERY_URL, headers = oauthHeaders())
        val payload = JsonSupport.parseObject(response.body)

        val deviceEndpoint = JsonSupport.string(
            payload,
            "device_authorization_endpoint",
            "deviceAuthorizationEndpoint",
        ) ?: throw ProviderException.MalformedPayload(
            "discovery document had no device authorization endpoint",
        )
        val tokenEndpoint = JsonSupport.string(payload, "token_endpoint", "tokenEndpoint")
            ?: throw ProviderException.MalformedPayload("discovery document had no token endpoint")

        return Pair(
            validateEndpoint(deviceEndpoint, "device_authorization_endpoint"),
            validateEndpoint(tokenEndpoint, "token_endpoint"),
        )
    }

    override suspend fun beginLogin(): LoginChallenge {
        val (deviceEndpoint, tokenEndpoint) = discover()

        val response = http.request(
            url = deviceEndpoint,
            method = "POST",
            headers = oauthHeaders(),
            body = HttpClient.formBody(
                mapOf(
                    "client_id" to Xai.CLIENT_ID,
                    "scope" to Xai.SCOPE,
                ),
            ),
        )
        val payload = JsonSupport.parseObject(response.body)

        val userCode = JsonSupport.string(payload, "user_code", "userCode")
            ?: throw ProviderException.MalformedPayload("device response had no user code")
        val deviceCode = JsonSupport.string(payload, "device_code", "deviceCode")
            ?: throw ProviderException.MalformedPayload("device response had no device code")
        val verificationUri = JsonSupport.string(payload, "verification_uri", "verificationUri")
            ?: throw ProviderException.MalformedPayload("device response had no verification URI")

        val expiresIn = JsonSupport.long(payload, "expires_in", "expiresIn")
            ?: DEFAULT_EXPIRES_SECONDS
        val interval = JsonSupport.long(payload, "interval") ?: MIN_POLL_SECONDS

        return LoginChallenge.DeviceCode(
            // Only the first segment is ever shown. The device code and the resolved token
            // endpoint ride along so completeLogin stays stateless and cannot end up polling
            // an endpoint a second discovery call might have changed under it.
            userCode = listOf(userCode, deviceCode, tokenEndpoint).joinToString(CODE_SEPARATOR),
            // Both URLs are opened in the user's browser under a screen that tells them to
            // sign in there, so they get the app's endorsement. They arrive from the same
            // provider response as everything else and are held to the same host rule as the
            // token endpoint — otherwise a compromised or MITM'd discovery response could
            // point the user at a phishing page the app has just vouched for.
            verificationUri = validateEndpoint(verificationUri, "verification_uri"),
            verificationUriComplete = JsonSupport.string(
                payload,
                "verification_uri_complete",
                "verificationUriComplete",
            )?.let { validateEndpoint(it, "verification_uri_complete") },
            expiresAt = JsonSupport.expiryAfterSeconds(expiresIn, nowMs())
                ?: (nowMs() + DEFAULT_EXPIRES_SECONDS * 1000),
            // The provider's interval is honoured but never allowed below the floor, so a
            // bad or missing value cannot turn the poll into a hot loop.
            pollIntervalMs = JsonSupport.secondsToMillis(maxOf(interval, MIN_POLL_SECONDS))
                ?: MIN_POLL_SECONDS * 1000,
        )
    }

    /**
     * Polls the token endpoint until the user approves, denies, or the code expires.
     *
     * Reads OAuth error bodies on both 2xx and HTTP 400/403, so a denial stops immediately
     * and slow_down changes every subsequent wait. The HTTP layer does not retry polls.
     */
    override suspend fun completeLogin(
        challenge: LoginChallenge,
        userInput: String?,
    ): OAuthCredentials {
        require(challenge is LoginChallenge.DeviceCode) { "xAI uses the device flow" }
        val (_, deviceCode, packedEndpoint) = splitChallenge(challenge.userCode)

        // Re-checked rather than trusted: the challenge is a value object that may have been
        // held across a process death, and validation is cheap.
        val tokenEndpoint = validateEndpoint(packedEndpoint, "token_endpoint")

        val fields = mapOf(
            "grant_type" to Xai.DEVICE_CODE_GRANT_TYPE,
            "device_code" to deviceCode,
            "client_id" to Xai.CLIENT_ID,
        )

        var intervalMs = challenge.pollIntervalMs
        while (nowMs() < challenge.expiresAt) {
            val payload = try {
                val response = http.request(
                    url = tokenEndpoint,
                    method = "POST",
                    headers = oauthHeaders(),
                    body = HttpClient.formBody(fields),
                    // Pending is expressed as a failed status, so the generic retry must not
                    // absorb it — poll timing belongs to this loop.
                    retries = 0,
                    devicePoll = true,
                )
                JsonSupport.parseObject(response.body).also { payload ->
                    if (response.statusCode !in 200..299 && JsonSupport.string(payload, "error") == null) {
                        throw ProviderException.MalformedPayload("device error response had no error code")
                    }
                }
            } catch (e: ProviderException.Offline) {
                null
            } catch (e: ProviderException.ServerError) {
                null
            }

            if (payload == null) {
                delay(intervalMs)
                continue
            }

            when (val error = JsonSupport.string(payload, "error")) {
                null -> return toCredentials(payload, tokenEndpoint)

                ERROR_AUTHORIZATION_PENDING -> delay(intervalMs)

                ERROR_SLOW_DOWN -> {
                    // RFC 8628 §3.5: the increase is permanent for the rest of the poll, not
                    // just for this attempt.
                    intervalMs = Math.addExact(intervalMs, SLOW_DOWN_STEP_MS)
                    delay(intervalMs)
                }

                ERROR_EXPIRED_TOKEN, ERROR_ACCESS_DENIED ->
                    throw ProviderException.LoginCancelled("Device login was not completed ($error)")

                // An unknown terminal code stops the loop rather than polling to expiry
                // against an endpoint that has already made up its mind.
                else -> throw ProviderException.Unexpected("device authorization was refused")
            }
        }

        throw ProviderException.LoginCancelled("Device login expired before it was approved")
    }

    override suspend fun refresh(credentials: OAuthCredentials): OAuthCredentials {
        val refreshToken = credentials.refreshToken
            ?: throw ProviderException.Unauthorized("No refresh token stored")

        // The endpoint was pinned at login so a refresh costs one request, not two. It is
        // still re-validated: it comes back out of the credential store, and stored input is
        // treated as untrusted. Only a credential set from before that pinning re-discovers.
        val tokenEndpoint = credentials.tokenEndpoint
            ?.let { validateEndpoint(it, "token_endpoint") }
            ?: discover().second

        val response = http.request(
            url = tokenEndpoint,
            method = "POST",
            headers = oauthHeaders(),
            body = HttpClient.formBody(
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to Xai.CLIENT_ID,
                    "refresh_token" to refreshToken,
                ),
            ),
            // A dead refresh token, not a malformed request: see `badRequestMeansExpired`.
            badRequestMeansExpired = true,
            // A rotating refresh grant is spent on arrival; see HttpClient.oneTimeGrant.
            oneTimeGrant = true,
        )

        val refreshed = toCredentials(JsonSupport.parseObject(response.body), tokenEndpoint)
        // A refresh response may omit the refresh token, meaning "keep using the old one".
        return refreshed.copy(refreshToken = refreshed.refreshToken ?: refreshToken)
    }

    /**
     * Identity, preferring the ID token.
     *
     * The claims arrived over TLS from the token endpoint in response to a request this app
     * made, so reading them costs no extra round-trip and no extra token exposure. `/v1/me` is
     * the fallback for the case where xAI issues no ID token for the granted scopes.
     */
    override suspend fun fetchProfile(credentials: OAuthCredentials): ProviderProfile {
        val claims = JwtClaims.parse(credentials.idToken)
        val subject = JwtClaims.string(claims, "sub")
        val email = JwtClaims.string(claims, "email")

        // `sub` is the stable key; the address is only a fallback, because a user can change
        // it while the account stays the same.
        val accountId = subject ?: email
        if (accountId == null) return fetchProfileFromApi(credentials)

        return ProviderProfile(
            externalAccountId = accountId,
            email = email,
            displayName = JwtClaims.string(claims, "name"),
            // xAI meters money, not a named tier, so neither the token nor the billing
            // payloads carry a plan. Left null rather than guessed at.
            plan = null,
        )
    }

    private suspend fun fetchProfileFromApi(credentials: OAuthCredentials): ProviderProfile {
        val response = http.request(
            url = Xai.ME_URL,
            headers = mapOf(
                "Authorization" to "Bearer ${credentials.accessToken}",
                "Accept" to "application/json",
            ),
        )
        val payload = JsonSupport.parseObject(response.body)

        val email = JsonSupport.string(payload, "email")
        val accountId = JsonSupport.string(payload, "id", "sub", "user_id", "userId")
            ?: email
            ?: throw ProviderException.MalformedPayload("profile had no account identifier")

        return ProviderProfile(
            externalAccountId = accountId,
            email = email,
            displayName = JsonSupport.string(payload, "name", "display_name", "displayName"),
            plan = null,
        )
    }

    /**
     * Reads both billing views.
     *
     * They are independent: the weekly credit view and the monthly spend view answer
     * separately and one can fail while the other works. Losing one costs its rows, not the
     * refresh — the account still shows real numbers for whatever answered. Only when both
     * are gone is there nothing to show, and then the failure has to surface instead of
     * being reported as an account with no limits.
     */
    override suspend fun fetchUsage(
        account: ProviderAccount,
        credentials: OAuthCredentials,
    ): UsageResult {
        val headers = usageHeaders(credentials)
        val now = nowMs()
        var lastFailure: ProviderException? = null

        // null means "this view failed", which is not the same as "this view reported
        // nothing" — an empty list is a legitimate answer.
        val credits: List<UsageWindow>? = try {
            val response = http.request(url = Xai.BILLING_CREDITS_URL, headers = headers)
            XaiBillingParser.parseCredits(JsonSupport.parseObject(response.body), now)
        } catch (e: ProviderException) {
            lastFailure = e
            null
        }

        val billing: List<UsageWindow>? = try {
            val response = http.request(url = Xai.BILLING_URL, headers = headers)
            XaiBillingParser.parseBilling(JsonSupport.parseObject(response.body), now)
        } catch (e: ProviderException) {
            lastFailure = e
            null
        }

        if (credits == null && billing == null) {
            throw lastFailure ?: ProviderException.Unexpected("xAI billing returned nothing")
        }

        // xAI exposes no reset credits, so the list stays empty rather than being faked.
        return UsageResult(windows = XaiBillingParser.merge(credits.orEmpty(), billing.orEmpty()))
    }

    /**
     * Headers for the billing proxy.
     *
     * Lowercase names and a wildcard accept mirror the first-party CLI, which is what the
     * proxy answers billing JSON for. The version marker is a compatibility signal, not a
     * disguise, though note the UA is the Grok CLI's own string and does not name this
     * app — unlike Codex's, which appends UsageLimits.
     */
    private fun usageHeaders(credentials: OAuthCredentials): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${credentials.accessToken}")
        putAll(Xai.IDENTITY_HEADERS)
        put("accept", "*/*")
        put("user-agent", Xai.USER_AGENT)
    }

    /** Discovery, device and token calls. Form bodies carry their own content type. */
    private fun oauthHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to Xai.USER_AGENT,
    )

    private fun toCredentials(payload: JsonObject, tokenEndpoint: String): OAuthCredentials {
        val accessToken = JsonSupport.string(payload, "access_token", "accessToken")
            ?: throw ProviderException.MalformedPayload("token response had no access_token")
        val expiresIn = JsonSupport.long(payload, "expires_in", "expiresIn")

        return OAuthCredentials(
            accessToken = accessToken,
            refreshToken = JsonSupport.string(payload, "refresh_token", "refreshToken"),
            idToken = JsonSupport.string(payload, "id_token", "idToken"),
            expiresAt = JsonSupport.expiryAfterSeconds(expiresIn, nowMs()),
            // Pinned here so a refresh never depends on discovery being reachable.
            tokenEndpoint = tokenEndpoint,
        )
    }

    /** Unpacks the challenge into user code, device code and token endpoint. */
    private fun splitChallenge(value: String): Triple<String, String, String> {
        // limit = 3 keeps any separator inside the URL with the URL.
        val parts = value.split(CODE_SEPARATOR, limit = 3)
        require(parts.size == 3) { "malformed device challenge" }
        return Triple(parts[0], parts[1], parts[2])
    }

    companion object {
        /** Packs the user code, the device code and the token endpoint into one field. */
        private const val CODE_SEPARATOR = "|"

        /**
         * Registrable domain of [Xai.ISSUER] (`auth.x.ai`), and the only domain discovered
         * endpoints may live under. It is a rule about hosts rather than a URL, which is why
         * it sits here and not in ProviderEndpoints.
         */
        private const val ISSUER_HOST = "x.ai"

        /** Poll floor, also the default when the provider states no interval. */
        private const val MIN_POLL_SECONDS = 5L

        /** Used only when the device response omits `expires_in`. */
        private const val DEFAULT_EXPIRES_SECONDS = 15L * 60

        /** How much a `slow_down` adds to the poll interval, per RFC 8628 §3.5. */
        private const val SLOW_DOWN_STEP_MS = 5_000L

        private const val ERROR_AUTHORIZATION_PENDING = "authorization_pending"
        private const val ERROR_SLOW_DOWN = "slow_down"
        private const val ERROR_EXPIRED_TOKEN = "expired_token"
        private const val ERROR_ACCESS_DENIED = "access_denied"

        /** The user-visible half of the packed challenge code. */
        fun displayCode(packed: String): String = packed.substringBefore(CODE_SEPARATOR)
    }
}
