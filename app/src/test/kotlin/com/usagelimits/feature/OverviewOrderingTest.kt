package com.usagelimits.feature

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two things the overview screen was getting wrong about what to show first.
 *
 * The list was sorted by `Severity.ordinal`, whose declaration order puts STALE and ERROR
 * after EXHAUSTED because they are worse *states* — so a never-fetched blank card and a
 * day-old stale card sat above the account the user had actually run out on, on the one
 * screen that promises "the account that needs attention is the one already on screen".
 * The widget had its own, correct ranking; now both use `Severity.urgency`.
 *
 * And "Next reset" took the minimum over every window's reset instant, past ones included.
 * A snapshot keeps an expired instant until the next fetch replaces it, so once any window
 * had rolled over the card read "now" for hours while the real next rollover never showed.
 */
class OverviewOrderingTest {

    private val now = 1_789_000_000_000L

    @Test
    fun `exhausted outranks stale and error, which come last`() {
        val order = Severity.entries.sortedBy { it.urgency }
        assertEquals(Severity.EXHAUSTED, order.first())
        assertTrue(
            "stale and error must rank after every live severity",
            order.indexOf(Severity.STALE) > order.indexOf(Severity.HEALTHY) &&
                order.indexOf(Severity.ERROR) > order.indexOf(Severity.HEALTHY),
        )
        // The property the bug violated, stated directly.
        assertTrue(Severity.EXHAUSTED.urgency < Severity.STALE.urgency)
        assertTrue(Severity.EXHAUSTED.urgency < Severity.ERROR.urgency)
    }

    private fun account(id: String) = ProviderAccount(
        localId = id,
        provider = ProviderId.CODEX,
        externalAccountId = "ext-$id",
        email = null,
        displayName = id,
        plan = null,
        credentialReference = "codex_$id",
        createdAt = now,
        lastSuccessfulSync = null,
    )

    private fun window(id: String, resetAt: Long?) = UsageWindow(
        id = id, label = id, category = WindowCategory.FIVE_HOUR,
        usedPercent = 10.0, periodSeconds = 18_000, resetAt = resetAt, exhausted = false,
    )

    private fun usage(id: String, vararg resets: Long?) = AccountUsage(
        account(id),
        UsageSnapshot(
            accountId = id, fetchedAt = now, status = SnapshotStatus.OK,
            windows = resets.mapIndexed { i, r -> window("$id-$i", r) },
        ),
    )

    @Test
    fun `next reset skips instants that have already passed`() {
        val state = UsageUiState(
            accounts = listOf(
                usage("A", now - 60_000),          // rolled over a minute ago — not "next"
                usage("B", now + 3_600_000),       // the real next one
                usage("C", now + 86_400_000),
            ),
        )

        assertEquals(now + 3_600_000, state.nextResetAt(now))
    }

    @Test
    fun `with every reset in the past there is no next reset`() {
        val state = UsageUiState(accounts = listOf(usage("A", now - 1), usage("B", null)))

        // "—" on the card, not "now" for ever.
        assertNull(state.nextResetAt(now))
    }
}
