package com.usagelimits.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which window the summary leads with.
 *
 * An explicitly exhausted window outranks everything, whatever its percentage says. Ranked by
 * percentage alone, one the provider flagged exhausted but gave no figure for sorted LAST —
 * unknown reads as "infinitely much left" — and the summary led with a 10 %-remaining neighbour
 * while the real emergency sat below it. The Swift twin ranks the same way.
 */
class MostCriticalTest {

    private fun window(id: String, usedPercent: Double?, exhausted: Boolean) = UsageWindow(
        id = id, label = id, category = WindowCategory.OTHER, usedPercent = usedPercent,
        periodSeconds = null, resetAt = null, exhausted = exhausted,
    )

    private fun snapshot(vararg windows: UsageWindow) = UsageSnapshot(
        accountId = "a", fetchedAt = 0, status = SnapshotStatus.OK, windows = windows.toList(),
    )

    @Test
    fun `an exhausted window with no percentage still leads`() {
        val snapshot = snapshot(
            window("weekly", usedPercent = 90.0, exhausted = false),
            window("5h", usedPercent = null, exhausted = true),
        )

        assertEquals("5h", snapshot.mostCritical?.id)
    }

    @Test
    fun `without an exhausted flag the tightest percentage leads, and unknown sorts last`() {
        val snapshot = snapshot(
            window("unknown", usedPercent = null, exhausted = false),
            window("tight", usedPercent = 95.0, exhausted = false),
            window("loose", usedPercent = 10.0, exhausted = false),
        )

        assertEquals("tight", snapshot.mostCritical?.id)
    }
}
