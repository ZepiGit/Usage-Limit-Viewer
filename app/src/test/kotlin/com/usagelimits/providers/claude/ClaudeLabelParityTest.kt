package com.usagelimits.providers.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Label and plan formatting, pinned where it had drifted from the Swift port.
 *
 * These two implementations exist so a phone and an iPhone say the same thing about the same
 * account. Every case here is one where they said different things.
 */
class ClaudeLabelParityTest {

    private fun payload(text: String): JsonObject =
        Json.parseToJsonElement(text) as JsonObject

    private fun tier(value: String) =
        ClaudeUsageParser.parsePlan(payload("""{"organization":{"rate_limit_tier":"$value"}}"""))

    @Test
    fun `collapsed separators do not become a double space`() {
        // `split` kept the empty part between two underscores, so the name carried a gap the
        // Swift side never produced.
        assertEquals("Team Plus", tier("default_team__plus"))
    }

    @Test
    fun `a tier that is only a multiplier reads as a name`() {
        // The regex has always required a separator before the digits — which is also what
        // stops the Swift twin removing more characters than the string has.
        assertEquals("20x", tier("claude_20x"))
        assertEquals("5x", tier("5x"))
    }

    @Test
    fun `a real multiplier still reads as one`() {
        assertEquals("Max 5×", tier("default_claude_max_5x"))
    }

    @Test
    fun `a label is never cut in the middle of a character`() {
        // `take` counts UTF-16 units, so a cut landing between the halves of an emoji left a
        // lone surrogate — a malformed string that renders as a replacement box.
        val key = "a".repeat(47) + "😀" + "tail"
        val windows = ClaudeUsageParser.parse(
            payload("""{"$key":{"utilization":40,"resets_at":"2026-09-10T12:00:00Z"}}"""),
            0L,
        )

        val label = windows.single().label
        assertEquals(47, label.length)
        assertEquals(false, label.any { Character.isHighSurrogate(it) })
    }
}
