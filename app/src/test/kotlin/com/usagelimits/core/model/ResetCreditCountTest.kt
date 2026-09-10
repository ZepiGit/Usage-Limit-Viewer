package com.usagelimits.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What counts as a reset credit you have, and what counts as one you can spend.
 *
 * Two different questions, and the app answers both: a balance line states what is held, a
 * redeem control is gated on what applies. Conflating them either hides credits a user owns or
 * offers a spend the provider will refuse.
 */
class ResetCreditCountTest {

    private fun snapshot(
        rows: List<ResetCredit> = emptyList(),
        count: Int? = null,
        applicable: Int? = null,
    ) = UsageSnapshot(
        accountId = "a",
        fetchedAt = 0L,
        status = SnapshotStatus.OK,
        windows = emptyList(),
        resetCredits = rows,
        resetCreditCount = count,
        applicableResetCreditCount = applicable,
    )

    @Test
    fun `a count without rows still counts`() {
        // The shape Codex actually serves in its usage payload: a count and no rows at all.
        val stored = snapshot(count = 2, applicable = 2)

        assertEquals(2, stored.heldResetCredits)
        assertEquals(2, stored.spendableResetCredits)
    }

    @Test
    fun `a spent credit is not one you hold`() {
        // A consumed credit is still listed. Counting it says the user has one to spend.
        val stored = snapshot(
            rows = listOf(ResetCredit(id = "c1", grantedAt = null, expiresAt = null, status = "consumed")),
        )

        assertEquals(0, stored.heldResetCredits)
    }

    @Test
    fun `holding credits is not the same as being able to spend one`() {
        val stored = snapshot(count = 2, applicable = 0)

        assertEquals(2, stored.heldResetCredits)
        assertEquals(0, stored.spendableResetCredits)
    }

    @Test
    fun `the provider's own count outranks the rows it sent`() {
        // The list can be truncated or filtered while the count stays exact.
        val stored = snapshot(
            rows = listOf(ResetCredit(id = "c1", grantedAt = null, expiresAt = null, status = "available")),
            count = 5,
        )

        assertEquals(5, stored.heldResetCredits)
    }
}
