package com.usagelimits.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Lenient JSON access for provider payloads.
 *
 * Every provider here is an undocumented internal endpoint, so parsers read defensively:
 * unknown fields are ignored, absent fields yield null rather than throwing, and both
 * snake_case and camelCase spellings are accepted because upstream serves both depending on
 * the endpoint. A field that disappears should cost one row, not the whole screen.
 */
object JsonSupport {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun parseObject(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw ProviderException.MalformedPayload("response was not a JSON object", it) }

    /** Reads the first present spelling of a field, e.g. `used_percent` then `usedPercent`. */
    fun field(obj: JsonObject?, vararg names: String): JsonElement? {
        if (obj == null) return null
        for (name in names) {
            val value = obj[name]
            if (value != null && value.toString() != "null") return value
        }
        return null
    }

    fun obj(source: JsonObject?, vararg names: String): JsonObject? =
        field(source, *names)?.let { runCatching { it.jsonObject }.getOrNull() }

    // Trimmed, as the Swift twin's is. Untrimmed, a `kind` of " session" failed every
    // comparison in the Claude parser — wrong id, wrong category, no duration — and the two
    // platforms produced different windows for one payload.
    fun string(source: JsonObject?, vararg names: String): String? =
        field(source, *names)
            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }

    /** Accepts a JSON number or a numeric string, since providers mix the two. */
    fun double(source: JsonObject?, vararg names: String): Double? {
        val element = field(source, *names) ?: return null
        val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
        // Finite only. Kotlin's toDoubleOrNull accepts "Infinity" and "NaN", and an infinite
        // used-percent became an EXHAUSTED window — an exhaustion alert from garbage. The Swift
        // twin refuses non-finite values; now both do.
        return (primitive.doubleOrNull ?: primitive.content.trim().toDoubleOrNull())
            ?.takeIf { it.isFinite() }
    }

    fun long(source: JsonObject?, vararg names: String): Long? {
        val element = field(source, *names) ?: return null
        val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
        primitive.longOrNull?.let { return it }

        // The same rule `double` applies, for a sharper reason. `toLong()` SATURATES: "Infinity"
        // becomes Long.MAX_VALUE rather than failing, and every caller here then multiplies by
        // 1000 — which overflows to -1000, so the result lands one second in the PAST.
        //
        // Two places that reached: a reset offset became a reset that had already happened and
        // took over the account's "next reset" everywhere, since that is a min; and an
        // `expires_in` became an expiry already behind us, so `needsRefresh` was true for ever
        // and every sync pass spent a rotating one-time refresh grant instead of one an hour.
        //
        // The range check is separate from the finiteness one: 1e30 is perfectly finite and
        // still saturates.
        val approx = primitive.content.trim().toDoubleOrNull() ?: return null
        if (!approx.isFinite()) return null
        if (approx < Long.MIN_VALUE.toDouble() || approx > Long.MAX_VALUE.toDouble()) return null
        return approx.toLong()
    }

    /**
     * Seconds to milliseconds without wrapping.
     *
     * `seconds * 1000` overflows silently in Kotlin: a valid `Long` such as `9223372036854775807`
     * — which `long()` rightly accepts, it is a real JSON integer — wraps to -1000. A token
     * expiry then landed one second in the PAST, so `needsRefresh` was true for ever and every
     * sync spent a rotating one-time grant; a device-poll interval became -1000 ms, below the
     * five-second floor it was clamped to before the multiply. Null says "not a duration this
     * app can use", and every caller has a default for that.
     */
    fun secondsToMillis(seconds: Long?): Long? =
        seconds?.takeIf { it >= 0 }?.let { runCatching { Math.multiplyExact(it, 1000L) }.getOrNull() }

    /** `nowMs + seconds`, or null where that instant cannot be represented. */
    fun expiryAfterSeconds(seconds: Long?, nowMs: Long): Long? =
        secondsToMillis(seconds)?.let { runCatching { Math.addExact(nowMs, it) }.getOrNull() }

    fun boolean(source: JsonObject?, vararg names: String): Boolean? {
        val element = field(source, *names) ?: return null
        val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
        return primitive.content.trim().lowercase().toBooleanStrictOrNull()
    }

    // A JsonArray already IS a List<JsonElement>; copying it bought nothing but the copy.
    fun array(source: JsonObject?, vararg names: String): List<JsonElement> =
        (field(source, *names) as? kotlinx.serialization.json.JsonArray) ?: emptyList()
}
