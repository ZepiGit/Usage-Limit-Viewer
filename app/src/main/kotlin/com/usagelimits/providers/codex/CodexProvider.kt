package com.usagelimits.providers.codex

import com.usagelimits.core.model.planLabel

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ResetCredit
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Codex
import com.usagelimits.core.network.ProviderException
import com.usagelimits.core.oauth.JwtClaims
import com.usagelimits.core.oauth.LoopbackServer
import com.usagelimits.core.oauth.Pkce
import com.usagelimits.core.oauth.PkceCodes
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.UUID

/**
 * OpenAI Codex / ChatGPT subscription.
 *
 * Login is the authorization-code flow the Codex CLI itself runs: sign in on the provider's
 * page, get redirected to `http://localhost:1455/auth/callback`, done — the same shape as
 * Claude and Antigravity, on the same [LoopbackServer]. The device flow, which was the only
 * one for a while, stays as the fallback for the one thing the browser flow needs and cannot
 * guarantee: the registered port being free. It asks the user to carry a code from this app
 * into the browser, which is exactly the step the browser flow removes.
 *
 * One wrinkle in the fallback: OpenAI's device endpoint returns the PKCE verifier *and*
 * challenge alongside the authorization code, so the app exchanges a pair it did not
 * generate. The verifier still never crosses an untrusted channel, but it means PKCE there is
 * not the client-binding guarantee it normally is — noted in the research doc. The browser
 * flow generates its own pair, as PKCE intends.
 */
