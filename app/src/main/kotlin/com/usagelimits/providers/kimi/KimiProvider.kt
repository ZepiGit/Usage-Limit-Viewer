package com.usagelimits.providers.kimi

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.planLabel
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Kimi
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * Kimi Code, authenticated with a key the user creates themselves.
 *
 * The other four providers sign in through a normal OAuth flow. This one deliberately does
 * not, and the reason is worth stating where someone will read it before "fixing" it:
 *
 * Kimi Code does have an RFC 8628 device flow. Its public client id belongs to `kimi-cli`, and
 * `api.kimi.com` additionally gates on an `X-Msh-Platform` header against a server-side
 * allowlist, answering values outside it with `403 access_terminated`. Using that client id
 * and header from this app would be impersonating another client past an access control the
 * provider put there on purpose. The open request asking Moonshot for a third-party client id
 * (moonshotai/kimi-code#1795) calls it impersonation in as many words, and it has not been
 * granted. So the user brings a key from their own console; it lands in the Keystore like
 * every other credential and never leaves the device.
 */
class KimiProvider(private val http: HttpClient) : UsageProvider {

    override val providerId = ProviderId.KIMI

    override suspend fun beginLogin(): LoginChallenge = LoginChallenge.ApiKey(
        consoleUrl = Kimi.CONSOLE_URL,
        hint = "Create a key in your Kimi Code console and paste it here. It is stored on this " +
            "device only, in the same encrypted store as every other account.",
    )

    override suspend fun completeLogin(
        challenge: LoginChallenge,
        userInput: String?,
    ): OAuthCredentials {
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
     * Nothing to refresh: an API key has no expiry and no refresh grant.
     *
     * Returning the same credentials rather than throwing, because the sync engine calls this
     * whenever it thinks a credential is stale — and for this provider it never is. A key that
     * has actually been revoked surfaces as a 401 on the usage call, which is the path that
     * marks an account as needing attention.
     */
    override suspend fun refresh(credentials: OAuthCredentials): OAuthCredentials = credentials

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
            headers = mapOf(
                "Authorization" to "Bearer ${credentials.accessToken}",
                "Accept" to "application/json",
            ),
        )
        return JsonSupport.parseObject(response.body)
    }

    /** The membership tier, spelled the way every other provider's tier is spelled. */
    private fun tier(payload: JsonObject): String? = planLabel(
        JsonSupport.string(payload, "membership", "tier", "plan")
            ?: JsonSupport.string(JsonSupport.obj(payload, "membership"), "name", "title"),
    )

    /**
     * A stable identity for the account, which is what tells a re-entered key from a new one.
     *
     * Kimi's own identifier when the response carries one. When it does not, a digest of the
     * key stands in — one way, truncated, and never the key itself, which must not reach Room.
     * It only has to be stable and unique: pasting the same key again then updates the account
     * it belongs to instead of creating a second one beside it.
     */
    /**
     * The account this key belongs to: Kimi's own id where the payload states one, a one-way
     * digest of the key where it does not.
     *
     * The preference order is not cosmetic. A digest changes when the key does, so naming the
     * account after one means a user who rotates their key in the console comes back as a
     * SECOND account for the same subscription — with the first left behind holding a key that
     * no longer works. Kimi's id survives the rotation.
     *
     * The digest is the fallback rather than the rule because a payload that names nobody still
     * has to produce a stable account, and the key is then the only thing left to derive one
     * from. It is truncated SHA-256 and never reversible into the key.
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
}
