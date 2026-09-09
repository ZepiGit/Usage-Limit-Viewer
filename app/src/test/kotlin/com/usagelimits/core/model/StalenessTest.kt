package com.usagelimits.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Age has to be part of the severity verdict.
 *
 * Without it, a snapshot that stopped refreshing keeps whatever pill it had when it last
 * succeeded, so day-old numbers still read "Healthy" — the exact failure this app exists to
 * prevent.
 */
class StalenessTest {

    private val fetchedAt = 1_757_000_000_000L

    private fun snapshot(usedPercent: Double) = UsageSnapshot(
        accountId = "acct",
        fetchedAt = fetchedAt,
        status = SnapshotStatus.OK,
        windows = listOf(
            UsageWindow(
                id = "w",
                label = "5h limit",
                category = WindowCategory.FIVE_HOUR,
                usedPercent = usedPercent,
                periodSeconds = 18_000,
                resetAt = null,
                exhausted = false,
            ),
        ),
    )

    @Test
    fun `fresh healthy snapshot stays healthy`() {
        val snapshot = snapshot(usedPercent = 10.0)
        assertEquals(Severity.HEALTHY, snapshot.severityAt(fetchedAt + 60_000))
        assertFalse(snapshot.isStaleAt(fetchedAt + 60_000))
    }

    @Test
    fun `healthy snapshot becomes stale once past the threshold`() {
        val snapshot = snapshot(usedPercent = 10.0)
        val past = fetchedAt + Severity.STALE_AFTER_MS
        assertEquals(Severity.STALE, snapshot.severityAt(past))
        assertTrue(snapshot.isStaleAt(past))
    }

    @Test
    fun `a day old snapshot never reports healthy`() {
        val snapshot = snapshot(usedPercent = 5.0)
        val dayLater = fetchedAt + 24L * 60 * 60 * 1000
        assertEquals(Severity.STALE, snapshot.severityAt(dayLater))
    }

    @Test
    fun `error outranks staleness`() {
        // A failed refresh is more actionable than "possibly old", so it is not masked.
        val failed = UsageSnapshot(
            accountId = "acct",
            fetchedAt = fetchedAt,
            status = SnapshotStatus.FAILED,
            windows = emptyList(),
            errorMessage = "boom",
        )
        assertEquals(Severity.ERROR, failed.severityAt(fetchedAt + 10L * 24 * 60 * 60 * 1000))
    }

    @Test
    fun `provider reported credit count wins over the row count`() {
        // The list can be truncated or filtered while the count stays exact; gating the redeem
        // button on the rows would hide it from someone who actually holds credits.
        val snapshot = UsageSnapshot(
            accountId = "acct",
            fetchedAt = fetchedAt,
            status = SnapshotStatus.OK,
            windows = emptyList(),
            resetCredits = emptyList(),
            resetCreditCount = 2,
        )
        assertEquals(2, snapshot.spendableResetCredits)
    }

    @Test
    fun `credit count falls back to the row count when absent`() {
        val snapshot = UsageSnapshot(
            accountId = "acct",
            fetchedAt = fetchedAt,
            status = SnapshotStatus.OK,
            windows = emptyList(),
            resetCredits = listOf(ResetCredit("a", null, null, "available")),
            resetCreditCount = null,
        )
        assertEquals(1, snapshot.spendableResetCredits)
    }

    @Test
    fun `credits that do not apply to the current limit are held but not spendable`() {
        // Production reports available_count and applicable_available_count side by side. A
        // user holding two credits that apply to nothing must still be told they hold two —
        // and must not be offered a button the provider would refuse.
        val snapshot = UsageSnapshot(
            accountId = "acct",
            fetchedAt = fetchedAt,
            status = SnapshotStatus.OK,
            windows = emptyList(),
            resetCredits = emptyList(),
            resetCreditCount = 2,
            applicableResetCreditCount = 0,
        )
        assertEquals(2, snapshot.heldResetCredits)
        assertEquals(0, snapshot.spendableResetCredits)
    }

    @Test
    fun `an unstated applicable count leaves the held count spendable`() {
        // Three of the four providers never report the distinction, so absent must mean
        // "no distinction drawn", not "nothing can be spent".
        val snapshot = UsageSnapshot(
            accountId = "acct",
            fetchedAt = fetchedAt,
            status = SnapshotStatus.OK,
            windows = emptyList(),
            resetCredits = emptyList(),
            resetCreditCount = 2,
            applicableResetCreditCount = null,
        )
        assertEquals(2, snapshot.spendableResetCredits)
    }
}
