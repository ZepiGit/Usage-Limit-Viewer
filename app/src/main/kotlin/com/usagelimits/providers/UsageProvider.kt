package com.usagelimits.providers

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ResetCredit
import com.usagelimits.core.model.UsageWindow

/** Identity a provider reports after login, before the account gets a local id. */
data class ProviderProfile(
    val externalAccountId: String,
    val email: String?,
    val displayName: String?,
    val plan: String?,
    val attributes: Map<String, String> = emptyMap(),
)

/** What a usage fetch yields, before it is stamped with a time and persisted. */
data class UsageResult(
    val windows: List<UsageWindow>,
    val resetCredits: List<ResetCredit> = emptyList(),
    /**
     * Provider-reported credit count, when it gives one.
     *
     * Authoritative over [resetCredits].size: the row list can be truncated or filtered while
     * the count stays exact.
     */
    val resetCreditCount: Int? = null,
)

/**
 * A login in progress.
 *
 * Both flows the app uses are represented: [DeviceCode] needs the user to type a code on
 * another screen, [Redirect] needs a browser round-trip back to the app.
 */
sealed interface LoginChallenge {
    data class DeviceCode(
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresAt: Long,
        val pollIntervalMs: Long,
    ) : LoginChallenge

    data class Redirect(
        val authorizationUrl: String,
        val redirectUri: String,
    ) : LoginChallenge
}

/**
 * One monitored provider.
 *
 * Implementations own every provider-specific detail — endpoints, headers, payload shapes —
 * and hand back only normalised model types, so no screen or widget ever needs to know which
 * provider it is rendering.
 *
 * Note what is absent: there is no request/completion/chat entry point. This app reads quota
 * and nothing else.
 */
interface UsageProvider {
    val providerId: ProviderId

    /** False while a provider is implemented but not yet ready to be offered in "Add account". */
    val isLoginAvailable: Boolean get() = true

    /** Starts a login and returns what the user has to do next. */
    suspend fun beginLogin(): LoginChallenge

    /**
     * Drives the login to completion: polls (device flow) or exchanges the code (redirect).
     * Suspends until the user finishes, the attempt is cancelled, or the challenge expires.
     */
    suspend fun completeLogin(challenge: LoginChallenge, redirectResponse: String? = null): OAuthCredentials

    /** Exchanges a refresh token for a fresh access token. */
    suspend fun refresh(credentials: OAuthCredentials): OAuthCredentials

    /** Reads identity for a freshly authenticated credential set. */
    suspend fun fetchProfile(credentials: OAuthCredentials): ProviderProfile

    /** Reads current quota. The account is passed for provider-specific attributes. */
    suspend fun fetchUsage(account: ProviderAccount, credentials: OAuthCredentials): UsageResult

    /** True only for providers that expose real, user-consumable reset credits. */
    val supportsResetCredits: Boolean get() = false

    /**
     * Spends one reset credit. Only ever called after an explicit, confirmed user action —
     * never by sync, and never automatically.
     */
    suspend fun consumeResetCredit(account: ProviderAccount, credentials: OAuthCredentials) {
        throw UnsupportedOperationException("${providerId.id} has no reset credits")
    }
}
