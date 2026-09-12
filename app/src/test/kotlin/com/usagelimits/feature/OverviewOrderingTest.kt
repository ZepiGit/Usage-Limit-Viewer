package com.usagelimits.feature

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.settings.AppSettings
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
    fun `the summary card's next reset belongs to the account it leads with`() {
        // A (healthy) resets in 12 minutes; B is exhausted and resets on Tuesday. The card
        // leads with B's window, so its "Next reset" must be B's — not A's twelve minutes.
        val state = UsageUiState(
            accounts = listOf(
                usage("A", now + 12 * 60_000),
                AccountUsage(
                    account("B"),
                    UsageSnapshot(
                        accountId = "B", fetchedAt = now, status = SnapshotStatus.OK,
                        windows = listOf(
                            UsageWindow(
                                id = "B-weekly", label = "Weekly", category = WindowCategory.WEEKLY,
                                usedPercent = 100.0, periodSeconds = 604_800,
                                resetAt = now + 3 * 86_400_000, exhausted = true,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals("B", state.mostCritical?.first?.account?.localId)
        assertEquals(now + 3 * 86_400_000, state.nextResetAt(now, "B"))
        assertEquals(now + 12 * 60_000, state.nextResetAt(now))
    }

    @Test
    fun `with every reset in the past there is no next reset`() {
        val state = UsageUiState(accounts = listOf(usage("A", now - 1), usage("B", null)))

        // "—" on the card, not "now" for ever.
        assertNull(state.nextResetAt(now))
    }

    /**
     * The order the user dragged the cards into beats the urgency ranking — but only once they
     * have actually dragged one.
     *
     * Two orders live on this screen, and getting the precedence wrong is invisible in either
     * direction: re-ranking a hand-made arrangement on the next sync looks like the drag was
     * forgotten, and honouring an all-zero `sortOrder` on a fresh install looks like the
     * urgency ranking was never built.
     */
    @Test
    fun `overview uses the same stored order before and after manual arrangement`() {
        // Deliberately handed over in the WRONG urgency order, as the repository would after a
        // reorder: it returns rows by sortOrder.
        val healthy = usageWith("healthy", 10.0)
        val exhausted = usageWith("exhausted", 100.0)
        val accounts = listOf(healthy, exhausted)

        val ranked = UsageUiState(accounts = accounts, settings = AppSettings())
        assertEquals(
            "the overview and All accounts widgets share the stored order",
            listOf("healthy", "exhausted"),
            ranked.orderedAccounts(now).map { it.account.localId },
        )

        val arranged = UsageUiState(
            accounts = accounts,
            settings = AppSettings(accountsManuallyOrdered = true),
        )
        assertEquals(
            "once arranged by hand, the stored order stands and nothing re-ranks it",
            listOf("healthy", "exhausted"),
            arranged.orderedAccounts(now).map { it.account.localId },
        )
    }

    private fun usageWith(id: String, usedPercent: Double) = AccountUsage(
        account(id),
        UsageSnapshot(
            accountId = id, fetchedAt = now, status = SnapshotStatus.OK,
            windows = listOf(
                UsageWindow(
                    id = "$id-w", label = "5h limit", category = WindowCategory.FIVE_HOUR,
                    usedPercent = usedPercent, periodSeconds = 18_000,
                    resetAt = now + 3_600_000, exhausted = usedPercent >= 100.0,
                ),
            ),
        ),
    )
}
