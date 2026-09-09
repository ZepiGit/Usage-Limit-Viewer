package com.usagelimits.providers.codex

import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Fixtures are synthetic: the shapes mirror `/backend-api/wham/usage` and
 * `/backend-api/wham/rate-limit-reset-credits`, the identifiers and numbers are invented.
 *
 * The clock is injected everywhere so a relative reset offset resolves to a value the test can
 * state exactly, instead of an assertion that drifts with wall-clock time.
 */
class CodexUsageParserTest {

    private val now = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli()

    // region window classification

    @Test
    fun `a monthly secondary window is classified monthly, not weekly`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "plan_type": "team",
              "rate_limit": {
                "primary_window":   { "used_percent": 18.0, "limit_window_seconds": 18000 },
                "secondary_window": { "used_percent": 63.0, "limit_window_seconds": 2592000 }
              }
            }
            """.trimIndent(),
        )

        val windows = CodexUsageParser.parse(payload, now)

        assertEquals(listOf("codex-short", "codex-long"), windows.map { it.id })
        val long = windows.single { it.id == "codex-long" }
        assertEquals(WindowCategory.MONTHLY, long.category)
        assertEquals(2_592_000L, long.periodSeconds)
        assertEquals("Monthly", long.label)
        assertEquals(63.0, long.usedPercent!!, 0.0001)
    }

    @Test
    fun `a weekly secondary window is classified weekly`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "primary_window":   { "used_percent": 18.0, "limit_window_seconds": 18000 },
                "secondary_window": { "used_percent": 63.0, "limit_window_seconds": 604800 }
              }
            }
            """.trimIndent(),
        )

        val long = CodexUsageParser.parse(payload, now).single { it.id == "codex-long" }
        assertEquals(WindowCategory.WEEKLY, long.category)
        assertEquals(604_800L, long.periodSeconds)
        assertEquals("Weekly", long.label)
    }

    @Test
    fun `slot position does not decide the category — declared seconds do`() {
        // The five-hour window arrives second and the weekly one first, the reverse of the
        // usual layout. Trusting the slot would label a week as five hours.
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "primary_window":   { "used_percent": 70.0, "limit_window_seconds": 604800 },
                "secondary_window": { "used_percent": 25.0, "limit_window_seconds": 18000 }
              }
            }
            """.trimIndent(),
        )

        val windows = CodexUsageParser.parse(payload, now).associateBy { it.id }

        val short = windows.getValue("codex-short")
        assertEquals(WindowCategory.FIVE_HOUR, short.category)
        assertEquals(18_000L, short.periodSeconds)
        assertEquals(25.0, short.usedPercent!!, 0.0001)

        val long = windows.getValue("codex-long")
        assertEquals(WindowCategory.WEEKLY, long.category)
        assertEquals(604_800L, long.periodSeconds)
        assertEquals(70.0, long.usedPercent!!, 0.0001)
    }

    @Test
    fun `windows with no declared duration fall back to primary then secondary order`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "primary_window":   { "used_percent": 11.0 },
                "secondary_window": { "used_percent": 22.0 }
              }
            }
            """.trimIndent(),
        )

        val windows = CodexUsageParser.parse(payload, now)

        assertEquals(listOf("codex-short", "codex-long"), windows.map { it.id })
        assertEquals(11.0, windows[0].usedPercent!!, 0.0001)
        assertEquals(22.0, windows[1].usedPercent!!, 0.0001)
        // Nothing is invented: an unknown duration stays unknown rather than being guessed.
        windows.forEach { window ->
            assertEquals(window.id, WindowCategory.OTHER, window.category)
            assertNull(window.id, window.periodSeconds)
            assertEquals(window.id, "Limit", window.label)
        }
    }

    @Test
    fun `a lone primary window produces one window, not an empty long slot`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "primary_window": { "used_percent": 8.0, "limit_window_seconds": 18000 }
              }
            }
            """.trimIndent(),
        )

        val window = CodexUsageParser.parse(payload, now).single()
        assertEquals("codex-short", window.id)
        assertEquals(WindowCategory.FIVE_HOUR, window.category)
    }

    // endregion

    // region grouping

    @Test
    fun `code review windows are grouped and labelled apart from ordinary usage`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "primary_window": { "used_percent": 5.0, "limit_window_seconds": 18000 }
              },
              "code_review_rate_limit": {
                "primary_window":   { "used_percent": 30.0, "limit_window_seconds": 18000 },
                "secondary_window": { "used_percent": 40.0, "limit_window_seconds": 604800 }
              }
            }
            """.trimIndent(),
        )

        val windows = CodexUsageParser.parse(payload, now)

        assertEquals(
            listOf("codex-short", "code-review-short", "code-review-long"),
            windows.map { it.id },
        )
        assertNull(windows[0].group)
        assertEquals("Code review", windows[1].group)
        assertEquals("Code review · 5h limit", windows[1].label)
        assertEquals("Code review", windows[2].group)
        assertEquals("Code review · Weekly", windows[2].label)
        assertEquals(40.0, windows[2].usedPercent!!, 0.0001)
    }

    @Test
    fun `additional rate limits become windows named after the entry`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "additional_rate_limits": [
                {
                  "name": "Sora video",
                  "primary_window":   { "used_percent": 12.0, "limit_window_seconds": 18000 },
                  "secondary_window": { "used_percent": 34.0, "limit_window_seconds": 604800 }
                }
              ]
            }
            """.trimIndent(),
        )

        val windows = CodexUsageParser.parse(payload, now)

        assertEquals(
            listOf("additional-sora-video-0-short", "additional-sora-video-0-long"),
            windows.map { it.id },
        )
        windows.forEach { assertEquals("Sora video", it.group) }
        assertEquals("Sora video · 5h limit", windows[0].label)
        assertEquals("Sora video · Weekly", windows[1].label)
        assertEquals(34.0, windows[1].usedPercent!!, 0.0001)
    }

    @Test
    fun `an unnamed additional rate limit falls back to its position`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "additional_rate_limits": [
                { "primary_window": { "used_percent": 9.0, "limit_window_seconds": 18000 } }
              ]
            }
            """.trimIndent(),
        )

        val window = CodexUsageParser.parse(payload, now).single()
        assertEquals("additional-additional-1-0-short", window.id)
        assertEquals("Additional 1", window.group)
        assertEquals("Additional 1 · 5h limit", window.label)
    }

    // endregion

    // region exhaustion

    @Test
    fun `limit_reached forces a fully used window even with no percentage reported`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "limit_reached": true,
                "primary_window": { "limit_window_seconds": 18000, "reset_after_seconds": 1800 }
              }
            }
            """.trimIndent(),
        )

        val window = CodexUsageParser.parse(payload, now).single()
        assertEquals(100.0, window.usedPercent!!, 0.0001)
        assertEquals(0.0, window.remainingPercent!!, 0.0001)
        assertTrue(window.exhausted)
    }

    @Test
    fun `allowed false forces a fully used window even with no percentage reported`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "allowed": false,
                "primary_window":   { "limit_window_seconds": 18000 },
                "secondary_window": { "limit_window_seconds": 604800 }
              }
            }
            """.trimIndent(),
        )

        val windows = CodexUsageParser.parse(payload, now)
        assertEquals(2, windows.size)
        // The flag sits on the parent, so both windows of the family are spent.
        windows.forEach { window ->
            assertEquals(window.id, 100.0, window.usedPercent!!, 0.0001)
            assertTrue(window.id, window.exhausted)
        }
    }

    @Test
    fun `an allowed family with no percentage reports unknown rather than zero used`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "allowed": true,
                "primary_window": { "limit_window_seconds": 18000 }
              }
            }
            """.trimIndent(),
        )

        val window = CodexUsageParser.parse(payload, now).single()
        assertNull(window.usedPercent)
        assertNull(window.remainingPercent)
        assertFalse(window.exhausted)
    }

    @Test
    fun `a window at 100 percent is exhausted without any parent flag`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "primary_window":   { "used_percent": 100.0, "limit_window_seconds": 18000 },
                "secondary_window": { "used_percent": 99.9,  "limit_window_seconds": 604800 }
              }
            }
            """.trimIndent(),
        )

        val windows = CodexUsageParser.parse(payload, now).associateBy { it.id }
        assertTrue(windows.getValue("codex-short").exhausted)
        assertFalse(windows.getValue("codex-long").exhausted)
    }

    // endregion

    // region reset instants

    @Test
    fun `reset_after_seconds is resolved against the injected clock`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "primary_window": {
                  "used_percent": 4.0, "limit_window_seconds": 18000, "reset_after_seconds": 3600
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(now + 3_600_000L, CodexUsageParser.parse(payload, now).single().resetAt)
    }

    @Test
    fun `an absolute reset_at wins over reset_after_seconds`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "primary_window": {
                  "used_percent": 4.0,
                  "limit_window_seconds": 18000,
                  "reset_at": "2026-09-09T17:30:00Z",
                  "reset_after_seconds": 3600
                }
              }
            }
            """.trimIndent(),
        )

        val resetAt = CodexUsageParser.parse(payload, now).single().resetAt
        assertEquals(Instant.parse("2026-09-09T17:30:00Z").toEpochMilli(), resetAt)
    }

    @Test
    fun `a window with no reset information keeps a null resetAt`() {
        val payload = JsonSupport.parseObject(
            """
            { "rate_limit": { "primary_window": { "used_percent": 4.0, "limit_window_seconds": 18000 } } }
            """.trimIndent(),
        )

        assertNull(CodexUsageParser.parse(payload, now).single().resetAt)
    }

    // endregion

    // region spelling and tolerance

    @Test
    fun `camelCase spellings parse identically to snake_case`() {
        val snake = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "limit_reached": false,
                "primary_window": {
                  "used_percent": 25.0, "limit_window_seconds": 18000,
                  "reset_at": "2026-09-09T17:30:00Z"
                },
                "secondary_window": {
                  "used_percent": 50.0, "limit_window_seconds": 604800, "reset_after_seconds": 7200
                }
              }
            }
            """.trimIndent(),
        )
        val camel = JsonSupport.parseObject(
            """
            {
              "rateLimit": {
                "limitReached": false,
                "primaryWindow": {
                  "usedPercent": 25.0, "limitWindowSeconds": 18000,
                  "resetAt": "2026-09-09T17:30:00Z"
                },
                "secondaryWindow": {
                  "usedPercent": 50.0, "limitWindowSeconds": 604800, "resetAfterSeconds": 7200
                }
              }
            }
            """.trimIndent(),
        )

        val expected = CodexUsageParser.parse(snake, now)
        assertEquals(2, expected.size)
        assertEquals(expected, CodexUsageParser.parse(camel, now))
    }

    @Test
    fun `an empty payload yields no windows`() {
        assertTrue(CodexUsageParser.parse(JsonSupport.parseObject("{}"), now).isEmpty())
    }

    @Test
    fun `unknown families and unknown fields are ignored`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "primary_window": {
                  "used_percent": 8.0, "limit_window_seconds": 18000, "future_field": { "x": 1 }
                },
                "unexpected": [1, 2, 3]
              },
              "some_new_family": {
                "primary_window": { "used_percent": 99.0, "limit_window_seconds": 18000 }
              },
              "plan_type": "pro"
            }
            """.trimIndent(),
        )

        val window = CodexUsageParser.parse(payload, now).single()
        assertEquals("codex-short", window.id)
        assertEquals(8.0, window.usedPercent!!, 0.0001)
    }

    @Test
    fun `a non object entry in additional_rate_limits is skipped rather than fatal`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "additional_rate_limits": [
                "unexpected",
                { "name": "Deep research",
                  "primary_window": { "used_percent": 3.0, "limit_window_seconds": 18000 } }
              ]
            }
            """.trimIndent(),
        )

        val window = CodexUsageParser.parse(payload, now).single()
        assertEquals("Deep research", window.group)
        // The surviving entry keeps its own index, so ids stay stable against the payload.
        assertEquals("additional-deep-research-1-short", window.id)
    }

    // endregion

    // region reset credits

    private val creditsPayload = """
        {
          "available_count": 2,
          "applicable_available_count": 1,
          "credits": [
            {
              "id": "credit-available",
              "reset_type": "codex_rate_limits",
              "status": "available",
              "granted_at": "2026-09-01T00:00:00Z",
              "expires_at": "2026-10-01T00:00:00Z"
            },
            { "id": "credit-spent",      "reset_type": "codex_rate_limits", "status": "spent" },
            { "id": "credit-other-type", "reset_type": "sora_rate_limits",  "status": "available" },
            { "id": "credit-untyped",    "status": "available" }
          ]
        }
    """.trimIndent()

    @Test
    fun `only available codex credits survive the filter`() {
        val credits = CodexUsageParser.parseResetCredits(JsonSupport.parseObject(creditsPayload))

        // A spent credit and a credit for an unrelated reset are both unusable here; an absent
        // reset_type is not a mismatch, so that credit is kept.
        assertEquals(listOf("credit-available", "credit-untyped"), credits.map { it.id })
        credits.forEach { assertEquals("available", it.status) }
    }

    @Test
    fun `credit timestamps are parsed to epoch millis and stay null when absent`() {
        val credits = CodexUsageParser.parseResetCredits(JsonSupport.parseObject(creditsPayload))
            .associateBy { it.id }

        val available = credits.getValue("credit-available")
        assertEquals(Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(), available.grantedAt)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), available.expiresAt)

        val untyped = credits.getValue("credit-untyped")
        assertNull(untyped.grantedAt)
        assertNull(untyped.expiresAt)
    }

    @Test
    fun `camelCase credit timestamps are accepted`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "credits": [
                { "id": "credit-camel", "resetType": "codex_rate_limits", "status": "available",
                  "grantedAt": "2026-09-01T00:00:00Z", "expiresAt": "2026-10-01T00:00:00Z" }
              ]
            }
            """.trimIndent(),
        )

        val credit = CodexUsageParser.parseResetCredits(payload).single()
        assertEquals("credit-camel", credit.id)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(), credit.grantedAt)
    }

    @Test
    fun `a credit without an id is dropped`() {
        val payload = JsonSupport.parseObject(
            """
            { "credits": [ { "reset_type": "codex_rate_limits", "status": "available" } ] }
            """.trimIndent(),
        )

        assertTrue(CodexUsageParser.parseResetCredits(payload).isEmpty())
    }

    @Test
    fun `a null or empty credits payload yields no credits`() {
        assertTrue(CodexUsageParser.parseResetCredits(null).isEmpty())
        assertTrue(CodexUsageParser.parseResetCredits(JsonSupport.parseObject("{}")).isEmpty())
    }

    @Test
    fun `availableCreditCount reads the provider reported count`() {
        assertEquals(
            2,
            CodexUsageParser.availableCreditCount(JsonSupport.parseObject(creditsPayload)),
        )
        assertEquals(
            5,
            CodexUsageParser.availableCreditCount(
                JsonSupport.parseObject("""{ "availableCount": 5 }"""),
            ),
        )
        assertNull(CodexUsageParser.availableCreditCount(null))
        assertNull(CodexUsageParser.availableCreditCount(JsonSupport.parseObject("{}")))
    }

    @Test
    fun `parseEmbeddedResetCredits reads the copy nested in the usage payload`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "rate_limit": {
                "primary_window": { "used_percent": 8.0, "limit_window_seconds": 18000 }
              },
              "rate_limit_reset_credits": {
                "available_count": 1,
                "credits": [
                  { "id": "embedded-credit", "reset_type": "codex_rate_limits",
                    "status": "available" },
                  { "id": "embedded-spent",  "reset_type": "codex_rate_limits", "status": "spent" }
                ]
              }
            }
            """.trimIndent(),
        )

        val credits = CodexUsageParser.parseEmbeddedResetCredits(payload)
        assertEquals(listOf("embedded-credit"), credits.map { it.id })
    }

    @Test
    fun `parseEmbeddedResetCredits is empty when the usage payload carries no copy`() {
        val payload = JsonSupport.parseObject(
            """
            { "rate_limit": { "primary_window": { "used_percent": 8.0 } } }
            """.trimIndent(),
        )

        assertTrue(CodexUsageParser.parseEmbeddedResetCredits(payload).isEmpty())
    }

    // endregion

    @Test
    fun `parsePlan reads plan_type in either spelling`() {
        assertEquals(
            "team",
            CodexUsageParser.parsePlan(JsonSupport.parseObject("""{ "plan_type": "team" }""")),
        )
        assertEquals(
            "pro",
            CodexUsageParser.parsePlan(JsonSupport.parseObject("""{ "planType": "pro" }""")),
        )
        assertNull(CodexUsageParser.parsePlan(JsonSupport.parseObject("{}")))
    }
}
