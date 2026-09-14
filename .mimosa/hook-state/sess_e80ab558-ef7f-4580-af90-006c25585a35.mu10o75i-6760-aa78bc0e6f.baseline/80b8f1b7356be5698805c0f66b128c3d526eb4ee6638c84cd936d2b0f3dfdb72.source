package com.usagelimits.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a window reports when the arithmetic behind it went wrong.
 *
 * A provider stating a limit of zero, or a payload carrying a literal NaN, produces a non-finite
 * percentage. The clamp does not catch one — NaN compares false against every bound, so
 * `coerceIn` hands it straight back — and every consumer downstream then renders or ranks a
 * number that is not one.
 *
 * The iOS twin refuses these for the same reason, and the consequence there is worse: Swift's
 * `Int(_:)` traps on a non-finite value where Kotlin's `toInt()` saturates, and it does so inside
 * a widget extension, where a trap is a blank tile with no diagnostic anywhere.
 */
class RemainingPercentTest {

    private fun window(usedPercent: Double?) = UsageWindow(
        id = "5h",
        label = "5h limit",
        category = WindowCategory.FIVE_HOUR,
        usedPercent = usedPercent,
        periodSeconds = 18_000,
        resetAt = null,
        exhausted = false,
    )

    @Test
    fun `a NaN usage reads as unknown rather than as a number`() {
        assertNull(window(Double.NaN).remainingPercent)
    }

    @Test
    fun `an infinite usage reads as unknown in both directions`() {
        assertNull(window(Double.POSITIVE_INFINITY).remainingPercent)
        assertNull(window(Double.NEGATIVE_INFINITY).remainingPercent)
    }

    @Test
    fun `an absurd but finite usage is clamped rather than refused`() {
        // The clamp handles every finite value, so the guard is about non-finite ones alone: a
        // merely enormous figure still means "nothing left", which the app can honestly say.
        assertEquals(0.0, window(1e300).remainingPercent!!, 1e-9)
    }

    @Test
    fun `an ordinary figure is unaffected`() {
        assertEquals(60.0, window(40.0).remainingPercent!!, 1e-9)
    }
}
