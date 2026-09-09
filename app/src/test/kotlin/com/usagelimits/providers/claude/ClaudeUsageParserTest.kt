package com.usagelimits.providers.claude

import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Fixtures are synthetic. The shapes mirror `/api/oauth/usage` as a live account returns it —
 * flat window keys alongside a `limits` array carrying the same quotas — but every number and
 * identifier is invented.
 */
class ClaudeUsageParserTest {

    private val now = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli()

    /**
     * The production shape: flat keys, a `limits` array covering the same ground, unused
     * codename slots, and a credit balance that is not a window.
     *
     * `iguana_necktie` is null here while `limits` carries the Fable figure — that really is
     * how a live account answers, and it is why `limits` is the primary source.
     */
    private val fullPayload = """
        {
          "five_hour":  { "utilization": 21.0, "resets_at": "2026-09-10T01:29:59Z",
                          "locked_reason": null },
          "seven_day":  { "utilization": 3.0,  "resets_at": "2026-09-16T19:59:59Z",
                          "locked_reason": null },
          "seven_day_opus": null,
          "iguana_necktie": null,
          "nimbus_quill": { "utilization": 0.0, "resets_at": null, "locked_reason": null },
          "extra_usage": { "is_enabled": false, "monthly_limit": null, "utilization": 87.0 },
          "member_dashboard_available": false,
          "limits": [
            { "kind": "session",       "group": "session", "percent": 21, "is_active": true,
              "resets_at": "2026-09-10T01:29:59Z", "scope": null },
            { "kind": "weekly_all",    "group": "weekly",  "percent": 3,  "is_active": false,
              "resets_at": "2026-09-16T19:59:59Z", "scope": null },
            { "kind": "weekly_scoped", "group": "weekly",  "percent": 0,  "is_active": false,
              "resets_at": "2026-09-16T20:00:00Z",
              "scope": { "model": { "id": null, "display_name": "Fable" } } }
          ]
        }
    """.trimIndent()

    /** The same account as it answers when the payload carries no `limits` array. */
    private val flatOnlyPayload = """
        {
          "five_hour":  { "utilization": 42.5, "resets_at": "2026-09-09T17:30:00Z" },
          "seven_day":  { "utilization": 47.0, "resets_at": "2026-09-14T00:00:00Z" },
          "seven_day_opus": { "utilization": 10.0, "resets_at": null },
          "iguana_necktie": { "utilization": 5.0, "resets_at": "2026-09-14T00:00:00Z" }
        }
    """.trimIndent()

    // region limits is the primary source

    @Test
    fun `limits entries become the windows and the flat twins are not repeated`() {
        // Reading both lists would render every quota twice, and a duplicated weekly row is
        // indistinguishable from a second real limit.
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)

