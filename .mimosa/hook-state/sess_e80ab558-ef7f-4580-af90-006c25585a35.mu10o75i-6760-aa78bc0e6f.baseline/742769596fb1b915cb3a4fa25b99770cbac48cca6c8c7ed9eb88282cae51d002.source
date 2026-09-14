package com.usagelimits.core.database

import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The guard in [UsageRepository.saveSnapshot] is only worth anything if nothing can happen
 * between it and the write it protects.
 *
 * `OrphanSnapshotTest` proves the guard rejects a snapshot for an account that was ALREADY
 * gone when the guard ran. That is the easy half. This is the other half: an account that is
 * present when the guard reads it and gone by the time the insert issues. Two statements with
 * no transaction around them cannot exclude that, and the trigger is ordinary — "Refresh now",
 * then "Remove account" while the provider call is still outstanding.
 *
 * Both tests below drive the SAME repository code down the same path. The only difference is
 * the transaction scope it was given, which is the point: the first shows the failure is real
 * without one, the second shows the fix is the transaction and not a change of shape.
 */
class SnapshotWriteRaceTest {

    /** Models Room's write serialisation: one writer at a time, for the whole block. */
    private class MutexTransactionRunner : TransactionRunner {
        private val mutex = Mutex()
        override suspend fun <T> inTransaction(block: suspend () -> T): T = mutex.withLock { block() }
    }

    /** An account DAO that lets the test run something at the moment the guard reads. */
    private class RacingAccountDao(
        private val rows: MutableMap<String, AccountEntity>,
        private val onRead: suspend () -> Unit,
    ) : AccountDao {
        var markSyncedCalls = 0

        override fun observeAll(): Flow<List<AccountEntity>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<AccountEntity> = rows.values.toList()

        override suspend fun getById(localId: String): AccountEntity? {
            val found = rows[localId]
            // The suspension point that a real DAO has anyway. Everything this test is about
            // happens in the window it opens.
            onRead()
            return found
        }

        override suspend fun getByExternalId(provider: String, externalAccountId: String) =
            rows.values.firstOrNull {
                it.provider == provider && it.externalAccountId == externalAccountId
            }

        override suspend fun upsert(account: AccountEntity) { rows[account.localId] = account }
        override suspend fun deleteById(localId: String) { rows.remove(localId) }
        override suspend fun markSynced(localId: String, timestamp: Long) { markSyncedCalls++ }
        // Ordering plays no part in what these tests exercise; the reorder path has its own
        // double that actually records.
        override suspend fun setSortOrder(localId: String, order: Int) = Unit
        override suspend fun nextSortOrder(): Int = 0
    }

    /** Stands in for SQLite: rejects any insert whose parent account row is gone. */
    private class FkCheckingSnapshotDao(
        private val accounts: Map<String, AccountEntity>,
    ) : UsageSnapshotDao {
        val rows = mutableMapOf<String, UsageSnapshotEntity>()

        override fun observeAll(): Flow<List<UsageSnapshotEntity>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<UsageSnapshotEntity> = rows.values.toList()
        override suspend fun getForAccount(accountId: String) = rows[accountId]
        override suspend fun deleteForAccount(accountId: String) { rows.remove(accountId) }

        override suspend fun upsert(snapshot: UsageSnapshotEntity) {
            if (!accounts.containsKey(snapshot.accountId)) {
                throw IllegalStateException("FOREIGN KEY constraint failed (code 787)")
            }
            rows[snapshot.accountId] = snapshot
        }
    }

    private val now = 1_700_000_000_000L

    private fun account(id: String) = AccountEntity(
        localId = id,
        provider = "codex",
        externalAccountId = "ext-$id",
        email = null,
        displayName = null,
        plan = null,
        credentialReference = "codex_$id",
        createdAt = now,
        lastSuccessfulSync = null,
        attributesJson = """{"values":{}}""",
        sortOrder = 0,
    )

    private fun snapshot(id: String) = UsageSnapshot(
        accountId = id,
        fetchedAt = now,
        status = SnapshotStatus.OK,
        windows = listOf(
            UsageWindow(
                id = "$id-weekly",
                label = "Weekly",
                category = WindowCategory.WEEKLY,
                usedPercent = 40.0,
                periodSeconds = 604_800,
                resetAt = now + 3_600_000,
                exhausted = false,
            ),
        ),
    )

    /**
     * Without a transaction the failure is reachable — this is the bug, demonstrated.
     *
     * If this test ever starts passing by NOT throwing, the interleave stopped happening and
     * the test below stopped proving anything, so it would need rewriting rather than deleting.
     */
    @Test
    fun `without a transaction a delete can land between the guard and the insert`() = runBlocking {
        val accounts = mutableMapOf("A" to account("A"))
        lateinit var repository: UsageRepository
        var deleted = false

        val accountDao = RacingAccountDao(accounts) {
            // The user removes the account while the guard is mid-read. One delete only, or
            // the delete inside `deleteAccount` would recurse through this same hook.
            if (!deleted) {
                deleted = true
                repository.deleteAccount("A")
            }
        }
        val snapshotDao = FkCheckingSnapshotDao(accounts)
        repository = UsageRepository(accountDao, snapshotDao, transactions = DirectTransactionRunner)

        try {
            repository.saveSnapshot(snapshot("A"))
            fail("expected the foreign key violation the guard is supposed to prevent")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "should be the FK violation, not some other failure",
                expected.message.orEmpty().contains("FOREIGN KEY"),
            )
        }
        assertTrue("the account really was removed", accounts.isEmpty())
    }

    /**
     * With one, the pair is atomic and the delete waits its turn.
     *
     * The delete is started while the guard reads, exactly as above, but it cannot get in: the
     * snapshot write completes first and the removal follows, taking the snapshot with it.
     */
    @Test
    fun `a transaction holds the guard and the insert together`() = runBlocking {
        val accounts = mutableMapOf("A" to account("A"))
        lateinit var repository: UsageRepository
        var started = false

        val scope = CoroutineScope(coroutineContext)
        val accountDao = RacingAccountDao(accounts) {
            if (!started) {
                started = true
                // Launched rather than awaited: it blocks on the same lock this write holds,
                // so awaiting it here would deadlock — which is itself the exclusion working.
                scope.launch { repository.deleteAccount("A") }
                yield()
            }
        }
        val snapshotDao = FkCheckingSnapshotDao(accounts)
        repository = UsageRepository(accountDao, snapshotDao, transactions = MutexTransactionRunner())

        // No throw: the insert ran while the account was still there.
        repository.saveSnapshot(snapshot("A"))
        assertNotNull("the snapshot was written", snapshotDao.rows["A"])
        assertEquals(1, accountDao.markSyncedCalls)

        // And the delete, once it gets the lock, still removes both.
        yield()
        assertTrue("the account was removed after the write", accounts.isEmpty())
        assertTrue("its snapshot went with it", snapshotDao.rows.isEmpty())
    }
}
