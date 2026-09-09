package com.usagelimits.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Every case states its own "now" so the assertions are exact. The formatter deliberately drops
 * resolution as the distance grows, which keeps a countdown narrow enough for a widget row.
 */
class CountdownTest {

    private val now = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli()

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // region format

    @Test
    fun `beyond a day it reads days and hours`() {
        assertEquals("5d 2h", Countdown.format(5 * day + 2 * hour))
        assertEquals("1d 1h", Countdown.format(day + hour))
    }

    @Test
    fun `whole days drop the hours`() {
        assertEquals("3d", Countdown.format(3 * day))
    }

    @Test
    fun `past a day the minutes are dropped entirely`() {
        // 3d 0h 45m: at this distance the minutes are noise, so they are not shown.
        assertEquals("3d", Countdown.format(3 * day + 45 * minute))
    }

    @Test
    fun `below a day it reads hours and minutes`() {
        assertEquals("1h 16m", Countdown.format(hour + 16 * minute))
        assertEquals("23h 59m", Countdown.format(23 * hour + 59 * minute))
    }

    @Test
    fun `whole hours drop the minutes`() {
        assertEquals("2h", Countdown.format(2 * hour))
    }

    @Test
    fun `below an hour it reads minutes`() {
        assertEquals("45m", Countdown.format(45 * minute))
        assertEquals("1m", Countdown.format(minute))
    }

    @Test
    fun `below a minute it reads less than a minute`() {
        assertEquals("<1m", Countdown.format(59_999L))
        assertEquals("<1m", Countdown.format(30_000L))
        assertEquals("<1m", Countdown.format(1L))
    }

    @Test
    fun `zero and negative distances read as now`() {
        assertEquals("now", Countdown.format(0L))
        assertEquals("now", Countdown.format(-1L))
        assertEquals("now", Countdown.format(-5 * day))
    }

    // endregion

    // region resetLabel

    @Test
    fun `a provider that reports no reset instant gets no label`() {
        assertNull(Countdown.resetLabel(null, now))
    }

    @Test
    fun `a future reset counts down`() {
        assertEquals("Reset in 1h 16m", Countdown.resetLabel(now + hour + 16 * minute, now))
        assertEquals("Reset in 5d 2h", Countdown.resetLabel(now + 5 * day + 2 * hour, now))
    }

    @Test
    fun `a reset that has arrived reads as due`() {
        assertEquals("Reset due", Countdown.resetLabel(now - 1L, now))
        assertEquals("Reset due", Countdown.resetLabel(now - 3 * hour, now))
        // Exactly at the instant the window has rolled over, so it is due rather than "now".
        assertEquals("Reset due", Countdown.resetLabel(now, now))
    }

    // endregion

    // region freshnessLabel

    @Test
    fun `an account that has never synced says so`() {
        assertEquals("Never updated", Countdown.freshnessLabel(null, now))
    }

    @Test
    fun `a sync under a minute old is just now`() {
        assertEquals("Updated just now", Countdown.freshnessLabel(now, now))
        assertEquals("Updated just now", Countdown.freshnessLabel(now - 30_000L, now))
        assertEquals("Updated just now", Countdown.freshnessLabel(now - 59_999L, now))
    }

    @Test
    fun `an older sync is aged`() {
        assertEquals("Updated 1m ago", Countdown.freshnessLabel(now - minute, now))
        assertEquals("Updated 37m ago", Countdown.freshnessLabel(now - 37 * minute, now))
        assertEquals("Updated 2h 5m ago", Countdown.freshnessLabel(now - 2 * hour - 5 * minute, now))
        assertEquals("Updated 3d ago", Countdown.freshnessLabel(now - 3 * day, now))
    }

    // endregion

    // region absolute labels, for surfaces that cannot tick

    /** Fixed so the assertions do not depend on where the test runs. */
    private val utc = ZoneId.of("UTC")
    private val uk = Locale.UK

    @Test
    fun `a reset later today is a bare wall-clock time`() {
        assertEquals(
            "Resets 14:05",
            Countdown.absoluteResetLabel(
                Instant.parse("2026-09-09T14:05:00Z").toEpochMilli(), now, utc, uk,
            ),
        )
    }

    @Test
    fun `a reset within the week carries its weekday`() {
        // A bare clock time cannot say whether a weekly limit returns this evening or on
        // Tuesday, and that is the difference between waiting and finding another account.
        assertEquals(
            "Resets Mon 09:00",
            Countdown.absoluteResetLabel(
                Instant.parse("2026-09-14T09:00:00Z").toEpochMilli(), now, utc, uk,
            ),
        )
    }

    @Test
    fun `a reset beyond a week carries its date`() {
        // Past seven days a weekday stops being unambiguous — more than one Tuesday has passed.
        assertEquals(
            "Resets 9 Oct",
            Countdown.absoluteResetLabel(
                Instant.parse("2026-10-09T09:00:00Z").toEpochMilli(), now, utc, uk,
            ),
        )
    }

    @Test
    fun `a passed reset reads as due rather than as a past time`() {
        assertEquals(
            "Reset due",
            Countdown.absoluteResetLabel(now - minute, now, utc, uk),
        )
        assertNull(Countdown.absoluteResetLabel(null, now, utc, uk))
    }

    @Test
    fun `an absolute label does not age while a relative one does`() {
        // The defect this exists for. A widget composes once and is then left on the home
        // screen: `format` produces a number that was true at composition and silently becomes
        // wrong, while the absolute instant it names stays correct however old the render is.
        val resetAt = Instant.parse("2026-09-09T12:20:00Z").toEpochMilli()
        val laterOn = now + 25 * minute

        // Composed now, the relative form says twenty minutes. Twenty-five minutes later the
        // limit has already reset, and that frozen text is telling the user to keep waiting.
        assertEquals("20m", Countdown.format(resetAt - now))
        assertTrue(resetAt < laterOn)

        // The absolute form composed at either moment names the same instant.
        assertEquals(
            "Resets 12:20",
            Countdown.absoluteResetLabel(resetAt, now, utc, uk),
        )
        assertNotEquals(
            Countdown.format(resetAt - now),
            Countdown.format(resetAt - laterOn),
        )
    }

    @Test
    fun `the as-of stamp names a wall-clock time rather than an age`() {
        assertEquals("As of 11:23", Countdown.asOfLabel(
            Instant.parse("2026-09-09T11:23:00Z").toEpochMilli(), utc, uk,
        ))
        assertEquals("Never updated", Countdown.asOfLabel(null, utc, uk))
    }

    @Test
    fun `the as-of stamp is the same however long the widget has been on screen`() {
        // `freshnessLabel` is a function of now; `asOfLabel` is not, and that is the point.
        val fetchedAt = Instant.parse("2026-09-09T11:23:00Z").toEpochMilli()

        assertEquals(
            Countdown.asOfLabel(fetchedAt, utc, uk),
            Countdown.asOfLabel(fetchedAt, utc, uk),
        )
        assertNotEquals(
            Countdown.freshnessLabel(fetchedAt, now),
            Countdown.freshnessLabel(fetchedAt, now + 3 * hour),
        )
    }

    // endregion
}
