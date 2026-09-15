package com.usagelimits.core.sync

import androidx.work.ListenableWorker
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions the sync worker makes without touching the network.
 *
 * Whether to ask WorkManager for a retry: only a periodic pass in which every account failed.
 * A one-shot that retried sat in the backoff queue under its unique name with KEEP, and every
 * later trigger — app start, `onResume`, the widget's refresh button — was dropped for as long
 * as the backoff lasted. The user tapped refresh and nothing happened.
 *
 * Whether a clock-driven trigger should fetch at all: only when the cache has aged past the
 * user's interval, or a window has reset since its snapshot was taken. The system's widget
 * tick also fires on placement and resize, and neither should cost a provider round trip a
 * minute after the last one.
 */
class SyncWorkerPolicyTest {

    private val now = 1_800_000_000_000L
    private val interval = 30

    private fun account(id: String) = ProviderAccount(
        localId = id,
        provider = ProviderId.CODEX,
        externalAccountId = "ext-$id",
        email = "$id@example.com",
        displayName = null,
        plan = "Plus",
        credentialReference = "ref-$id",
        createdAt = now,
        lastSuccessfulSync = now,
    )

    private fun usage(id: String, fetchedAt: Long, resetAt: Long? = now + 3_600_000) = AccountUsage(
        account = account(id),
        snapshot = UsageSnapshot(
            accountId = id,
            fetchedAt = fetchedAt,
            status = SnapshotStatus.OK,
            windows = listOf(
                UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, 40.0, 18_000, resetAt, false),
            ),
        ),
    )

    @Test
    fun `a pass with any success reports success whatever else failed`() {
        val mixed = listOf(SyncOutcome("a", true), SyncOutcome("b", false, "boom"))
        assertEquals(ListenableWorker.Result.success(), SyncWorker.resultFor(mixed, periodic = true))
        assertEquals(ListenableWorker.Result.success(), SyncWorker.resultFor(mixed, periodic = false))
        assertEquals(ListenableWorker.Result.success(), SyncWorker.resultFor(emptyList(), periodic = true))
    }

    @Test
    fun `only a periodic pass in which everything failed asks for a retry`() {
        val allFailed = listOf(SyncOutcome("a", false, "x"), SyncOutcome("b", false, "y"))
        assertEquals(ListenableWorker.Result.retry(), SyncWorker.resultFor(allFailed, periodic = true))
        // A one-shot records its failures per account and gets out of the way of the next trigger.
        assertEquals(ListenableWorker.Result.failure(), SyncWorker.resultFor(allFailed, periodic = false))
    }

    @Test
    fun `a cache younger than the interval needs no fetch`() {
        val fresh = listOf(usage("a", fetchedAt = now - 5 * 60_000), usage("b", fetchedAt = now - 20 * 60_000))
        assertFalse(SyncWorker.cacheNeedsSync(fresh, interval, now))
    }

    @Test
    fun `a cache older than the interval needs a fetch`() {
        val aged = listOf(usage("a", fetchedAt = now - 31 * 60_000))
        assertTrue(SyncWorker.cacheNeedsSync(aged, interval, now))
        // Exactly at the interval counts as aged: the scheduled pass is due, not early.
        assertTrue(SyncWorker.cacheNeedsSync(listOf(usage("a", fetchedAt = now - 30 * 60_000)), interval, now))
    }

    @Test
    fun `the newest snapshot decides the age, not the oldest`() {
        // One account failing for a day must not drive a fetch every tick while the others are
        // current; its own error is already recorded and shown.
        val mixed = listOf(usage("old", fetchedAt = now - 86_400_000), usage("new", fetchedAt = now - 60_000))
        assertFalse(SyncWorker.cacheNeedsSync(mixed, interval, now))
    }

    @Test
    fun `a reset that passed since the fetch needs a fetch however fresh the snapshot`() {
        // Fetched two minutes ago, but the window reset one minute ago: the cached figure
        // describes a window the provider has already closed.
        val overtaken = listOf(usage("a", fetchedAt = now - 2 * 60_000, resetAt = now - 60_000))
        assertTrue(SyncWorker.cacheNeedsSync(overtaken, interval, now))
    }

    @Test
    fun `a reset that passed before the fetch is already reflected and needs nothing`() {
        // The provider had already reported the new window when this was fetched; a passed
        // reset instant it still carries is old news, not a reason to fetch again.
        val confirmed = listOf(usage("a", fetchedAt = now - 60_000, resetAt = now - 2 * 60_000))
        assertFalse(SyncWorker.cacheNeedsSync(confirmed, interval, now))
    }

    @Test
    fun `no accounts means nothing to fetch, and an account without a snapshot means fetch`() {
        assertFalse(SyncWorker.cacheNeedsSync(emptyList(), interval, now))
        assertTrue(SyncWorker.cacheNeedsSync(listOf(AccountUsage(account("a"), snapshot = null)), interval, now))
    }

    @Test
    fun `an interval below the platform floor is judged at the floor`() {
        // A stored 1-minute interval would otherwise make every tick a fetch.
        val recent = listOf(usage("a", fetchedAt = now - 10 * 60_000))
        assertFalse(SyncWorker.cacheNeedsSync(recent, syncIntervalMinutes = 1, nowMs = now))
    }
}
