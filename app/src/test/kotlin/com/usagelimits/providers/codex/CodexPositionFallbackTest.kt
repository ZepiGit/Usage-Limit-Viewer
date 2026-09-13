package com.usagelimits.providers.codex

import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.WindowCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When declared order may stand in for a stated duration — and when it may not.
 *
 * Known durations and legacy positional assignments keep their identities. An unfamiliar
 * declared duration must survive in a remaining slot without changing its period or category;
 * a slot suffix is not a claim that an unknown window lasts five hours or a week.
 */
class CodexPositionFallbackTest {

    private val now = 1_788_955_200_000L // 2026-09-09T12:00:00Z

    private fun payload(text: String): JsonObject =
        Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `a stated but unfamiliar duration is not forced into the session slot`() {
        val windows = CodexUsageParser.parse(
            payload("""{"rate_limit":{"primary_window":{"limit_window_seconds":86400,"used_percent":40,"reset_at":"2026-09-09T18:00:00Z"}}}"""),
            now,
        )

        assertEquals(1, windows.size)
        val window = windows.single()
        assertEquals("codex-long", window.id)
        assertEquals(WindowCategory.OTHER, window.category)
        assertEquals("Limit", window.label)
        assertEquals(86_400L, window.periodSeconds)
        assertEquals(40.0, window.usedPercent)
        assertEquals(60.0, window.remainingPercent)
        assertEquals(Severity.HEALTHY, window.severity)
        assertEquals(1_788_976_800_000L, window.resetAt)
    }

    @Test
    fun `two unfamiliar durations preserve both windows and their values`() {
        val windows = CodexUsageParser.parse(payload("""{"rate_limit":{
            "primary_window":{"limit_window_seconds":86400,"used_percent":40},
            "secondary_window":{"limit_window_seconds":43200,"used_percent":10}
        }}"""), now)

        assertEquals(2, windows.size)
        assertEquals(2, windows.map { it.id }.toSet().size)
        assertEquals(setOf(86_400L, 43_200L), windows.map { it.periodSeconds }.toSet())
        for (window in windows) {
            assertEquals(WindowCategory.OTHER, window.category)
            assertEquals("Limit", window.label)
            assertEquals(if (window.periodSeconds == 86_400L) 40.0 else 10.0, window.usedPercent)
        }
    }

    @Test
    fun `known and legacy slots keep priority over unfamiliar durations`() {
        for (known in listOf("18000", "604800", "null")) {
            for (unknownFirst in listOf(true, false)) {
                val unknown = """{"limit_window_seconds":86400,"used_percent":100}"""
                val established = """{"limit_window_seconds":$known,"used_percent":10}"""
                val primary = if (unknownFirst) unknown else established
                val secondary = if (unknownFirst) established else unknown
                val windows = CodexUsageParser.parse(payload("""{"rate_limit":{
                    "primary_window":$primary,"secondary_window":$secondary
                }}"""), now)
                assertEquals(2, windows.size)
                val unfamiliar = windows.single { it.periodSeconds == 86_400L }
                assertEquals(WindowCategory.OTHER, unfamiliar.category)
                assertEquals(Severity.EXHAUSTED, unfamiliar.severity)
                val retained = windows.single { it !== unfamiliar }
                val expectedID = when (known) {
                    "18000" -> "codex-short"
                    "604800" -> "codex-long"
                    else -> if (unknownFirst) "codex-long" else "codex-short"
                }
                assertEquals(expectedID, retained.id)
                assertEquals(10.0, retained.usedPercent)
            }
        }
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
