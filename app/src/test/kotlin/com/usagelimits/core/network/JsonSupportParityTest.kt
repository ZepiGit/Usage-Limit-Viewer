package com.usagelimits.core.network

import com.usagelimits.core.time.Instants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the shared JSON helpers had drifted from their Swift twins — found by a field-by-field
 * parity audit of the Claude parser, and each one a different number on the two phones for
 * one payload.
 */
class JsonSupportParityTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `strings are trimmed, as they are on iOS`() {
        // A `kind` of " session" failed every comparison in the Claude parser: wrong id, wrong
        // category, no duration — and a window iOS deduplicated, Android emitted twice.
        assertEquals("session", JsonSupport.string(obj("""{"k": " session "}"""), "k"))
        assertNull(JsonSupport.string(obj("""{"k": "   "}"""), "k"))
    }

    @Test
    fun `a non-finite number is no number`() {
        // "Infinity" as a used-percent became an EXHAUSTED window: an exhaustion alert from
        // garbage. iOS refused it; now both do.
        assertNull(JsonSupport.double(obj("""{"u": "Infinity"}"""), "u"))
        assertNull(JsonSupport.double(obj("""{"u": "NaN"}"""), "u"))
        assertEquals(21.0, JsonSupport.double(obj("""{"u": "21"}"""), "u"))
    }

    @Test
    fun `a non-finite epoch is no instant`() {
        // +Inf.toLong() saturates to Long.MAX, which would have been a "next reset" in the
        // year 292 million.
        assertNull(Instants.parse("Infinity"))
        assertNull(Instants.parse("NaN"))
    }
}
