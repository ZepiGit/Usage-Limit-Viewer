package com.usagelimits.core.database

import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sync that lands after its account was removed must not write.
 *
 * `usage_snapshots.accountId` is a foreign key onto `accounts`, and Room's upsert issues a
 * plain INSERT, so a snapshot for a vanished parent raises SQLITE_CONSTRAINT_FOREIGNKEY.
 * Room 2.6.1 rethrows that (its uniqueness fallback only tolerates "unique"/2067/1555), it
 * escapes `SyncEngine.syncAccount`, cancels the sibling accounts inside `syncAll`'s scope and
 * reaches the uncaught handler. The trigger is two taps: "Refresh now" then "Remove account"
 * while the provider call is still outstanding.
 */
class OrphanSnapshotTest {

    private class FakeAccountDao(private val rows: MutableMap<String, AccountEntity>) : AccountDao {
        var markSyncedCalls = 0

        override fun observeAll(): Flow<List<AccountEntity>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<AccountEntity> = rows.values.toList()
        override suspend fun getById(localId: String): AccountEntity? = rows[localId]
        override suspend fun getByExternalId(provider: String, externalAccountId: String) =
            rows.values.firstOrNull { it.provider == provider && it.externalAccountId == externalAccountId }

        override suspend fun insert(account: AccountEntity) { rows[account.localId] = account }
        override suspend fun upsert(account: AccountEntity) { rows[account.localId] = account }
        override suspend fun delete(account: AccountEntity) { rows.remove(account.localId) }
        override suspend fun deleteById(localId: String) { rows.remove(localId) }
        override suspend fun markSynced(localId: String, timestamp: Long) { markSyncedCalls++ }
    }

    /** Stands in for SQLite: rejects any insert whose parent account row is gone. */
    private class FakeSnapshotDao(private val accounts: Map<String, AccountEntity>) : UsageSnapshotDao {
        val rows = mutableMapOf<String, UsageSnapshotEntity>()
        var upsertAttempts = 0

        override fun observeAll(): Flow<List<UsageSnapshotEntity>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<UsageSnapshotEntity> = rows.values.toList()
        override suspend fun getForAccount(accountId: String) = rows[accountId]
        override fun observeForAccount(accountId: String): Flow<UsageSnapshotEntity?> = flowOf(rows[accountId])
        override suspend fun deleteForAccount(accountId: String) { rows.remove(accountId) }

        override suspend fun upsert(snapshot: UsageSnapshotEntity) {
            upsertAttempts++
            if (!accounts.containsKey(snapshot.accountId)) {
                throw IllegalStateException("FOREIGN KEY constraint failed (code 787)")
            }
            rows[snapshot.accountId] = snapshot
        }
    }

    private val now = 1_757_000_000_000L

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
    )

    private fun snapshot(accountId: String) = UsageSnapshot(
        accountId = accountId,
        fetchedAt = now,
        status = SnapshotStatus.OK,
        windows = listOf(
            UsageWindow(
                id = "w1",
                label = "Weekly",
                category = WindowCategory.WEEKLY,
                usedPercent = 40.0,
                periodSeconds = 604_800,
                resetAt = now + 3_600_000,
                exhausted = false,
            ),
        ),
    )

    @Test
    fun `a snapshot for a removed account is dropped, not written`() = runBlocking {
        val accounts = mutableMapOf<String, AccountEntity>()
        val accountDao = FakeAccountDao(accounts)
        val snapshotDao = FakeSnapshotDao(accounts)
        val repository = UsageRepository(accountDao, snapshotDao)

        // The account existed when the fetch started and is gone by the time it returns.
        repository.saveSnapshot(snapshot("A"))

        assertEquals("no insert should have been attempted", 0, snapshotDao.upsertAttempts)
        assertEquals(0, accountDao.markSyncedCalls)
        assertTrue(snapshotDao.rows.isEmpty())
    }

    @Test
    fun `a failure for a removed account is dropped, not written`() = runBlocking {
        val accounts = mutableMapOf<String, AccountEntity>()
        val accountDao = FakeAccountDao(accounts)
        val snapshotDao = FakeSnapshotDao(accounts)
        val repository = UsageRepository(accountDao, snapshotDao)

        repository.saveFailure("A", "Sign-in expired — reconnect this account", now)

        assertEquals(0, snapshotDao.upsertAttempts)
        assertTrue(snapshotDao.rows.isEmpty())
    }

    @Test
    fun `a snapshot for a live account is still written`() = runBlocking {
        val accounts = mutableMapOf("A" to account("A"))
        val accountDao = FakeAccountDao(accounts)
        val snapshotDao = FakeSnapshotDao(accounts)
        val repository = UsageRepository(accountDao, snapshotDao)

        repository.saveSnapshot(snapshot("A"))
        repository.saveFailure("A", "No network connection", now)

        assertEquals(2, snapshotDao.upsertAttempts)
        assertEquals(1, accountDao.markSyncedCalls)
        assertEquals(SnapshotStatus.FAILED.name, snapshotDao.rows.getValue("A").status)
        // The failure keeps the numbers it found, which is the whole point of saveFailure.
        assertTrue(snapshotDao.rows.getValue("A").windowsJson.contains("Weekly"))
    }
}
