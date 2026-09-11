package com.usagelimits.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.database.NotificationDao
import com.usagelimits.core.database.NotificationEventEntity
import com.usagelimits.core.database.NotificationStateEntity
import com.usagelimits.core.database.TransactionRunner
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A publication that arrives after one of its accounts was disconnected.
 *
 * The publisher is handed a list read moments earlier; a user tapping "Disconnect" while a
 * background sync is mid-pass is in that list. Both notification tables carry a foreign key to
 * the account, so writing its event or state raised SQLITE_CONSTRAINT_FOREIGNKEY and aborted
 * the whole publication — the surviving accounts' alerts with it.
 *
 * And the fix only holds if EVERYTHING database-side runs inside one transaction, the state
 * read included: two publishers in the same window (the worker and a pull-to-refresh) otherwise
 * read the same episode, and the second reuses a number the first is about to retire.
 */
@RunWith(RobolectricTestRunner::class)
class PublisherDeletedAccountTest {

    private val now = 1_757_000_000_000L

    /** Stands in for SQLite: rejects a write whose parent account row is gone. */
    private class FakeNotificationDao(val live: MutableSet<String>) : NotificationDao {
        val events = mutableMapOf<String, NotificationEventEntity>()
        val states = mutableMapOf<String, NotificationStateEntity>()
        var insideTransaction = false
        var callsOutsideTransaction = 0

        private fun touch() { if (!insideTransaction) callsOutsideTransaction++ }

        override suspend fun claim(event: NotificationEventEntity): Long {
            touch()
            if (event.accountId !in live) throw IllegalStateException("FOREIGN KEY constraint failed (code 787)")
            return if (events.putIfAbsent(event.eventKey, event) == null) 1L else -1L
        }

        override suspend fun allStates(): List<NotificationStateEntity> { touch(); return states.values.toList() }

        override suspend fun upsertStates(states: List<NotificationStateEntity>) {
            touch()
            states.forEach {
                if (it.accountId !in live) throw IllegalStateException("FOREIGN KEY constraint failed (code 787)")
                this.states[it.accountId] = it
            }
        }

        override suspend fun pruneEventsBefore(cutoff: Long) { touch() }
        override suspend fun existingAccountIds(): List<String> { touch(); return live.toList() }
    }

    /** Marks the window the DAO is allowed to be touched in. */
    private class RecordingRunner(private val dao: FakeNotificationDao) : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            dao.insideTransaction = true
            try { return block() } finally { dao.insideTransaction = false }
        }
    }

    private fun usage(id: String, remaining: Double) = AccountUsage(
        account = ProviderAccount(
            localId = id, provider = ProviderId.CODEX, externalAccountId = "ext-$id",
            email = null, displayName = null, plan = null, credentialReference = "ref-$id",
            createdAt = 0, lastSuccessfulSync = null, attributes = emptyMap(),
        ),
        snapshot = UsageSnapshot(
            accountId = id, fetchedAt = now, status = SnapshotStatus.OK,
            windows = listOf(
                UsageWindow(
                    id = "w", label = "5h limit", category = WindowCategory.FIVE_HOUR,
                    usedPercent = 100.0 - remaining, periodSeconds = 18_000, resetAt = null,
                    exhausted = false,
                ),
            ),
        ),
    )

    @Test
    fun `a deleted account is skipped and the survivors are still published`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = FakeNotificationDao(live = mutableSetOf("alive"))
        val publisher = NotificationPublisher(
            context, SettingsStore(context), dao, now = { now }, transactions = RecordingRunner(dao),
        )

        // Both were read before "gone" was disconnected; only one still exists.
        publisher.publishFor(listOf(usage("gone", 5.0), usage("alive", 5.0)))

        assertTrue("the surviving account's keys are claimed", dao.events.values.any { it.accountId == "alive" })
        assertTrue("nothing was written for the vanished one", dao.events.values.none { it.accountId == "gone" })
        assertTrue(dao.states.keys.contains("alive"))
        assertTrue(dao.states.keys.none { it == "gone" })
    }

    @Test
    fun `every database call, the state read included, happens inside the transaction`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = FakeNotificationDao(live = mutableSetOf("alive"))
        val publisher = NotificationPublisher(
            context, SettingsStore(context), dao, now = { now }, transactions = RecordingRunner(dao),
        )

        publisher.publishFor(listOf(usage("alive", 5.0)))

        // The read is the one that matters: a transaction that opens after `allStates()` lets
        // a second publisher read the same episode and reuse the number this one retires.
        assertEquals("DAO calls made outside the transaction", 0, dao.callsOutsideTransaction)
    }
}
