package com.usagelimits.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The four providers between them send ISO-8601 with an offset, ISO-8601 without one, epoch
 * seconds, epoch millis and relative offsets — sometimes for the same field on different
 * endpoints. Each shape is pinned here against the same fixed instant so a regression in one
 * branch cannot hide behind another.
 */
class InstantsTest {

    private val reference = Instant.parse("2026-09-09T17:30:00Z")
    private val referenceMillis = reference.toEpochMilli()
    private val now = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli()

    // region absolute strings

    @Test
    fun `ISO-8601 with a Z offset parses`() {
        assertEquals(referenceMillis, Instants.parse("2026-09-09T17:30:00Z"))
    }

    @Test
    fun `ISO-8601 with a numeric offset is normalised to UTC`() {
        assertEquals(referenceMillis, Instants.parse("2026-09-09T19:30:00+02:00"))
        assertEquals(referenceMillis, Instants.parse("2026-09-09T13:30:00-04:00"))
    }

    @Test
    fun `a bare local date-time with no offset is read as UTC`() {
        // Providers that drop the offset always mean UTC; assuming the device zone would shift
        // a reset by hours.
        assertEquals(referenceMillis, Instants.parse("2026-09-09T17:30:00"))
    }

    @Test
    fun `sub-millisecond precision is truncated rather than rejected`() {
        val withMillis = Instant.parse("2026-09-09T17:30:00.123Z").toEpochMilli()
        assertEquals(withMillis, Instants.parse("2026-09-09T17:30:00.123456789Z"))
        assertEquals(withMillis, Instants.parse("2026-09-09T17:30:00.1234Z"))
        // Fewer than three fractional digits are already acceptable and pass through untouched.
        assertEquals(
            Instant.parse("2026-09-09T17:30:00.120Z").toEpochMilli(),
            Instants.parse("2026-09-09T17:30:00.12Z"),
        )
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(referenceMillis, Instants.parse("  2026-09-09T17:30:00Z  "))
    }

    // endregion

    // region numeric strings

    @Test
    fun `epoch seconds are scaled to millis`() {
        assertEquals(referenceMillis, Instants.parse(reference.epochSecond.toString()))
    }

    @Test
    fun `epoch millis are left alone`() {
        assertEquals(referenceMillis, Instants.parse(referenceMillis.toString()))
    }

    @Test
    fun `a fractional epoch value truncates to whole seconds`() {
        assertEquals(referenceMillis, Instants.parse("${reference.epochSecond}.5"))
    }

    @Test
    fun `the seconds-versus-millis boundary is ten billion`() {
        // Below the bound the number is read as seconds, at or above it as millis. Ten billion
        // seconds is roughly the year 2286, so no real timestamp is ambiguous.
        assertEquals(9_999_999_999_000L, Instants.fromEpochNumber(9_999_999_999L))
        assertEquals(10_000_000_000L, Instants.fromEpochNumber(10_000_000_000L))
    }

    @Test
    fun `a non-positive epoch number is unusable`() {
        assertNull(Instants.fromEpochNumber(0L))
        assertNull(Instants.fromEpochNumber(-1L))
        assertNull(Instants.parse("0"))
        assertNull(Instants.parse("-1"))
    }

    // endregion

    // region unusable input

    @Test
    fun `null, empty and blank values yield null`() {
        assertNull(Instants.parse(null))
        assertNull(Instants.parse(""))
        assertNull(Instants.parse("   "))
    }

    @Test
    fun `garbage yields null rather than throwing`() {
        assertNull(Instants.parse("not a timestamp"))
        assertNull(Instants.parse("2026-13-45T99:99:99Z"))
        assertNull(Instants.parse("tomorrow"))
    }

    // endregion

    // region relative offsets

    @Test
    fun `a positive offset is added to the supplied clock`() {
        assertEquals(now + 60_000L, Instants.fromOffsetSeconds(60L, now))
        assertEquals(now + 18_000_000L, Instants.fromOffsetSeconds(18_000L, now))
    }

    @Test
    fun `a zero offset is the supplied clock itself`() {
        assertEquals(now, Instants.fromOffsetSeconds(0L, now))
    }

    @Test
    fun `an absent or negative offset yields null`() {
        assertNull(Instants.fromOffsetSeconds(null, now))
        assertNull(Instants.fromOffsetSeconds(-1L, now))
    }

    // endregion
}
