package com.usagelimits.widget

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WidgetScope
import com.usagelimits.core.model.WindowCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scope selection is what makes per-widget configuration meaningful.
 *
 * Before a configuration screen existed, ACCOUNT and PROVIDER were unreachable, so two widgets
 * watching different accounts rendered identically. These pin the selection behaviour.
 */
class WidgetDataBuilderTest {

    private val now = 1_757_000_000_000L

    private fun account(id: String, provider: ProviderId) = ProviderAccount(
        localId = id,
        provider = provider,
        externalAccountId = "ext-$id",
        email = "$id@example.com",
        displayName = null,
        plan = "Plus",
        credentialReference = "ref-$id",
        createdAt = now,
        lastSuccessfulSync = now,
    )

    private fun usage(
        id: String,
        provider: ProviderId,
        usedPercent: Double,
        fetchedAt: Long = now,
    ) = AccountUsage(
        account = account(id, provider),
        snapshot = UsageSnapshot(
            accountId = id,
            fetchedAt = fetchedAt,
            status = SnapshotStatus.OK,
            windows = listOf(
                UsageWindow(
                    id = "5h",
                    label = "5h limit",
                    category = WindowCategory.FIVE_HOUR,
                    usedPercent = usedPercent,
                    periodSeconds = 18_000,
                    resetAt = now + 3_600_000,
                    exhausted = false,
                ),
                UsageWindow(
                    id = "wk",
                    label = "Weekly",
                    category = WindowCategory.WEEKLY,
                    usedPercent = usedPercent,
                    periodSeconds = 604_800,
                    resetAt = now + 86_400_000,
                    exhausted = false,
                ),
            ),
        ),
    )

    private val all = listOf(
        usage("a", ProviderId.CODEX, usedPercent = 10.0),
        usage("b", ProviderId.CODEX, usedPercent = 90.0),
        usage("c", ProviderId.CLAUDE, usedPercent = 50.0),
    )

    @Test
    fun `account scope selects exactly one account`() {
        val snapshot = WidgetDataBuilder.build(all, now, WidgetScope.ACCOUNT, "b", null)
        assertEquals(1, snapshot.accountCount)
        assertEquals("b", snapshot.accounts.single().accountId)
    }

    @Test
    fun `provider scope selects every account of that provider`() {
        val snapshot = WidgetDataBuilder.build(all, now, WidgetScope.PROVIDER, null, "codex")
        assertEquals(2, snapshot.accountCount)
        assertTrue(snapshot.accounts.map { it.accountId }.containsAll(listOf("a", "b")))
    }

    @Test
    fun `most critical scope leads with the worst account`() {
        val snapshot = WidgetDataBuilder.build(all, now, WidgetScope.MOST_CRITICAL, null, null)
        // "b" is at 10% remaining — the one worth showing first.
        assertEquals("b", snapshot.accounts.first().accountId)
    }

    @Test
    fun `all accounts scope keeps every account`() {
        val snapshot = WidgetDataBuilder.build(all, now, WidgetScope.ALL_ACCOUNTS, null, null)
        assertEquals(3, snapshot.accountCount)
    }

    @Test
    fun `headline picks the tightest window of each horizon`() {
        val snapshot = WidgetDataBuilder.build(all, now, WidgetScope.ALL_ACCOUNTS, null, null)
        assertEquals(10.0, snapshot.headlineShort!!.remainingPercent!!, 0.001)
        assertEquals(10.0, snapshot.headlineLong!!.remainingPercent!!, 0.001)
    }

    @Test
    fun `an unmatched account id yields the empty snapshot`() {
        val snapshot = WidgetDataBuilder.build(all, now, WidgetScope.ACCOUNT, "missing", null)
        assertEquals(0, snapshot.accountCount)
    }

    @Test
    fun `an old snapshot reports as stale rather than healthy`() {
        val old = listOf(
            usage(
                id = "a",
                provider = ProviderId.CODEX,
                usedPercent = 1.0,
                fetchedAt = now - 2 * Severity.STALE_AFTER_MS,
            ),
        )
        val snapshot = WidgetDataBuilder.build(old, now, WidgetScope.ALL_ACCOUNTS, null, null)
        assertEquals(Severity.STALE, snapshot.overallSeverity)
    }
}
