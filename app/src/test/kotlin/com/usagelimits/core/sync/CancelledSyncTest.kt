package com.usagelimits.core.sync

import com.usagelimits.core.auth.CredentialStore
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.database.AccountDao
import com.usagelimits.core.database.AccountEntity
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.database.UsageSnapshotDao
import com.usagelimits.core.database.UsageSnapshotEntity
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.HttpClient
import com.usagelimits.providers.ProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cancelled sync must leave no trace, and it used to leave error cards.
 *
 * `CancellationException` is an ordinary `Exception` on the JVM, so `syncAccount`'s catch-all
 * swallowed it and persisted "Unexpected error while refreshing" for every account still in
 * flight. WorkManager cancels periodic work routinely — a constraint lost, doze, the system
 * reclaiming the worker — so the user would open the app to failed accounts that had never
 * actually failed. Nothing here was broken except the record of it.
 *
 * `recordFailure` already rethrew cancellation for precisely this reason, which is what makes
 * the omission one level up an oversight rather than a decision.
 */
class CancelledSyncTest {

    private val now = 1_789_000_000_000L

    /** Stands in for a sync cancelled while it was reading credentials. */
    private class CancellingStore : CredentialStore {
        override suspend fun load(reference: String): OAuthCredentials? =
            throw CancellationException("worker stopped")
        override suspend fun save(reference: String, credentials: OAuthCredentials) = Unit
        override suspend fun delete(reference: String) = Unit
        override suspend fun references(): Set<String> = emptySet()
    }

    private class RecordingAccountDao(private val rows: Map<String, AccountEntity>) : AccountDao {
        override fun observeAll(): Flow<List<AccountEntity>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<AccountEntity> = rows.values.toList()
        override suspend fun getById(localId: String): AccountEntity? = rows[localId]
        override suspend fun getByExternalId(provider: String, externalAccountId: String) = null
        override suspend fun insert(account: AccountEntity) = Unit
        override suspend fun upsert(account: AccountEntity) = Unit
        override suspend fun delete(account: AccountEntity) = Unit
        override suspend fun deleteById(localId: String) = Unit
        override suspend fun markSynced(localId: String, timestamp: Long) = Unit
    }

    private class RecordingSnapshotDao : UsageSnapshotDao {
        val written = mutableListOf<UsageSnapshotEntity>()

        override fun observeAll(): Flow<List<UsageSnapshotEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<UsageSnapshotEntity> = emptyList()
        override suspend fun getForAccount(accountId: String): UsageSnapshotEntity? = null
        override fun observeForAccount(accountId: String): Flow<UsageSnapshotEntity?> = flowOf(null)
        override suspend fun deleteForAccount(accountId: String) = Unit
        override suspend fun upsert(snapshot: UsageSnapshotEntity) { written += snapshot }
    }

    private val account = ProviderAccount(
        localId = "U1",
        provider = ProviderId.CODEX,
        externalAccountId = "ext-U1",
        email = null,
        displayName = "Codex",
        plan = "Plus",
        credentialReference = "codex_U1",
        createdAt = now,
        lastSuccessfulSync = null,
    )

    private val entity = AccountEntity(
        localId = "U1",
        provider = "codex",
        externalAccountId = "ext-U1",
        email = null,
        displayName = "Codex",
        plan = "Plus",
        credentialReference = "codex_U1",
        createdAt = now,
        lastSuccessfulSync = null,
        attributesJson = """{"values":{}}""",
        sortOrder = 0,
    )

    @Test
    fun `a cancelled sync propagates and records nothing`() {
        val snapshots = RecordingSnapshotDao()
        val engine = SyncEngine(
            repository = UsageRepository(RecordingAccountDao(mapOf("U1" to entity)), snapshots),
            credentialStore = CancellingStore(),
            registry = ProviderRegistry(HttpClient()),
            nowMs = { now },
        )

        // Rethrown, not converted into a SyncOutcome: a caller that cancelled this work is not
        // asking for a verdict on the account.
        assertThrows(CancellationException::class.java) {
            runBlocking { engine.syncAccount(account) }
        }

        // And crucially, nothing was persisted. A FAILED row here is what turned a cancelled
        // background pass into error cards on healthy accounts.
        assertTrue(
            "a cancelled sync must not write a snapshot, got ${snapshots.written.size}",
            snapshots.written.isEmpty(),
        )
    }
}