class CodexProvider(
    private val http: HttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : UsageProvider {

    override val providerId = ProviderId.CODEX
    override val supportsResetCredits = true

    // The browser flow's half-open state, held across the round-trip like Claude's: in memory
    // only, never persisted or logged, cleared the moment the redirect comes back.
    private var server: LoopbackServer? = null
    private var pendingCodes: PkceCodes? = null
    private var pendingState: String? = null

    override suspend fun beginLogin(): LoginChallenge {
        // Bind the port BEFORE the authorization URL is handed back, so a conflict is known
        // now — and answered with the device flow — rather than after the browser has minted
        // a code that then has nowhere to land.
        val boundServer = try {
            LoopbackServer(Codex.REDIRECT_PORT).also { it.start() }
        } catch (e: ProviderException) {
            return beginDeviceLogin()
        }

        val codes = Pkce.generate()
        val state = Pkce.generateState()
        pendingCodes = codes
        pendingState = state
        server = boundServer

        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to Codex.CLIENT_ID,
            "redirect_uri" to Codex.REDIRECT_URI,
            "scope" to Codex.AUTHORIZE_SCOPE,
            "code_challenge" to codes.codeChallenge,
            "code_challenge_method" to codes.codeChallengeMethod,
            "state" to state,
        )
        params.putAll(Codex.AUTHORIZE_EXTRA_PARAMS)

        val authorizationUrl = params.entries.joinToString(
            separator = "&",
            prefix = "${Codex.AUTHORIZE_URL}?",
        ) { (name, value) -> "${urlEncode(name)}=${urlEncode(value)}" }

        return LoginChallenge.Redirect(
            authorizationUrl = authorizationUrl,
            redirectUri = Codex.REDIRECT_URI,
        )
    }

    /** The device flow: no port to bind, a code to carry. Used only when the port is taken. */
    private suspend fun beginDeviceLogin(): LoginChallenge {
        val response = http.request(
            url = Codex.DEVICE_USER_CODE_URL,
            method = "POST",
            headers = mapOf("Accept" to "application/json", "User-Agent" to Codex.USER_AGENT),
            body = HttpClient.jsonBody(
                JsonSupport.json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject { put("client_id", Codex.CLIENT_ID) },
                ),
            ),
        )

        val payload = JsonSupport.parseObject(response.body)

        // Upstream has shipped both spellings of this field; accept either.
        val userCode = JsonSupport.string(payload, "user_code", "usercode")
            ?: throw ProviderException.MalformedPayload("device response had no user code")
        val deviceAuthId = JsonSupport.string(payload, "device_auth_id", "deviceAuthId")
            ?: throw ProviderException.MalformedPayload("device response had no device_auth_id")

        val intervalSeconds = JsonSupport.long(payload, "interval")
            ?.takeIf { it >= DEFAULT_POLL_SECONDS } ?: DEFAULT_POLL_SECONDS

        return LoginChallenge.DeviceCode(
            // The device id is not shown to the user; it is carried through the challenge so
            // completeLogin stays stateless.
            userCode = "$userCode$CODE_SEPARATOR$deviceAuthId",
            verificationUri = Codex.DEVICE_VERIFICATION_URL,
            verificationUriComplete = null,
            expiresAt = nowMs() + DEVICE_TIMEOUT_MS,
            pollIntervalMs = JsonSupport.secondsToMillis(intervalSeconds) ?: DEFAULT_POLL_SECONDS * 1000,
        )
    }

    override suspend fun completeLogin(
        challenge: LoginChallenge,
        userInput: String?,
    ): OAuthCredentials = when (challenge) {
        is LoginChallenge.Redirect -> completeBrowserLogin()
        is LoginChallenge.DeviceCode -> completeDeviceLogin(challenge)
        is LoginChallenge.ApiKey -> throw ProviderException.Unexpected("Codex does not use a key")
    }

    private suspend fun completeBrowserLogin(): OAuthCredentials {
        val codes = pendingCodes
        val expectedState = pendingState
        val listener = server
        if (codes == null || expectedState == null || listener == null) {
            throw ProviderException.Unexpected("Login was not started on this provider instance")
        }

        val response = try {
            listener.awaitRedirect(REDIRECT_TIMEOUT_MS) { response ->
                response.state?.let { Pkce.constantTimeEquals(expectedState, it) } == true
            }
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
        // The CSRF control for the whole flow: the state the browser brought back has to be
        // the one this instance generated, checked before the code is looked at.
        val returnedState = response.state
            ?: throw ProviderException.LoginCancelled("Redirect carried no state")
        if (!Pkce.constantTimeEquals(expectedState, returnedState)) {
            throw ProviderException.Unexpected("state mismatch")
        }
        val code = response.code
            ?: throw ProviderException.LoginCancelled("No authorization code was returned")

        return exchangeCode(code, codes.codeVerifier, Codex.REDIRECT_URI)
    }

    private suspend fun completeDeviceLogin(challenge: LoginChallenge.DeviceCode): OAuthCredentials {
        val (userCode, deviceAuthId) = splitChallenge(challenge.userCode)

        val authorization = pollForAuthorization(userCode, deviceAuthId, challenge)

        val code = JsonSupport.string(authorization, "authorization_code", "authorizationCode")
            ?: throw ProviderException.MalformedPayload("device token response had no authorization code")
        val verifier = JsonSupport.string(authorization, "code_verifier", "codeVerifier")
            ?: throw ProviderException.MalformedPayload("device token response had no code verifier")

        return exchangeCode(code, verifier, Codex.DEVICE_EXCHANGE_REDIRECT_URI)
    }

    /**
     * Polls until the user approves.
     *
     * OpenAI signals "not yet" with 403/404 rather than the RFC 8628 `authorization_pending`
     * body, so those two statuses are the continue condition; anything else is terminal.
     */
    private suspend fun pollForAuthorization(
        userCode: String,
        deviceAuthId: String,
        challenge: LoginChallenge.DeviceCode,
    ): JsonObject {
        val body = JsonSupport.json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("device_auth_id", deviceAuthId)
                put("user_code", userCode)
            },
        )

        while (nowMs() < challenge.expiresAt) {
            try {
                val response = http.request(
                    url = Codex.DEVICE_TOKEN_URL,
                    method = "POST",
                    headers = mapOf("Accept" to "application/json", "User-Agent" to Codex.USER_AGENT),
                    body = HttpClient.jsonBody(body),
                    // Pending is expressed as an error status, so the generic retry must not
                    // swallow it — poll timing is handled here instead.
                    retries = 0,
                )
                return JsonSupport.parseObject(response.body)
            } catch (e: ProviderException.Forbidden) {
                delay(challenge.pollIntervalMs)
            } catch (e: ProviderException.Unexpected) {
                // 404 while the code is unclaimed also means "keep waiting".
                if (e.message?.contains("404") != true) throw e
                delay(challenge.pollIntervalMs)
            } catch (e: ProviderException.Offline) {
                // One poll that did not get through is not a failed login. The user is in the
                // browser and the phone may be mid-handover; the deadline above bounds this.
                delay(challenge.pollIntervalMs)
            } catch (e: ProviderException.ServerError) {
                delay(challenge.pollIntervalMs)
            }
        }
        throw ProviderException.LoginCancelled("Device login expired before it was approved")
    }

    // Internal so the one-time-grant rule on this exchange can be tested directly; the only
    // other route in is a complete device or loopback login, which no unit test can drive.
    internal suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        redirectUri: String = Codex.DEVICE_EXCHANGE_REDIRECT_URI,
    ): OAuthCredentials {
        val response = http.request(
            url = Codex.TOKEN_URL,
            method = "POST",
            headers = mapOf("Accept" to "application/json", "User-Agent" to Codex.USER_AGENT),
            body = HttpClient.formBody(
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to Codex.CLIENT_ID,
                    "code" to code,
                    // Must match what the code was issued against: the loopback redirect for
                    // the browser flow, the device callback — never navigated to — for the
                    // device flow.
                    "redirect_uri" to redirectUri,
                    "code_verifier" to codeVerifier,
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
            url = Codex.TOKEN_URL,
            method = "POST",
            headers = mapOf("Accept" to "application/json", "User-Agent" to Codex.USER_AGENT),
            body = HttpClient.formBody(
                mapOf(
                    "client_id" to Codex.CLIENT_ID,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "scope" to REFRESH_SCOPE,
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
        val claims = JwtClaims.parse(credentials.idToken)
        val auth = JwtClaims.openAiAuth(claims)

        val accountId = JsonSupport.string(auth, "chatgpt_account_id")
            ?: JwtClaims.string(claims, "sub")
            ?: throw ProviderException.MalformedPayload("ID token had no account identifier")

        return ProviderProfile(
            externalAccountId = accountId,
            email = JwtClaims.string(claims, "email"),
            displayName = null,
            plan = planLabel(JsonSupport.string(auth, "chatgpt_plan_type")),
            attributes = JsonSupport.string(auth, "chatgpt_account_id")
                ?.let { mapOf(ATTR_ACCOUNT_ID to it) } ?: emptyMap(),
        )
    }

    override suspend fun fetchUsage(
        account: ProviderAccount,
        credentials: OAuthCredentials,
    ): UsageResult {
        val response = http.request(
            url = Codex.USAGE_URL,
            headers = usageHeaders(account, credentials),
        )
        val payload = JsonSupport.parseObject(response.body)
        val now = nowMs()

        val windows = CodexUsageParser.parse(payload, now)

        // The dedicated endpoint is authoritative but optional: a failure there should cost
        // the reset-credit rows, not the entire usage refresh. The usage payload carries a copy.
        val embedded = JsonSupport.obj(
            payload,
            "rate_limit_reset_credits",
            "rateLimitResetCredits",
        )
        val credits = runCatching { fetchResetCredits(account, credentials) }
            .getOrElse {
                CreditsResult(
                    credits = CodexUsageParser.parseResetCredits(embedded),
                    count = CodexUsageParser.availableCreditCount(embedded),
                )
            }

        // The two sources are not the same payload with the same fields, which is easy to miss
        // and was wrong here until a live account was checked. The dedicated endpoint returns
        // `credits`, `available_count`, `total_earned_count` and no applicable count at all;
        // the embedded copy returns `available_count` and `applicable_available_count` and no
        // `credits` array. Reading the applicable count off whichever source answered meant it
        // was only ever available on the FALLBACK path — the button lost its gate precisely
        // when the authoritative call succeeded.
        val applicable = CodexUsageParser.applicableCreditCount(embedded)

        // The reported count wins over the row count. The list can be truncated or filtered
        // while the count stays exact, and gating the redeem button on the rows would hide it
        // from someone who actually holds credits.
        return UsageResult(
            windows = windows,
            resetCredits = credits.credits,
            resetCreditCount = credits.count ?: credits.credits.size,
            applicableResetCreditCount = applicable,
        )
    }

    private data class CreditsResult(
        val credits: List<ResetCredit>,
        val count: Int?,
    )

    private suspend fun fetchResetCredits(
        account: ProviderAccount,
        credentials: OAuthCredentials,
    ): CreditsResult {
        val response = http.request(
            url = Codex.RESET_CREDITS_URL,
            headers = usageHeaders(account, credentials) + Codex.RESET_CREDIT_HEADERS,
        )
        val payload = JsonSupport.parseObject(response.body)
        return CreditsResult(
            credits = CodexUsageParser.parseResetCredits(payload),
            count = CodexUsageParser.availableCreditCount(payload),
        )
    }

    /**
     * Spends one reset credit.
     *
     * Called only from an explicit, confirmed user action. [redeem_request_id] is a fresh
     * UUID per attempt, which is what makes the call idempotent on the provider side — a
     * retried request with the same id will not spend a second credit.
     */
    override suspend fun consumeResetCredit(
        account: ProviderAccount,
        credentials: OAuthCredentials,
    ) {
        http.request(
            url = Codex.RESET_CREDITS_CONSUME_URL,
            method = "POST",
            headers = usageHeaders(account, credentials) + Codex.RESET_CREDIT_HEADERS,
            body = HttpClient.jsonBody(
                JsonSupport.json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject { put("redeem_request_id", UUID.randomUUID().toString()) },
                ),
            ),
            // Never retried: a network error after the server committed the spend would
            // otherwise risk burning a second credit.
            retries = 0,
        )
    }

    private fun usageHeaders(
        account: ProviderAccount,
        credentials: OAuthCredentials,
    ): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${credentials.accessToken}")
        put("Content-Type", "application/json")
        put("Accept", "application/json")
        put("User-Agent", Codex.USER_AGENT)
        val accountId = account.attributes[ATTR_ACCOUNT_ID]
        if (!accountId.isNullOrBlank()) put(Codex.HEADER_ACCOUNT_ID, accountId)
    }

    private fun toCredentials(payload: JsonObject): OAuthCredentials {
        val accessToken = JsonSupport.string(payload, "access_token")
            ?: throw ProviderException.MalformedPayload("token response had no access_token")
        val expiresIn = JsonSupport.long(payload, "expires_in")
        return OAuthCredentials(
            accessToken = accessToken,
            refreshToken = JsonSupport.string(payload, "refresh_token"),
            idToken = JsonSupport.string(payload, "id_token"),
            expiresAt = JsonSupport.expiryAfterSeconds(expiresIn, nowMs()),
        )
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun splitChallenge(value: String): Pair<String, String> {
        val parts = value.split(CODE_SEPARATOR)
        require(parts.size == 2) { "malformed device challenge" }
        return parts[0] to parts[1]
    }

    companion object {
        const val ATTR_ACCOUNT_ID = "chatgpt_account_id"

        // Deliberately differs from the authorize scope to match the refresh grant.
        private const val REFRESH_SCOPE = "openid profile email"

        /** Packs the user code and the device id into one challenge field. */
        private const val CODE_SEPARATOR = "|"
        private const val DEFAULT_POLL_SECONDS = 5L
        private const val DEVICE_TIMEOUT_MS = 15L * 60 * 1000

        /** How long the user has in the browser before the loopback listener gives up. */
        private const val REDIRECT_TIMEOUT_MS = 5L * 60 * 1000

        /** The user-visible half of the packed challenge code. */
        fun displayCode(packed: String): String = packed.substringBefore(CODE_SEPARATOR)
    }
}
