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

    fun string(source: JsonObject?, vararg names: String): String? =
        field(source, *names)
            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != "null" }

    /** Accepts a JSON number or a numeric string, since providers mix the two. */
    fun double(source: JsonObject?, vararg names: String): Double? {
        val element = field(source, *names) ?: return null
        val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
        return primitive.doubleOrNull ?: primitive.content.trim().toDoubleOrNull()
    }

    fun long(source: JsonObject?, vararg names: String): Long? {
        val element = field(source, *names) ?: return null
        val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
        return primitive.longOrNull ?: primitive.content.trim().toDoubleOrNull()?.toLong()
    }

    fun boolean(source: JsonObject?, vararg names: String): Boolean? {
        val element = field(source, *names) ?: return null
        val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
        return primitive.content.trim().lowercase().toBooleanStrictOrNull()
    }

    fun array(source: JsonObject?, vararg names: String): List<JsonElement> =
        field(source, *names)?.let { element ->
            runCatching { element as? kotlinx.serialization.json.JsonArray }.getOrNull()?.toList()
        } ?: emptyList()
}
