package com.usagelimits.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The thresholds are the app's single colour scale, so the boundary values themselves are
 * asserted: exactly 50 % and exactly 20 % remaining each fall into the *worse* band, because the
 * comparisons are `<=`. Rounding a bar to "50 %" must never make a half-spent quota look healthy.
 */
class SeverityTest {

    @Test
    fun `the published thresholds are the ones the bands use`() {
        assertEquals(50.0, Severity.HEALTHY_ABOVE, 0.0)
        assertEquals(20.0, Severity.MEDIUM_ABOVE, 0.0)
    }

    @Test
    fun `above fifty percent remaining is healthy`() {
        assertEquals(Severity.HEALTHY, Severity.fromRemainingPercent(100.0))
        assertEquals(Severity.HEALTHY, Severity.fromRemainingPercent(82.0))
        assertEquals(Severity.HEALTHY, Severity.fromRemainingPercent(50.1))
    }

    @Test
    fun `exactly fifty percent remaining is medium, not healthy`() {
        assertEquals(Severity.MEDIUM, Severity.fromRemainingPercent(Severity.HEALTHY_ABOVE))
    }

    @Test
    fun `between twenty and fifty percent remaining is medium`() {
        assertEquals(Severity.MEDIUM, Severity.fromRemainingPercent(49.9))
        assertEquals(Severity.MEDIUM, Severity.fromRemainingPercent(35.0))
        assertEquals(Severity.MEDIUM, Severity.fromRemainingPercent(20.1))
    }

    @Test
    fun `exactly twenty percent remaining is low, not medium`() {
        assertEquals(Severity.LOW, Severity.fromRemainingPercent(Severity.MEDIUM_ABOVE))
    }

    @Test
    fun `between zero and twenty percent remaining is low`() {
        assertEquals(Severity.LOW, Severity.fromRemainingPercent(19.9))
        assertEquals(Severity.LOW, Severity.fromRemainingPercent(5.0))
        assertEquals(Severity.LOW, Severity.fromRemainingPercent(0.1))
    }

    @Test
    fun `zero or less remaining is exhausted`() {
        assertEquals(Severity.EXHAUSTED, Severity.fromRemainingPercent(0.0))
        // A provider that overshoots its own quota still reads as spent, not as an error.
        assertEquals(Severity.EXHAUSTED, Severity.fromRemainingPercent(-5.0))
    }

    @Test
    fun `an unknown remaining percentage is an error`() {
        assertEquals(Severity.ERROR, Severity.fromRemainingPercent(null))
    }

    @Test
    fun `the exhausted flag overrides a healthy percentage`() {
        // Providers sometimes report a comfortable percentage next to a hard "limit reached";
        // the flag is the authoritative one.
        assertEquals(Severity.EXHAUSTED, Severity.fromRemainingPercent(82.0, exhausted = true))
        assertEquals(Severity.EXHAUSTED, Severity.fromRemainingPercent(null, exhausted = true))
    }

    @Test
    fun `the enum runs best to worst so maxOf picks the worse state`() {
        assertEquals(
            listOf(
                Severity.HEALTHY,
                Severity.MEDIUM,
                Severity.LOW,
                Severity.EXHAUSTED,
                Severity.STALE,
                Severity.ERROR,
            ),
            Severity.values().toList(),
        )

        assertEquals(Severity.MEDIUM, maxOf(Severity.HEALTHY, Severity.MEDIUM))
        assertEquals(Severity.LOW, maxOf(Severity.LOW, Severity.HEALTHY))
        assertEquals(Severity.EXHAUSTED, maxOf(Severity.EXHAUSTED, Severity.MEDIUM))
        assertEquals(Severity.STALE, maxOf(Severity.EXHAUSTED, Severity.STALE))
        assertEquals(Severity.ERROR, maxOf(Severity.STALE, Severity.ERROR))

        // The account roll-up is a max over every window, so one spent window wins.
        val account = listOf(Severity.HEALTHY, Severity.EXHAUSTED, Severity.MEDIUM)
        assertEquals(Severity.EXHAUSTED, account.maxOrNull())
    }

    @Test
    fun `stale outranks every measured band but not an error`() {
        assertEquals(Severity.STALE, maxOf(Severity.STALE, Severity.LOW))
        assertEquals(Severity.ERROR, maxOf(Severity.ERROR, Severity.STALE))
    }
}
