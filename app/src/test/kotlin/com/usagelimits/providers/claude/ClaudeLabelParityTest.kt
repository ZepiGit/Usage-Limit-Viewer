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
    fun `a label is cut by grapheme, exactly where the iOS twin cuts it`() {
        // The previous version of this test pinned the WRONG answer: it expected 47 with the
        // emoji dropped, because `take` counted UTF-16 units and the fix then discarded the
        // stranded half. Swift's `prefix` counts grapheme clusters and keeps the emoji as the
        // 48th character — and the parser's own comment said Swift was right. Now it is
        // counted the same way, and the emoji at the boundary survives whole.
        val key = "a".repeat(47) + "😀" + "tail"
        val windows = ClaudeUsageParser.parse(
            payload("""{"$key":{"utilization":40,"resets_at":"2026-09-10T12:00:00Z"}}"""),
            0L,
        )

        val label = windows.single().label
        assertEquals(48, label.codePointCount(0, label.length))
        assertEquals(true, label.endsWith("😀"))
        assertEquals(false, label.any { Character.isHighSurrogate(it) && it == label.last() })
    }
}
