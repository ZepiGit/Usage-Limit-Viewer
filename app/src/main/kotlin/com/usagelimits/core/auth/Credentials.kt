package com.usagelimits.core.auth

/**
 * An OAuth credential set for one account.
 *
 * Instances are only ever held in memory and inside the encrypted credential store. They must
 * never be logged, put in a Bundle, written to Room, or handed to a widget — see
 * [toString], which is overridden so an accidental interpolation cannot leak a token.
 */
data class OAuthCredentials(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    /** Epoch millis when [accessToken] stops being valid, if known. */
    val expiresAt: Long?,
    /**
     * Token endpoint to refresh against, when the provider discovers it at runtime
     * (xAI resolves it via OIDC discovery) rather than having it hardcoded.
     */
    val tokenEndpoint: String? = null,
) {
    /** True when the token is expired, or close enough that a refresh should happen first. */
    fun needsRefresh(nowMs: Long, leadMs: Long = DEFAULT_REFRESH_LEAD_MS): Boolean {
        val expiry = expiresAt ?: return false
        return nowMs >= expiry - leadMs
    }

    /** Never render token material, however this object ends up being formatted. */
    override fun toString(): String =
        "OAuthCredentials(accessToken=***, refreshToken=${if (refreshToken != null) "***" else "null"}, expiresAt=$expiresAt)"

    companion object {
        /** Refresh a little early so an in-flight sync does not race the expiry. */
        const val DEFAULT_REFRESH_LEAD_MS = 5L * 60 * 1000
    }
}
