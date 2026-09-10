package com.usagelimits.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which window the summary card leads with, and which reset it shows beside it.
 *
 * `mostCritical` is the app's headline derivation — the number a user glances at and acts on —
 * and it had no test at all. The configuration below is not invented: a live Codex account
 * observed through the gateway sat at a nearly untouched five-hour window alongside a weekly
 * one well past half spent. The values here are synthetic, the RELATIONSHIP is the one that
 * was actually observed, and it is the relationship that makes this dangerous. Lead with the
 * five-hour window and the tile reads almost-full while the real constraint is the weekly.
 *
 * Nothing about that failure looks like a failure: no crash, no error state, just a confident
 * number that is wrong by the width of the gap between two windows.
 */
class HeadlineWindowTest {

    private fun window(
        id: String,
        category: WindowCategory,
        usedPercent: Double?,
        resetAt: Long?,
        periodSeconds: Long?,
    ) = UsageWindow(
        id = id,
        label = id,
        category = category,
        usedPercent = usedPercent,
        periodSeconds = periodSeconds,
        resetAt = resetAt,
        exhausted = false,
    )

    private val now = 1_789_000_000_000L

    /** Five-hour barely touched, weekly well spent — the shape a real account was in. */
    private fun snapshot(vararg windows: UsageWindow) = UsageSnapshot(
        accountId = "A",
        fetchedAt = now,
        status = SnapshotStatus.OK,
        windows = windows.toList(),
    )

    @Test
    fun `the headline is the tightest window, not the first or the soonest`() {
        val fiveHour = window("5h", WindowCategory.FIVE_HOUR, 5.0, now + 3_600_000, 18_000)
        val weekly = window("weekly", WindowCategory.WEEKLY, 70.0, now + 400_000_000, 604_800)

        val critical = snapshot(fiveHour, weekly).mostCritical

        // 30 % left beats 95 % left. Ordering in the list must not decide this.
        assertEquals("weekly", critical?.id)
        assertEquals(snapshot(weekly, fiveHour).mostCritical?.id, critical?.id)
    }

    @Test
    fun `the soonest reset is a different window from the headline, and that is correct`() {
        val fiveHour = window("5h", WindowCategory.FIVE_HOUR, 5.0, now + 3_600_000, 18_000)
        val weekly = window("weekly", WindowCategory.WEEKLY, 70.0, now + 400_000_000, 604_800)
        val snapshot = snapshot(fiveHour, weekly)

        // The five-hour window rolls over first; the weekly is the one running out. Pairing
        // them as though they described the same limit is how a card comes to read
        // "30 % left · resets in 1h" when that hour belongs to the window at 95 %.
        assertEquals(now + 3_600_000, snapshot.nextReset)
        assertNotEquals(snapshot.nextReset, snapshot.mostCritical?.resetAt)
    }

    @Test
    fun `a window with no usable percentage never becomes the headline`() {
        val unknown = window("unknown", WindowCategory.OTHER, null, now + 60_000, null)
        val weekly = window("weekly", WindowCategory.WEEKLY, 70.0, now + 400_000_000, 604_800)

        // Leading with a window whose remaining is unknown would put a blank where the number
        // belongs, and hide the one figure that is known.
        assertEquals("weekly", snapshot(unknown, weekly).mostCritical?.id)
    }

    @Test
    fun `with no windows at all there is no headline and no reset`() {
        assertNull(snapshot().mostCritical)
        assertNull(snapshot().nextReset)
    }
}
