package com.usagelimits.providers.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When declared order may stand in for a stated duration — and when it may not.
 *
 * Codex reports its windows in `primary_window` and `secondary_window`, and older payloads omit
 * the duration entirely. Position is the fallback for those, and only those: a window that
 * states a duration the app does not recognise has still stated one, and overriding it by
 * position reports a day's quota as five hours'. That is the same error as reading a week as five
 * hours, which a live payload caught once already.
 */
class CodexPositionFallbackTest {

    private val now = 1_757_000_000_000L

    private fun payload(text: String): JsonObject =
        Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `a stated but unfamiliar duration is not forced into the session slot`() {
        val windows = CodexUsageParser.parse(
            payload("""{"rate_limit":{"primary_window":{"limit_window_seconds":86400,"used_percent":40}}}"""),
            now,
        )

        assertTrue(
            "a stated duration must not be overridden by position",
            windows.none { it.id == "codex-short" },
        )
    }

    @Test
    fun `a legacy window with no duration still falls back to position`() {
        val windows = CodexUsageParser.parse(
            payload("""{"rate_limit":{"primary_window":{"used_percent":40}}}"""),
            now,
        )

        assertEquals("codex-short", windows.first().id)
    }

    @Test
    fun `an explicit null duration counts as no duration`() {
        val windows = CodexUsageParser.parse(
            payload("""{"rate_limit":{"primary_window":{"limit_window_seconds":null,"used_percent":40}}}"""),
            now,
        )

        assertEquals("codex-short", windows.first().id)
    }

    @Test
    fun `a stated five-hour duration is still classified by duration`() {
        val windows = CodexUsageParser.parse(
            payload("""{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":40}}}"""),
            now,
        )

        assertEquals("codex-short", windows.first().id)
    }
}
