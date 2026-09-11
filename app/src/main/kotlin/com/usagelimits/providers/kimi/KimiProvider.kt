package com.usagelimits.providers.kimi

import com.usagelimits.BuildConfig
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.planLabel
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Kimi
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.KeyLoginCapable
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * Kimi Code.
 *
 * Signs in with Kimi's RFC 8628 device flow, under this app's own name. For a while this was
 * the one provider without OAuth, on the reading that its device flow belonged to `kimi-cli`
 * and that driving it meant impersonating that client. The reading was half right: the client
 * id is public and shared by every program that drives the flow, first-party or not, and what
 * Moonshot actually gates on — and forbids spoofing — is the `X-Msh-Platform` header that
 * names the calling program. CLIProxyAPI sends its own name there; so does this app. What the
 * gate may still do is answer `403 access_terminated` on the coding API until this app's name
 * is allowlisted, which is why a key from the user's own console remains the second way in.
 *
 * A key-connected account has no refresh grant and no expiry; an OAuth-connected one has both.
 * Everything after sign-in is the same for either.
 */
class KimiProvider(
    private val http: HttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val version: String = BuildConfig.VERSION_NAME,
) : UsageProvider, KeyLoginCapable {

    override val providerId = ProviderId.KIMI

    override suspend fun beginLogin(): LoginChallenge {
        val response = http.request(
            url = Kimi.DEVICE_CODE_URL,
            method = "POST",
            headers = oauthHeaders(),
            body = HttpClient.formBody(mapOf("client_id" to Kimi.CLIENT_ID)),
        )
        val payload = JsonSupport.parseObject(response.body)

        val userCode = JsonSupport.string(payload, "user_code", "userCode")
            ?: throw ProviderException.MalformedPayload("device response had no user code")
        val deviceCode = JsonSupport.string(payload, "device_code", "deviceCode")
            ?: throw ProviderException.MalformedPayload("device response had no device code")
        val verificationUri = JsonSupport.string(payload, "verification_uri", "verificationUri")
            ?: JsonSupport.string(payload, "verification_uri_complete", "verificationUriComplete")
            ?: throw ProviderException.MalformedPayload("device response had no verification URI")

        val expiresIn = JsonSupport.long(payload, "expires_in", "expiresIn") ?: DEFAULT_EXPIRES_SECONDS
        val interval = JsonSupport.long(payload, "interval") ?: MIN_POLL_SECONDS

        return LoginChallenge.DeviceCode(
            // Only the first segment is ever shown; the device code rides along so
            // completeLogin stays stateless.
            userCode = "$userCode$CODE_SEPARATOR$deviceCode",
            verificationUri = verificationUri,
            verificationUriComplete = JsonSupport.string(
                payload,
                "verification_uri_complete",
                "verificationUriComplete",
            ),
            expiresAt = JsonSupport.expiryAfterSeconds(expiresIn, nowMs())
                ?: (nowMs() + DEFAULT_EXPIRES_SECONDS * 1000),
            // The provider's interval is honoured but never allowed below the floor.
            pollIntervalMs = JsonSupport.secondsToMillis(maxOf(interval, MIN_POLL_SECONDS))
                ?: MIN_POLL_SECONDS * 1000,
        )
    }

    override fun keyLoginChallenge(): LoginChallenge.ApiKey = LoginChallenge.ApiKey(
        consoleUrl = Kimi.CONSOLE_URL,
        hint = "Create a key in your Kimi Code console and paste it here. It is stored on this " +
            "device only, in the same encrypted store as every other account.",
    )

    override suspend fun completeLogin(
        challenge: LoginChallenge,
        userInput: String?,
    ): OAuthCredentials = when (challenge) {
        is LoginChallenge.DeviceCode -> pollForTokens(challenge)
        is LoginChallenge.ApiKey -> connectWithKey(userInput)
        is LoginChallenge.Redirect -> throw ProviderException.Unexpected("Kimi does not redirect")
    }

    /**
     * Polls the token endpoint until the user approves, denies, or the code expires.
     *
     * OAuth error bodies are interpreted on both 2xx and HTTP 400/403. Only pending,
     * slow_down and temporary transport/server failures continue; a refusal ends the attempt.
     */
    private suspend fun pollForTokens(challenge: LoginChallenge.DeviceCode): OAuthCredentials {
        val deviceCode = challenge.userCode.substringAfter(CODE_SEPARATOR, "")
        if (deviceCode.isEmpty()) throw ProviderException.Unexpected("malformed device challenge")

        val fields = mapOf(
            "grant_type" to Kimi.DEVICE_CODE_GRANT_TYPE,
            "device_code" to deviceCode,
            "client_id" to Kimi.CLIENT_ID,
        )

        var intervalMs = challenge.pollIntervalMs
        while (nowMs() < challenge.expiresAt) {
            val payload = try {
                val response = http.request(
                    url = Kimi.TOKEN_URL,
                    method = "POST",
                    headers = oauthHeaders(),
                    body = HttpClient.formBody(fields),
                    // Pending may arrive as a failed status; the generic retry must not
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
                null -> return toCredentials(payload)

                ERROR_AUTHORIZATION_PENDING -> delay(intervalMs)

                ERROR_SLOW_DOWN -> {
                    // RFC 8628 §3.5: the increase is permanent for the rest of the poll.
                    intervalMs = Math.addExact(intervalMs, SLOW_DOWN_STEP_MS)
                    delay(intervalMs)
                }

                ERROR_EXPIRED_TOKEN, ERROR_ACCESS_DENIED ->
                    throw ProviderException.LoginCancelled("Device login was not completed ($error)")

                else -> throw ProviderException.Unexpected("device authorization was refused")
            }
        }

        throw ProviderException.LoginCancelled("Device login expired before it was approved")
    }

    /** The other way in: a key from the user's console, proved against the usage endpoint. */
    private suspend fun connectWithKey(userInput: String?): OAuthCredentials {
        val key = userInput?.trim()
        if (key.isNullOrEmpty()) throw ProviderException.LoginCancelled("No key was entered")

        // Rejected here rather than after an account row exists: a key that cannot read usage
        // is not a connected account, and storing it would leave a permanently failing row the
        // user has to work out how to remove.
        val credentials = OAuthCredentials(
            accessToken = key,
            refreshToken = null,
            idToken = null,
            expiresAt = null,
        )
        fetchUsagePayload(credentials)
        return credentials
    }

    /**
     * Refreshes an OAuth-connected account; hands a key-connected one back unchanged.
     *
     * A key has no expiry and no refresh grant, and the sync engine calls this whenever it
     * suspects staleness — for a key that suspicion is never right, and a revoked key surfaces
     * as a 401 on the usage call, which already marks the account as needing attention.
     */
    override suspend fun refresh(credentials: OAuthCredentials): OAuthCredentials {
        val refreshToken = credentials.refreshToken ?: return credentials

        val response = http.request(
            url = Kimi.TOKEN_URL,
            method = "POST",
            headers = oauthHeaders(),
            body = HttpClient.formBody(
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to Kimi.CLIENT_ID,
                    "refresh_token" to refreshToken,
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
        val payload = fetchUsagePayload(credentials)
        return ProviderProfile(
            externalAccountId = identity(payload, credentials.accessToken),
            email = JsonSupport.string(payload, "email")
                ?: JsonSupport.string(JsonSupport.obj(payload, "user"), "email"),
            displayName = null,
            plan = tier(payload),
        )
    }

    override suspend fun fetchUsage(
        account: ProviderAccount,
        credentials: OAuthCredentials,
    ): UsageResult = UsageResult(windows = KimiUsageParser.parse(fetchUsagePayload(credentials)))

    private suspend fun fetchUsagePayload(credentials: OAuthCredentials): JsonObject {
        val response = http.request(
            url = Kimi.USAGE_ENDPOINT,
            headers = Kimi.identityHeaders(version) + mapOf(
                "Authorization" to "Bearer ${credentials.accessToken}",
                "Accept" to "application/json",
            ),
        )
        return JsonSupport.parseObject(response.body)
    }

    private fun oauthHeaders(): Map<String, String> =
        Kimi.identityHeaders(version) + mapOf("Accept" to "application/json")

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

    /** The membership tier, spelled the way every other provider's tier is spelled. */
    private fun tier(payload: JsonObject): String? = planLabel(
        JsonSupport.string(payload, "membership", "tier", "plan")
            ?: JsonSupport.string(JsonSupport.obj(payload, "membership"), "name", "title"),
    )

    /**
     * The account this credential belongs to: Kimi's own id where the payload states one, a
     * one-way digest of the credential where it does not.
     *
     * The preference order is not cosmetic. A digest changes when the credential does, so
     * naming the account after one means a user who rotates their key — or whose access token
     * is simply reissued — comes back as a SECOND account for the same subscription, with the
     * first left behind holding a credential that no longer works. Kimi's id survives both.
     *
     * The digest is the fallback rather than the rule because a payload that names nobody
     * still has to produce a stable account, and the credential is then the only thing left to
     * derive one from. It is truncated SHA-256 and never reversible into the credential.
     *
     * Internal rather than private so it can be tested directly. The behaviour only shows up
     * on the second sign-in with a rotated key, which is not a state a parser test can reach.
     */
    internal fun identity(payload: JsonObject, key: String): String {
        JsonSupport.string(payload, "userId", "user_id", "accountId", "account_id")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        JsonSupport.string(JsonSupport.obj(payload, "user"), "id", "userId", "user_id")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return "key-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Packs the user code and the device code into one challenge field. */
        private const val CODE_SEPARATOR = "|"
        private const val MIN_POLL_SECONDS = 5L
        private const val DEFAULT_EXPIRES_SECONDS = 15L * 60
        private const val SLOW_DOWN_STEP_MS = 5_000L

        private const val ERROR_AUTHORIZATION_PENDING = "authorization_pending"
        private const val ERROR_SLOW_DOWN = "slow_down"
        private const val ERROR_EXPIRED_TOKEN = "expired_token"
        private const val ERROR_ACCESS_DENIED = "access_denied"

        /** The user-visible half of the packed challenge code. */
        fun displayCode(packed: String): String = packed.substringBefore(CODE_SEPARATOR)
    }
}
