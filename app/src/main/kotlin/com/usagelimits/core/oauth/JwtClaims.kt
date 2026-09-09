package com.usagelimits.core.oauth

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads claims out of an ID token.
 *
 * The signature is deliberately *not* verified: the token arrives over TLS directly from the
 * provider's token endpoint in response to a request we initiated, so it is already
 * authenticated by the channel. Claims are used only to label the account in the UI — never
 * to make an access-control decision — so this is introspection, not validation.
 */
object JwtClaims {

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the decoded payload object, or null if [token] is not a well-formed JWT. */
    fun parse(token: String?): JsonObject? {
        if (token.isNullOrBlank()) return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        return runCatching {
            val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }

    fun string(claims: JsonObject?, key: String): String? =
        claims?.get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotBlank() }

    /**
     * Codex nests its subscription data under a namespaced claim rather than at the top
     * level, so account id and plan come from inside this object.
     */
    fun openAiAuth(claims: JsonObject?): JsonObject? =
        claims?.get("https://api.openai.com/auth")?.let { runCatching { it.jsonObject }.getOrNull() }
}
