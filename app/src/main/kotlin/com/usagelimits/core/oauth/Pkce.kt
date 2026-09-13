package com.usagelimits.core.oauth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** A PKCE verifier/challenge pair (RFC 7636). */
data class PkceCodes(
    val codeVerifier: String,
    val codeChallenge: String,
) {
    val codeChallengeMethod: String get() = "S256"
}

/**
 * PKCE and CSRF-state generation.
 *
 * Every redirect-based flow in this app uses PKCE, so an intercepted authorization code
 * cannot be redeemed without the verifier that never left the device.
 */
object Pkce {

    private val secureRandom = SecureRandom()

    /**
     * RFC 7636 allows a 43–128 character verifier; 32 random bytes base64url-encodes to 43,
     * giving 256 bits of entropy at the minimum legal length.
     */
    private const val VERIFIER_BYTES = 32
    private const val STATE_BYTES = 32

    fun generate(): PkceCodes {
        val verifierBytes = ByteArray(VERIFIER_BYTES).also(secureRandom::nextBytes)
        val verifier = encode(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return PkceCodes(codeVerifier = verifier, codeChallenge = encode(digest))
    }

    /** Opaque random value used to bind an authorization response to this request. */
    fun generateState(): String =
        encode(ByteArray(STATE_BYTES).also(secureRandom::nextBytes))

    /**
     * Constant-time comparison for the returned state.
     *
     * Timing is not a realistic threat for a local string compare, but state validation is
     * the CSRF control for the whole flow, so it does not rely on early-exit equality.
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        if (aBytes.size != bBytes.size) return false
        var diff = 0
        for (i in aBytes.indices) diff = diff or (aBytes[i].toInt() xor bBytes[i].toInt())
        return diff == 0
    }

    /**
     * Whether a returned `state` answers the request that issued [expected].
     *
     * Exact equality, or [expected] followed by a `.`-separated suffix. OpenAI's authorize
     * endpoint appends onboarding metadata to the state it echoes for some accounts — the
     * Codex CLI accepts `<state>.onboarding_entrypoint=…` for the same reason — and an exact
     * comparison turned every such sign-in into a redirect the listener answered with 400 and
     * then waited on until it timed out. The random part is still required in full, so this
     * gives a forged redirect nothing: a stranger has to know all 256 bits either way.
     */
    fun stateMatches(expected: String, returned: String): Boolean {
        if (constantTimeEquals(expected, returned)) return true
        if (returned.length <= expected.length) return false
        return returned[expected.length] == '.' &&
            constantTimeEquals(expected, returned.substring(0, expected.length))
    }

    /** base64url without padding, as the spec requires. */
    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
