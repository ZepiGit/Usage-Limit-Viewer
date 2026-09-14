package com.usagelimits.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The subscription tier, spelled the same way whichever provider supplied it.
 *
 * Anthropic hands back `default_claude_max_5x` and OpenAI hands back a bare `plus`. The Codex
 * path used to pass its value straight through, so one account read "OpenAI Codex plus" while
 * the Claude beside it read "Claude Max 5×" — same screen, same row, different conventions.
 */
class PlanLabelTest {

    @Test
    fun `a bare lowercase tier is capitalised`() {
        // The Codex case, and the reason this exists.
        assertEquals("Plus", planLabel("plus"))
        assertEquals("Pro", planLabel("pro"))
        assertEquals("Free", planLabel("free"))
    }

    @Test
    fun `nothing in means nothing out, rather than an empty badge`() {
        assertNull(planLabel(null))
        assertNull(planLabel(""))
        assertNull(planLabel("   "))
        assertNull(planLabel("__"))
    }

    @Test
    fun `separators become spaces and do not double up`() {
        assertEquals("Team Plus", planLabel("team_plus"))
        assertEquals("Team Plus", planLabel("team__plus"))
        // Not '-': the Swift twin splits on '_' alone, so splitting on a hyphen here would
        // make the two apps print different things for the same tier.
        assertEquals("Team-plus", planLabel("team-plus"))
    }

    @Test
    fun `a trailing multiplier is a multiplier`() {
        assertEquals("Max 5×", planLabel("max_5x"))
        assertEquals("Max 20×", planLabel("max_20x"))
    }

    @Test
    fun `a tier that is nothing but a multiplier is a name`() {
        // Reading `20x` as a multiplier leaves no tier for it to multiply, and returning null
        // there is how the shared formatter first broke Claude: `claude_20x` strips to this.
        assertEquals("20x", planLabel("20x"))
        assertEquals("5x", planLabel("5x"))
    }

    @Test
    fun `a tier nobody has seen yet still reads as words`() {
        // Deliberately not a lookup table. Vendors add tiers, and a table renders a new one as
        // no plan at all — which looks exactly like an account that has no subscription.
        assertEquals("Ultra Business", planLabel("ultra_business"))
        assertEquals("Scholar", planLabel("SCHOLAR"))
    }
}
