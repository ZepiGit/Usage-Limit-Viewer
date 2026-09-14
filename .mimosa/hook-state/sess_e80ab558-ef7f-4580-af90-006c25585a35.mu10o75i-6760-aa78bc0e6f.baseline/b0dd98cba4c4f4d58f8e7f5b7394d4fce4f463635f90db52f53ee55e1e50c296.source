package com.usagelimits.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cache-only repaint exists because two presentation changes need no network and no data
 * event: a snapshot crossing its staleness threshold, and a reset instant arriving. The one
 * coalesced wake is the soonest such boundary still ahead.
 */
class WidgetPresentationWorkerTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `the next boundary is the soonest staleness or reset still ahead`() {
        assertEquals(
            now + 500,
            WidgetPresentationWorker.nextPresentationBoundary(
                fetchedAtMs = listOf(now - 1_000), // long since stale — not a boundary
                resetAtMs = listOf(now + 9_000, now + 500),
                staleAfterMs = 60_000,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `a snapshot not yet stale ages at its threshold`() {
        assertEquals(
            now + 59_000,
            WidgetPresentationWorker.nextPresentationBoundary(
                fetchedAtMs = listOf(now - 1_000),
                resetAtMs = emptyList(),
                staleAfterMs = 60_000,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `a cache with nothing ahead schedules nothing`() {
        assertNull(
            WidgetPresentationWorker.nextPresentationBoundary(
                fetchedAtMs = listOf(now - 120_000),
                resetAtMs = listOf(now - 5),
                staleAfterMs = 60_000,
                nowMs = now,
            ),
        )
    }
}
