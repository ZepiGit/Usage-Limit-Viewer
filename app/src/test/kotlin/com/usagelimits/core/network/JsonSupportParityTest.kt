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
    @Test
    fun `a non-finite integer is no integer, as it is no double`() {
        // `toLong()` saturates where `toDoubleOrNull` is happy to parse, so "Infinity" became
        // Long.MAX_VALUE rather than nothing — and every caller multiplies by 1000.
        val payload = JsonSupport.parseObject("""{ "n": "Infinity", "m": "NaN" }""")

        assertNull(JsonSupport.long(payload, "n"))
        assertNull(JsonSupport.long(payload, "m"))
    }

    @Test
    fun `an integer too large for Long is refused rather than saturated`() {
        // Finite, so a finiteness check alone lets it through, and it still saturates.
        val payload = JsonSupport.parseObject("""{ "n": "1e30" }""")

        assertNull(JsonSupport.long(payload, "n"))
    }

    @Test
    fun `a reset offset that would overflow is no reset rather than one in the past`() {
        // The failure this guards is not "a wrong number" but a wrong DIRECTION: the multiply
        // wraps and the reset lands before now, which the account's next-reset minimum then
        // adopts for every window it has.
        val now = 1_757_000_000_000L

        assertNull(Instants.fromOffsetSeconds(Long.MAX_VALUE, now))
        // And a real offset still works, so the bound did not cost anything legitimate.
        assertEquals(now + 18_000_000L, Instants.fromOffsetSeconds(18_000L, now))
    }

    @Test
    fun `an expiry built from a garbage expires_in does not read as already expired`() {
        // What the defect actually cost: `needsRefresh` true for ever, so every sync pass spent
        // a rotating one-time refresh grant instead of one an hour.
        val now = 1_757_000_000_000L
        val payload = JsonSupport.parseObject("""{ "expires_in": "Infinity" }""")

        val seconds = JsonSupport.long(payload, "expires_in")
        val expiresAt = seconds?.let { now + it * 1000 }

        assertNull("a garbage expiry must be absent, not in the past", expiresAt)
    }

}
