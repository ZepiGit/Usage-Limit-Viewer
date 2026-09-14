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
        usedPercent: Double?,
        fetchedAt: Long = now,
        status: SnapshotStatus = SnapshotStatus.OK,
    ) = AccountUsage(
        account = account(id, provider),
        snapshot = UsageSnapshot(
            accountId = id,
            fetchedAt = fetchedAt,
            status = status,
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
    fun `most critical scope leads with an exhausted account, not a stale or failed one`() {
        val mixed = listOf(
            // 80% left, but too old to trust — STALE.
            usage(
                id = "stale",
                provider = ProviderId.CODEX,
                usedPercent = 20.0,
                fetchedAt = now - 2 * Severity.STALE_AFTER_MS,
            ),
            // 95% left, but the last fetch failed — ERROR.
            usage("error", ProviderId.CLAUDE, usedPercent = 5.0, status = SnapshotStatus.FAILED),
            usage("out", ProviderId.XAI, usedPercent = 100.0),
        )

        val snapshot = WidgetDataBuilder.build(mixed, now, WidgetScope.MOST_CRITICAL, null, null)

        // Ranking by Severity.ordinal descending put ERROR, then STALE, then EXHAUSTED — so
        // this used to lead with "error", an account whose quota may be perfectly fine.
        assertEquals("out", snapshot.accounts.first().accountId)
    }

    @Test
    fun `accounts with no numbers do not displace accounts with numbers`() {
        // A two-tile widget has two slots. A never-fetched or failed account has no rows to
        // show, so ranking it above a real one filled the tile with blank cards and pushed the
        // account at 3% off the screen entirely.
        val mixed = listOf(
            usage("never-fetched", ProviderId.CODEX, usedPercent = null),
            usage("failed", ProviderId.CLAUDE, usedPercent = null, status = SnapshotStatus.FAILED),
            usage("tight", ProviderId.XAI, usedPercent = 97.0),
        )

        val snapshot = WidgetDataBuilder.build(mixed, now, WidgetScope.MOST_CRITICAL, null, null)

        assertEquals("tight", snapshot.accounts.first().accountId)
        // The unreadable accounts still reach the user, through the one signal that is not
        // slot-limited.
        assertEquals(Severity.ERROR, snapshot.overallSeverity)
    }

    @Test
    fun `most critical scope orders one severity band by remaining ascending`() {
        // Both sit at 45% and 30% remaining: MEDIUM either way, so only the number separates
        // them. The old comparator saw equal keys and left them in cache order.
        val band = listOf(
            usage("roomy", ProviderId.CODEX, usedPercent = 55.0),
            usage("tight", ProviderId.CLAUDE, usedPercent = 70.0),
        )

        val snapshot = WidgetDataBuilder.build(band, now, WidgetScope.MOST_CRITICAL, null, null)

        assertEquals(listOf("tight", "roomy"), snapshot.accounts.map { it.accountId })
    }

    @Test
    fun `most critical scope walks the whole severity ramp`() {
        val ramp = listOf(
            usage("healthy", ProviderId.CODEX, usedPercent = 10.0),
            usage("medium", ProviderId.CLAUDE, usedPercent = 60.0),
            usage("low", ProviderId.ANTIGRAVITY, usedPercent = 90.0),
            usage("exhausted", ProviderId.XAI, usedPercent = 100.0),
            usage("error", ProviderId.CODEX, usedPercent = 5.0, status = SnapshotStatus.FAILED),
            usage(
                id = "stale",
                provider = ProviderId.CLAUDE,
                usedPercent = 30.0,
                fetchedAt = now - 2 * Severity.STALE_AFTER_MS,
            ),
        )

        val snapshot = WidgetDataBuilder.build(ramp, now, WidgetScope.MOST_CRITICAL, null, null)

        assertEquals(
            listOf("exhausted", "low", "medium", "healthy", "error", "stale"),
            snapshot.accounts.map { it.accountId },
        )
    }

    @Test
    fun `all accounts scope keeps every account`() {
        val snapshot = WidgetDataBuilder.build(all, now, WidgetScope.ALL_ACCOUNTS, null, null)
        assertEquals(3, snapshot.accountCount)
    }

    @Test
    fun `the headline belongs to the leading account`() {
        // Not a pooled minimum. On a tile showing two of six accounts, a pooled figure could be
        // the sixth account's window — a number the reader cannot locate anywhere on screen.
        val snapshot = WidgetDataBuilder.build(all, now, WidgetScope.MOST_CRITICAL, null, null)

        assertEquals("b", snapshot.accounts.first().accountId)
        assertEquals(10.0, snapshot.headlineShort!!.remainingPercent!!, 0.001)
        assertEquals(10.0, snapshot.headlineLong!!.remainingPercent!!, 0.001)
        // And it is the same row the leading card itself shows.
        assertEquals(
            snapshot.accounts.first().rows.first { it.category == WindowCategory.FIVE_HOUR },
            snapshot.headlineShort,
        )
    }

    @Test
    fun `one account's weekly window does not suppress another's monthly one`() {
        // Applying `weekly ?: monthly` to the pool meant any single weekly window hid every
        // monthly one, so the headline could read 80% while a visible row read 3%.
        val monthlyOnly = AccountUsage(
            account = account("monthly", ProviderId.XAI),
            snapshot = UsageSnapshot(
                accountId = "monthly",
                fetchedAt = now,
                status = SnapshotStatus.OK,
                windows = listOf(
                    UsageWindow(
                        id = "mo",
                        label = "Monthly",
                        category = WindowCategory.MONTHLY,
                        usedPercent = 97.0,
                        periodSeconds = 2_592_000,
                        resetAt = null,
                        exhausted = false,
                    ),
                ),
            ),
        )
        val weeklyOnly = usage("weekly", ProviderId.CLAUDE, usedPercent = 20.0)

        val snapshot = WidgetDataBuilder.build(
            listOf(weeklyOnly, monthlyOnly), now, WidgetScope.MOST_CRITICAL, null, null,
        )

        assertEquals("monthly", snapshot.accounts.first().accountId)
        assertEquals(WindowCategory.MONTHLY, snapshot.headlineLong!!.category)
        assertEquals(3.0, snapshot.headlineLong!!.remainingPercent!!, 0.001)
    }

    @Test
    fun `the next reset belongs to the account the headline describes`() {
        // Taken across every account this paired the headline state with an unrelated clock:
        // "0% left, resets in 12m", where the twelve minutes belonged to a healthy account's
        // five-hour window. Healthy five-hour windows reset constantly, so it was the common
        // case rather than an edge.
        val spentSoon = AccountUsage(
            account = account("spent", ProviderId.CODEX),
            snapshot = UsageSnapshot(
                accountId = "spent",
                fetchedAt = now,
                status = SnapshotStatus.OK,
                windows = listOf(
                    UsageWindow(
                        id = "mo",
                        label = "Monthly",
                        category = WindowCategory.MONTHLY,
                        usedPercent = 100.0,
                        periodSeconds = 2_592_000,
                        resetAt = now + 20 * 86_400_000L,
                        exhausted = true,
                    ),
                ),
            ),
        )
        val healthySoon = AccountUsage(
            account = account("healthy", ProviderId.CLAUDE),
            snapshot = UsageSnapshot(
                accountId = "healthy",
                fetchedAt = now,
                status = SnapshotStatus.OK,
                windows = listOf(
                    UsageWindow(
                        id = "5h",
                        label = "5h limit",
                        category = WindowCategory.FIVE_HOUR,
                        usedPercent = 5.0,
                        periodSeconds = 18_000,
                        resetAt = now + 12 * 60_000L,
                        exhausted = false,
                    ),
                ),
            ),
        )

        val snapshot = WidgetDataBuilder.build(
            listOf(healthySoon, spentSoon), now, WidgetScope.MOST_CRITICAL, null, null,
        )

        assertEquals("spent", snapshot.accounts.first().accountId)
        assertEquals(now + 20 * 86_400_000L, snapshot.nextResetAt)
    }

    @Test
    fun `sub-point drift does not reorder the tiles`() {
        // Two healthy accounts drifting 47.2 to 46.8 and 46.9 to 47.1 swapped on every refresh.
        // On a home screen that means the reader re-scans from scratch each time.
        val before = listOf(
            usage("a", ProviderId.CODEX, usedPercent = 52.8),
            usage("b", ProviderId.CLAUDE, usedPercent = 53.1),
        )
        val after = listOf(
            usage("a", ProviderId.CODEX, usedPercent = 53.2),
            usage("b", ProviderId.CLAUDE, usedPercent = 52.9),
        )

        assertEquals(
            WidgetDataBuilder.build(before, now, WidgetScope.MOST_CRITICAL, null, null)
                .accounts.map { it.accountId },
            WidgetDataBuilder.build(after, now, WidgetScope.MOST_CRITICAL, null, null)
                .accounts.map { it.accountId },
        )
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

    // region the long-horizon row

    private fun longWindow(
        id: String,
        label: String,
        category: WindowCategory,
        usedPercent: Double?,
    ) = UsageWindow(
        id = id,
        label = label,
        category = category,
        usedPercent = usedPercent,
        periodSeconds = if (category == WindowCategory.WEEKLY) 604_800 else 2_592_000,
        resetAt = now + 86_400_000,
        exhausted = usedPercent == 100.0,
    )

    private fun withWindows(vararg windows: UsageWindow) = AccountUsage(
        account = account("a", ProviderId.CODEX),
        snapshot = UsageSnapshot(
            accountId = "a",
            fetchedAt = now,
            status = SnapshotStatus.OK,
            windows = windows.toList(),
        ),
    )

    @Test
    fun `an exhausted monthly window is shown even when a healthy weekly one exists`() {
        // The defect: the long slot was `WEEKLY ?: MONTHLY ?: OTHER`, and the elvis
        // short-circuits on the mere existence of a weekly window. An account whose monthly
        // quota had run out showed its healthy weekly bar instead, and the exhausted limit
        // appeared nowhere on the home screen.
        val data = WidgetDataBuilder.build(
            listOf(
                withWindows(
                    longWindow("wk", "Weekly", WindowCategory.WEEKLY, usedPercent = 5.0),
                    longWindow("mo", "Monthly", WindowCategory.MONTHLY, usedPercent = 100.0),
                ),
            ),
            now,
            WidgetScope.ALL_ACCOUNTS,
            null,
            null,
        )

        val labels = data.accounts.single().rows.map { it.label }
        assertTrue("expected the exhausted monthly row, got $labels", labels.contains("Monthly"))
    }

    @Test
    fun `the weekly window still wins when nothing is more urgent`() {
        // The other direction, so the fix is a change of ranking and not of preference: with
        // both windows equally healthy, the better-understood category keeps the slot.
        val data = WidgetDataBuilder.build(
            listOf(
                withWindows(
                    longWindow("wk", "Weekly", WindowCategory.WEEKLY, usedPercent = 5.0),
                    longWindow("mo", "Monthly", WindowCategory.MONTHLY, usedPercent = 5.0),
                ),
            ),
            now,
            WidgetScope.ALL_ACCOUNTS,
            null,
            null,
        )

        assertEquals(listOf("Weekly"), data.accounts.single().rows.map { it.label })
    }

    @Test
    fun `an unreadable window does not displace one known to be empty`() {
        // `Severity.urgency` puts ERROR below EXHAUSTED deliberately. A monthly window whose
        // percentage could not be read says nothing actionable; a weekly one at zero says the
        // user has run out. The second is the row worth the slot.
        val data = WidgetDataBuilder.build(
            listOf(
                withWindows(
                    longWindow("wk", "Weekly", WindowCategory.WEEKLY, usedPercent = 100.0),
                    longWindow("mo", "Monthly", WindowCategory.MONTHLY, usedPercent = null),
                ),
            ),
            now,
            WidgetScope.ALL_ACCOUNTS,
            null,
            null,
        )

        assertEquals(listOf("Weekly"), data.accounts.single().rows.map { it.label })
    }

    // endregion
    @Test
    fun `a reset that has already passed does not mask the next one`() {
        // A snapshot keeps a window's reset instant until the next fetch replaces it. Once one
        // had passed, the minimum was anchored in the past and the compact widget's "Next reset"
        // tile named a reset that was already over, for as long as the cache stood.
        val usage = AccountUsage(
            account = account("a", ProviderId.CODEX),
            snapshot = UsageSnapshot(
                accountId = "a", fetchedAt = now, status = SnapshotStatus.OK,
                windows = listOf(
                    UsageWindow(
                        id = "5h", label = "5h limit", category = WindowCategory.FIVE_HOUR,
                        usedPercent = 50.0, periodSeconds = 18_000, resetAt = now - 100,
                        exhausted = false,
                    ),
                    UsageWindow(
                        id = "wk", label = "Weekly", category = WindowCategory.WEEKLY,
                        usedPercent = 50.0, periodSeconds = 604_800, resetAt = now + 2_000,
                        exhausted = false,
                    ),
                ),
            ),
        )

        val snapshot = WidgetDataBuilder.build(listOf(usage), now, WidgetScope.MOST_CRITICAL, null, null)

        assertEquals(now + 2_000, snapshot.nextResetAt)
    }

}