        assertEquals(listOf("five-hour", "seven-day", "seven-day-fable"), windows.map { it.id })
    }

    @Test
    fun `a session limit is a five hour window and a weekly limit is a seven day window`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)
            .associateBy { it.id }

        assertEquals(WindowCategory.FIVE_HOUR, windows.getValue("five-hour").category)
        assertEquals(18_000L, windows.getValue("five-hour").periodSeconds)
        assertEquals("5h limit", windows.getValue("five-hour").label)

        assertEquals(WindowCategory.WEEKLY, windows.getValue("seven-day").category)
        assertEquals(604_800L, windows.getValue("seven-day").periodSeconds)
        assertEquals("Weekly", windows.getValue("seven-day").label)
    }

    @Test
    fun `a scoped weekly limit is labelled with its model`() {
        // The point of reading `limits` rather than the codenamed key: this row carries the
        // model name, so the user sees "Weekly (Fable)" instead of "Iguana Necktie".
        val fable = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)
            .single { it.id == "seven-day-fable" }

        assertEquals("Weekly (Fable)", fable.label)
        assertEquals(0.0, fable.usedPercent!!, 0.0001)
        assertEquals(WindowCategory.WEEKLY, fable.category)
    }

    @Test
    fun `integer percents parse as readily as decimals`() {
        // Production sends `"percent": 21`, not `21.0`.
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)
            .associateBy { it.id }

        assertEquals(21.0, windows.getValue("five-hour").usedPercent!!, 0.0001)
        assertEquals(3.0, windows.getValue("seven-day").usedPercent!!, 0.0001)
        assertEquals(79.0, windows.getValue("five-hour").remainingPercent!!, 0.0001)
    }

    @Test
    fun `limits resets are parsed to epoch millis`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)
            .associateBy { it.id }

        assertEquals(
            Instant.parse("2026-09-10T01:29:59Z").toEpochMilli(),
            windows.getValue("five-hour").resetAt,
        )
    }

    @Test
    fun `an unrecognised limit kind is kept with the duration its group implies`() {
        // A new `kind` must cost a label, not a whole quota. `group` still says which horizon
        // it belongs to, which is enough to place it on the right screen.
        val payload = JsonSupport.parseObject(
            """
            {
              "limits": [
                { "kind": "weekly_cowork", "group": "weekly", "percent": 44 },
                { "kind": "brand_new_thing", "group": "something_else", "percent": 12 }
              ]
            }
            """.trimIndent(),
        )

        val windows = ClaudeUsageParser.parse(payload, now).associateBy { it.id }

        assertEquals(604_800L, windows.getValue("weekly-cowork").periodSeconds)
        assertEquals("Weekly Cowork", windows.getValue("weekly-cowork").label)
        // No group we know: OTHER states the duration is unknown rather than guessing a week.
        assertNull(windows.getValue("brand-new-thing").periodSeconds)
        assertEquals(WindowCategory.OTHER, windows.getValue("brand-new-thing").category)
    }

    @Test
    fun `two scoped limits for different models do not collide`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "limits": [
                { "kind": "weekly_scoped", "group": "weekly", "percent": 10,
                  "scope": { "model": { "display_name": "Fable" } } },
                { "kind": "weekly_scoped", "group": "weekly", "percent": 20,
                  "scope": { "model": { "display_name": "Opus" } } }
              ]
            }
            """.trimIndent(),
        )

        val windows = ClaudeUsageParser.parse(payload, now)

        assertEquals(listOf("seven-day-fable", "seven-day-opus"), windows.map { it.id })
        assertEquals(listOf("Weekly (Fable)", "Weekly (Opus)"), windows.map { it.label })
    }

    // endregion

    // region falling back to the flat keys

    @Test
    fun `a payload with no limits array falls back to the flat window keys`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(flatOnlyPayload), now)

        assertEquals(
            listOf("five-hour", "seven-day", "seven-day-opus", "iguana-necktie"),
            windows.map { it.id },
        )
    }

    @Test
    fun `only five_hour is a five hour window on the fallback path`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(flatOnlyPayload), now)

        assertEquals(WindowCategory.FIVE_HOUR, windows.first().category)
        windows.drop(1).forEach { window ->
            assertEquals(window.id, WindowCategory.WEEKLY, window.category)
            assertEquals(window.id, 604_800L, window.periodSeconds)
        }
    }

    @Test
    fun `a limits array whose every percent is unreadable falls back to the flat keys`() {
        // An array that yields nothing is no better than an absent one, and silently rendering
        // an empty screen would be the worst of the three outcomes.
        val payload = JsonSupport.parseObject(
            """
            {
              "iguana_necktie": { "utilization": 5.0, "resets_at": "2026-09-14T00:00:00Z" },
              "limits": [ { "kind": "weekly_scoped", "percent": "unavailable",
                            "scope": { "model": { "display_name": "Fable" } } } ]
            }
            """.trimIndent(),
        )

        val window = ClaudeUsageParser.parse(payload, now).single()

        assertEquals("iguana-necktie", window.id)
        assertEquals(5.0, window.usedPercent!!, 0.0001)
    }

    @Test
    fun `a null resets_at yields a null resetAt rather than dropping the window`() {
        val opus = ClaudeUsageParser.parse(JsonSupport.parseObject(flatOnlyPayload), now)
            .single { it.id == "seven-day-opus" }

        assertNull(opus.resetAt)
        assertEquals(10.0, opus.usedPercent!!, 0.0001)
    }

    @Test
    fun `camelCase resetsAt is accepted alongside snake_case`() {
        val payload = JsonSupport.parseObject(
            """{ "five_hour": { "utilization": 1.0, "resetsAt": "2026-09-09T17:30:00Z" } }""",
        )

        assertEquals(
            Instant.parse("2026-09-09T17:30:00Z").toEpochMilli(),
            ClaudeUsageParser.parse(payload, now).single().resetAt,
        )
    }

    // endregion

    // region the discovery net

    @Test
    fun `an undocumented key with real consumption is surfaced`() {
        // Anthropic ships new windows under rotating codenames. A fixed key list loses the
        // quota the moment one appears, and the number on screen goes quietly wrong.
        val payload = JsonSupport.parseObject(
            """
            {
              "five_hour": { "utilization": 3.0, "resets_at": "2026-09-09T17:30:00Z" },
              "thirty_day_quokka": { "utilization": 99.0, "resets_at": "2026-10-01T00:00:00Z" }
            }
            """.trimIndent(),
        )

        val windows = ClaudeUsageParser.parse(payload, now).associateBy { it.id }

        assertEquals(setOf("five-hour", "thirty-day-quokka"), windows.keys)
        val found = windows.getValue("thirty-day-quokka")
        assertEquals("Thirty Day Quokka", found.label)
        assertEquals(99.0, found.usedPercent!!, 0.0001)
        // Nothing documents how long this window is, and OTHER says so instead of guessing.
        assertNull(found.periodSeconds)
        assertEquals(WindowCategory.OTHER, found.category)
    }

    @Test
    fun `an untouched codename slot is not surfaced`() {
        // `nimbus_quill` sits at 0 % on a live account. A screen meant to be read in three
        // seconds does not need a row for a placeholder nobody is consuming.
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)

        assertTrue(windows.none { it.id == "nimbus-quill" })
    }

    @Test
    fun `a credit balance is not mistaken for a usage window`() {
        // `extra_usage` carries a `utilization` too, but it is a credit balance, not a rate
        // limit. Account severity is a MAX over the windows, so folding it in would grade an
        // account by money spent rather than quota consumed.
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject(fullPayload), now)

        assertTrue(windows.none { it.id == "extra-usage" })
        assertTrue(windows.none { it.usedPercent == 87.0 })
    }

    @Test
    fun `a scalar or non window object is never promoted`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "five_hour": { "utilization": 3.0, "resets_at": "2026-09-09T17:30:00Z" },
              "organization": { "uuid": "11111111-2222-3333-4444-555555555555" },
              "member_dashboard_available": false,
              "unexpected_scalar": 7
            }
            """.trimIndent(),
        )

        assertEquals(listOf("five-hour"), ClaudeUsageParser.parse(payload, now).map { it.id })
    }

    @Test
    fun `a discovered window does not duplicate one limits already reported`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "seven_day_quokka": { "utilization": 40.0, "resets_at": "2026-10-01T00:00:00Z" },
              "limits": [ { "kind": "seven_day_quokka", "group": "weekly", "percent": 40 } ]
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("seven-day-quokka"),
            ClaudeUsageParser.parse(payload, now).map { it.id },
        )
    }

    // endregion

    // region exhaustion and unreadable fields

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

    @Test
    fun `an explicitly null window key yields no row`() {
        // Live payloads null out every window the plan does not grant. A null usedPercent
        // resolves to Severity.ERROR and account severity is a MAX, so emitting these would
        // mark a perfectly healthy account as failed.
        val payload = JsonSupport.parseObject(
            """
            {
              "five_hour": { "utilization": 42.5, "resets_at": "2026-09-09T17:30:00Z" },
              "seven_day": null,
              "seven_day_opus": null
            }
            """.trimIndent(),
        )

        val windows = ClaudeUsageParser.parse(payload, now)

        assertEquals(listOf("five-hour"), windows.map { it.id })
        assertFalse(windows.any { it.severity == Severity.ERROR })
    }

    @Test
    fun `a window object without utilization yields no row while its siblings parse`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "five_hour": { "utilization": 42.5, "resets_at": "2026-09-09T17:30:00Z" },
              "seven_day": { "resets_at": "2026-09-14T00:00:00Z" },
              "seven_day_opus": { "utilization": 10.0, "resets_at": "2026-09-14T00:00:00Z" }
            }
            """.trimIndent(),
        )

        val windows = ClaudeUsageParser.parse(payload, now)

        assertEquals(listOf("five-hour", "seven-day-opus"), windows.map { it.id })
        assertEquals(42.5, windows.first().usedPercent!!, 0.0001)
    }

    @Test
    fun `a non numeric utilization yields no row while its siblings parse`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "five_hour": { "utilization": "unavailable", "resets_at": "2026-09-09T17:30:00Z" },
              "seven_day": { "utilization": null, "resets_at": "2026-09-14T00:00:00Z" },
              "seven_day_opus": { "utilization": "10.0", "resets_at": "2026-09-14T00:00:00Z" }
            }
            """.trimIndent(),
        )

        val windows = ClaudeUsageParser.parse(payload, now)

        // A numeric string is still usable; only the unreadable ones are dropped.
        assertEquals(listOf("seven-day-opus"), windows.map { it.id })
        assertEquals(10.0, windows.single().usedPercent!!, 0.0001)
    }

    @Test
    fun `every surviving window has a usable usedPercent`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "five_hour": { "utilization": 42.5, "resets_at": "2026-09-09T17:30:00Z" },
              "seven_day": { "utilization": "n/a", "resets_at": "2026-09-14T00:00:00Z" },
              "seven_day_opus": { "resets_at": "2026-09-14T00:00:00Z" },
              "seven_day_sonnet": { "utilization": 10.0, "resets_at": "2026-09-14T00:00:00Z" }
            }
            """.trimIndent(),
        )

        val windows = ClaudeUsageParser.parse(payload, now)

        assertTrue(windows.isNotEmpty())
        assertTrue(windows.all { it.usedPercent != null })
        assertFalse(windows.any { it.severity == Severity.ERROR })
    }

    @Test
    fun `the plan label keeps the tier multiplier`() {
        // Captured live: the profile reports `default_claude_max_5x`. The has_claude_max
        // boolean cannot distinguish that from Max 20x, which is a fivefold difference in the
        // very number this app exists to show.
        val profile = JsonSupport.parseObject(
            """
            {
              "account": { "has_claude_max": true, "has_claude_pro": false },
              "organization": {
                "organization_type": "claude_max",
                "rate_limit_tier": "default_claude_max_5x",
                "subscription_status": "active"
              }
            }
            """.trimIndent(),
        )

        assertEquals("Max 5×", ClaudeUsageParser.parsePlan(profile))
    }

    @Test
    fun `an unrecognised tier still yields something readable`() {
        // Anthropic adds tiers. A lookup table would render a new one as no plan at all, which
        // reads as "not subscribed" rather than "not recognised".
        val profile = JsonSupport.parseObject(
            """{ "organization": { "rate_limit_tier": "default_claude_team_premium_20x" } }""",
        )

        assertEquals("Team Premium 20×", ClaudeUsageParser.parsePlan(profile))
    }

    @Test
    fun `a tier with no multiplier reads as the plan name alone`() {
        val profile = JsonSupport.parseObject(
            """{ "organization": { "rate_limit_tier": "default_claude_pro" } }""",
        )

        assertEquals("Pro", ClaudeUsageParser.parsePlan(profile))
    }

    @Test
    fun `the booleans remain the fallback when no tier is reported`() {
        assertEquals(
            "Max",
            ClaudeUsageParser.parsePlan(
                JsonSupport.parseObject("""{ "account": { "has_claude_max": true } }"""),
            ),
        )
        assertEquals(
            "Pro",
            ClaudeUsageParser.parsePlan(
                JsonSupport.parseObject("""{ "account": { "has_claude_pro": true } }"""),
            ),
        )
        assertNull(ClaudeUsageParser.parsePlan(JsonSupport.parseObject("{}")))
    }

    @Test
    fun `an empty payload yields no windows`() {
        assertTrue(ClaudeUsageParser.parse(JsonSupport.parseObject("{}"), now).isEmpty())
    }

    // endregion
}
