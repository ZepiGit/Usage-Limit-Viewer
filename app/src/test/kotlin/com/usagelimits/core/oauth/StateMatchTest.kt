package com.usagelimits.core.oauth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI echoes the state it was sent, sometimes with onboarding metadata appended after a
 * dot — `<state>.onboarding_entrypoint=life_sciences` is the form the Codex CLI accepts. An
 * exact comparison turned those sign-ins into a redirect the listener refused, then a wait
 * that ran out the clock. The random part still has to come back in full.
 */
class StateMatchTest {

    private val state = "QmVhdXRpZnVsLXJhbmRvbS1zdGF0ZS12YWx1ZQ"

    @Test
    fun `an exact echo matches`() {
        assertTrue(Pkce.stateMatches(state, state))
    }

    @Test
    fun `a dot-separated suffix after the full state matches`() {
        assertTrue(Pkce.stateMatches(state, "$state.onboarding_entrypoint=life_sciences"))
        assertTrue(Pkce.stateMatches(state, "$state."))
    }

    @Test
    fun `anything short of the full state does not`() {
        assertFalse(Pkce.stateMatches(state, state.dropLast(1)))
        assertFalse(Pkce.stateMatches(state, state.dropLast(1) + ".suffix"))
        assertFalse(Pkce.stateMatches(state, ""))
    }

    @Test
    fun `a longer value without the separator does not`() {
        assertFalse(Pkce.stateMatches(state, state + "x"))
        assertFalse(Pkce.stateMatches(state, state + "-suffix"))
        assertFalse(Pkce.stateMatches(state, "x$state"))
    }
}
