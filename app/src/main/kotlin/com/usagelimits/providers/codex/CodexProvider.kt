package com.usagelimits.providers.codex

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ResetCredit
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Codex
import com.usagelimits.core.network.ProviderException
import com.usagelimits.core.oauth.JwtClaims
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * OpenAI Codex / ChatGPT subscription.
 *
 * Login uses OpenAI's device authorization flow rather than the authorization-code flow the
 * desktop client uses. The desktop flow redirects to `http://localhost:1455/auth/callback`,
 * which on Android would mean binding a fixed port and surviving the app being backgrounded
 * mid-browser. The device flow needs neither: the user types a short code on
 * auth.openai.com and the app polls. See docs/provider-auth-research.md for the comparison.
 *
 * One wrinkle: OpenAI's device endpoint returns the PKCE verifier *and* challenge alongside
 * the authorization code, so the app exchanges a pair it did not generate. The verifier still
 * never crosses an untrusted channel, but it means PKCE here is not the client-binding
 * guarantee it normally is — noted in the research doc.
 */
class CodexProvider(
    private val http: HttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : UsageProvider {

    override val providerId = ProviderId.CODEX
    override val supportsResetCredits = true

    override suspend fun beginLogin(): LoginChallenge {
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
            pollIntervalMs = intervalSeconds * 1000,
        )
    }

    override suspend fun completeLogin(
        challenge: LoginChallenge,
        redirectResponse: String?,
    ): OAuthCredentials {
        require(challenge is LoginChallenge.DeviceCode) { "Codex uses the device flow" }
        val (userCode, deviceAuthId) = splitChallenge(challenge.userCode)

        val authorization = pollForAuthorization(userCode, deviceAuthId, challenge)

        val code = JsonSupport.string(authorization, "authorization_code", "authorizationCode")
            ?: throw ProviderException.MalformedPayload("device token response had no authorization code")
        val verifier = JsonSupport.string(authorization, "code_verifier", "codeVerifier")
            ?: throw ProviderException.MalformedPayload("device token response had no code verifier")

        return exchangeCode(code, verifier)
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
            }
        }
        throw ProviderException.LoginCancelled("Device login expired before it was approved")
    }

    private suspend fun exchangeCode(code: String, codeVerifier: String): OAuthCredentials {
        val response = http.request(
            url = Codex.TOKEN_URL,
            method = "POST",
            headers = mapOf("Accept" to "application/json", "User-Agent" to Codex.USER_AGENT),
            body = HttpClient.formBody(
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to Codex.CLIENT_ID,
                    "code" to code,
                    // Must match what the device endpoint issued the code against, even
                    // though the app never navigates there.
                    "redirect_uri" to Codex.DEVICE_EXCHANGE_REDIRECT_URI,
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
            plan = JsonSupport.string(auth, "chatgpt_plan_type"),
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
            expiresAt = expiresIn?.let { nowMs() + it * 1000 },
        )
    }

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

        /** The user-visible half of the packed challenge code. */
        fun displayCode(packed: String): String = packed.substringBefore(CODE_SEPARATOR)
    }
}
