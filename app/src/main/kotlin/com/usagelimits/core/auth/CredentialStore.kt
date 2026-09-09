package com.usagelimits.core.auth

/**
 * Encrypted storage for OAuth credentials.
 *
 * Split deliberately from the Room usage cache: Room holds only non-secret account metadata
 * and usage numbers, so the cache can be queried freely (and by widgets) without any path
 * that could surface a token.
 */
interface CredentialStore {
    suspend fun load(reference: String): OAuthCredentials?
    suspend fun save(reference: String, credentials: OAuthCredentials)
    suspend fun delete(reference: String)
    suspend fun references(): Set<String>
}
