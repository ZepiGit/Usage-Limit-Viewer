package com.usagelimits.providers.claude

import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Fixtures are synthetic: the shapes mirror `/api/oauth/usage`, the numbers and identifiers do
 * not come from a real account.
 */
class ClaudeUsageParserTest {

    private val now = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli()

    /** The payload from the endpoint contract, with a `limits` entry describing Fable. */
    private val fullPayload = """
        {
          "five_hour":  { "utilization": 42.5, "resets_at": "2026-09-09T17:30:00Z" },
          "seven_day":  { "utilization": 47.0, "resets_at": "2026-09-14T00:00:00Z" },
          "seven_day_opus": { "utilization": 10.0, "resets_at": null },
          "iguana_necktie": { "utilization": 5.0, "resets_at": "2026-09-14T00:00:00Z" },
          "limits": [ { "kind": "weekly_scoped", "percent": 12.0, "is_active": true,
                        "scope": { "model": { "display_name": "Fable" } } } ]
        }
    """.trimIndent()

    @Test
    fun `five hour window is a five hour category with an 18000 second period`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)

        val fiveHour = windows.single { it.id == "five-hour" }
        assertEquals(WindowCategory.FIVE_HOUR, fiveHour.category)
        assertEquals(18_000L, fiveHour.periodSeconds)
        assertEquals("5h limit", fiveHour.label)
    }

    @Test
    fun `every non five hour window is weekly with a 604800 second period`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)

        val weekly = windows.filterNot { it.id == "five-hour" }
        assertEquals(listOf("seven-day", "seven-day-opus", "seven-day-fable"), weekly.map { it.id })
        weekly.forEach { window ->
            assertEquals(window.id, WindowCategory.WEEKLY, window.category)
            assertEquals(window.id, 604_800L, window.periodSeconds)
        }
    }

    @Test
    fun `utilization maps to usedPercent unchanged`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)
            .associateBy { it.id }

        assertEquals(42.5, windows.getValue("five-hour").usedPercent!!, 0.0001)
        assertEquals(47.0, windows.getValue("seven-day").usedPercent!!, 0.0001)
        assertEquals(10.0, windows.getValue("seven-day-opus").usedPercent!!, 0.0001)
        // Consumption in, remaining out — the UI reads the derived value.
        assertEquals(57.5, windows.getValue("five-hour").remainingPercent!!, 0.0001)
    }

    @Test
    fun `resets_at is parsed to epoch millis`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)
            .associateBy { it.id }

        assertEquals(
            Instant.parse("2026-09-09T17:30:00Z").toEpochMilli(),
            windows.getValue("five-hour").resetAt,
        )
        assertEquals(
            Instant.parse("2026-09-14T00:00:00Z").toEpochMilli(),
            windows.getValue("seven-day").resetAt,
        )
    }

    @Test
    fun `a null resets_at yields a null resetAt rather than dropping the window`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)

        val opus = windows.single { it.id == "seven-day-opus" }
        assertNull(opus.resetAt)
        assertNotNull(opus.usedPercent)
    }

    @Test
    fun `camelCase resetsAt is accepted alongside snake_case`() {
        val payload = JsonSupport.parseObject(
            """
            { "five_hour": { "utilization": 1.0, "resetsAt": "2026-09-09T17:30:00Z" } }
            """.trimIndent(),
        )

        assertEquals(
            Instant.parse("2026-09-09T17:30:00Z").toEpochMilli(),
            ClaudeUsageParser.parse(payload, now).single().resetAt,
        )
    }

    @Test
    fun `unknown top level keys are ignored`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "five_hour": { "utilization": 3.0, "resets_at": "2026-09-09T17:30:00Z" },
              "thirty_day_quokka": { "utilization": 99.0, "resets_at": "2026-10-01T00:00:00Z" },
              "organization": { "uuid": "11111111-2222-3333-4444-555555555555" },
              "unexpected_scalar": 7
            }
            """.trimIndent(),
        )

        val windows = ClaudeUsageParser.parse(payload, now)
        assertEquals(listOf("five-hour"), windows.map { it.id })
    }

    @Test
    fun `an empty payload yields no windows`() {
        assertTrue(ClaudeUsageParser.parse(JsonSupport.parseObject("{}"), now).isEmpty())
    }

    @Test
    fun `a matching limits entry replaces the iguana_necktie window`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)

        // Exactly one Fable row: the codenamed key must not render beside its own limits entry.
        val fable = windows.single { it.label == "Weekly (Fable)" }
        assertEquals("seven-day-fable", fable.id)
        assertEquals(12.0, fable.usedPercent!!, 0.0001)
        // The replacement takes its reset from the limits entry, which carries none here.
        assertNull(fable.resetAt)
        assertTrue(windows.none { it.id == "iguana-necktie" })
    }

    @Test
    fun `without a matching limits entry the iguana_necktie window is kept`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "iguana_necktie": { "utilization": 5.0, "resets_at": "2026-09-14T00:00:00Z" },
              "limits": [ { "kind": "weekly_scoped", "percent": 12.0, "is_active": true,
                            "scope": { "model": { "display_name": "Sonnet" } } } ]
            }
            """.trimIndent(),
        )

        val window = ClaudeUsageParser.parse(payload, now).single()
        assertEquals("iguana-necktie", window.id)
        assertEquals("Weekly (Fable)", window.label)
        assertEquals(5.0, window.usedPercent!!, 0.0001)
        assertEquals(
            Instant.parse("2026-09-14T00:00:00Z").toEpochMilli(),
            window.resetAt,
        )
    }

    @Test
    fun `a window at 100 percent utilization is exhausted`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "five_hour": { "utilization": 100.0, "resets_at": "2026-09-09T17:30:00Z" },
              "seven_day": { "utilization": 99.9, "resets_at": "2026-09-14T00:00:00Z" }
            }
            """.trimIndent(),
        )

        val windows = ClaudeUsageParser.parse(payload, now).associateBy { it.id }
        assertTrue(windows.getValue("five-hour").exhausted)
        assertEquals(0.0, windows.getValue("five-hour").remainingPercent!!, 0.0001)
        assertFalse(windows.getValue("seven-day").exhausted)
    }
}
