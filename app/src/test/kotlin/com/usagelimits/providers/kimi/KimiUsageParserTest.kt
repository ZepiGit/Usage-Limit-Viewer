package com.usagelimits.providers.kimi

import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kimi Code's usage response, and the trap in it.
 *
 * The weekly block reports `used`; the rate-limit blocks report `remaining`. Neither carries
 * the other. A parser that understands one of the two shows an untouched weekly bar on an
 * account that has spent its entire week — a defect other clients shipped before fixing it,
 * which is the reason these cases exist before the provider does.
 */
class KimiUsageParserTest {

    private fun payload(text: String) = JsonSupport.parseObject(text)

    @Test
    fun `the weekly window is read from used, which is the only field it has`() {
        val windows = KimiUsageParser.parse(
            payload(
                """
                { "usage": { "limit": 7168, "used": 7168, "resetTime": 1800000000 } }
                """,
            ),
        )

        val weekly = windows.single()
        assertEquals("Weekly", weekly.label)
        assertEquals(WindowCategory.WEEKLY, weekly.category)
        // The whole point: 100 %, not null, not zero.
        assertEquals(100.0, weekly.usedPercent!!, 0.001)
        assertTrue(weekly.exhausted)
    }

    @Test
    fun `a rate limit is read from remaining, which is the only field it has`() {
        val windows = KimiUsageParser.parse(
            payload(
                """
                { "limits": [
                    { "name": "5h limit",
                      "detail": { "limit": 100, "remaining": 25, "resetTime": 1800000000 } }
                ] }
                """,
            ),
        )

        val limit = windows.single()
        assertEquals("5h limit", limit.label)
        assertEquals(75.0, limit.usedPercent!!, 0.001)
    }

    @Test
    fun `both kinds arrive together and both are read`() {
        val windows = KimiUsageParser.parse(
            payload(
                """
                {
                  "usage": { "limit": 1000, "used": 400 },
                  "limits": [
                    { "name": "5h limit", "detail": { "limit": 50, "remaining": 10 } }
                  ]
                }
                """,
            ),
        )

        assertEquals(2, windows.size)
        assertEquals(40.0, windows[0].usedPercent!!, 0.001)
        assertEquals(80.0, windows[1].usedPercent!!, 0.001)
    }

    @Test
    fun `a reported used wins over a derived one`() {
        // If a block ever carries both, the number the provider stated outranks arithmetic.
        val windows = KimiUsageParser.parse(
            payload("""{ "usage": { "limit": 100, "used": 90, "remaining": 50 } }"""),
        )
        assertEquals(90.0, windows.single().usedPercent!!, 0.001)
    }

    @Test
    fun `a window with no limit is dropped rather than shown as full`() {
        // Dividing by a zero limit is infinity or NaN, and a bar drawn from it reads as an
        // exhausted account that is not exhausted.
        assertTrue(
            KimiUsageParser.parse(payload("""{ "usage": { "limit": 0, "used": 5 } }""")).isEmpty(),
        )
        assertTrue(
            KimiUsageParser.parse(payload("""{ "usage": { "used": 5 } }""")).isEmpty(),
        )
    }

    @Test
    fun `the window duration is read, not assumed from position`() {
        val windows = KimiUsageParser.parse(
            payload(
                """
                { "limits": [
                    { "name": "Monthly", "windowSeconds": 2592000,
                      "detail": { "limit": 10, "remaining": 5 } }
                ] }
                """,
            ),
        )
        assertEquals(2_592_000L, windows.single().periodSeconds)
        // Not classified as the five-hour window just because it came first.
        assertTrue(windows.single().category != WindowCategory.FIVE_HOUR)
    }

    @Test
    fun `an unnamed window is labelled from its duration`() {
        val windows = KimiUsageParser.parse(
            payload("""{ "limits": [ { "detail": { "limit": 10, "remaining": 5 } } ] }"""),
        )
        assertEquals("5h limit", windows.single().label)
        assertEquals(WindowCategory.FIVE_HOUR, windows.single().category)
    }

    @Test
    fun `a reset stamp in seconds and one in milliseconds mean the same instant`() {
        val seconds = KimiUsageParser.parse(
            payload("""{ "usage": { "limit": 10, "used": 1, "resetTime": 1800000000 } }"""),
        ).single().resetAt
        val millis = KimiUsageParser.parse(
            payload("""{ "usage": { "limit": 10, "used": 1, "resetTime": 1800000000000 } }"""),
        ).single().resetAt

        assertNotNull(seconds)
        assertEquals(1_800_000_000_000L, seconds)
        assertEquals(seconds, millis)
    }

    @Test
    fun `an empty response yields nothing rather than an invented window`() {
        assertTrue(KimiUsageParser.parse(payload("{}")).isEmpty())
        assertNull(KimiUsageParser.parse(payload("""{ "limits": [] }""")).firstOrNull())
    }
}
