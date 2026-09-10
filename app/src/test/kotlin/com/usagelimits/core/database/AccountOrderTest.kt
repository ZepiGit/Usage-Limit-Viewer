package com.usagelimits.core.database

import com.usagelimits.core.model.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order the user dragged the accounts into, as it reaches the database.
 *
 * Two things here are easy to get wrong and invisible when wrong. Renumbering only the two
 * cards that swapped leaves an order that is neither the old one nor the new one, and shows up
 * as cards drifting back a position at a time over later drags. And a newly connected account
 * inheriting `sortOrder = 0` puts it at the TOP of a list the user arranged by hand, which
 * reads as the arrangement having been thrown away.
 */
class AccountOrderTest {

    /** Records what the repository writes, rather than pretending to. */
    private class OrderingAccountDao(
        private val rows: MutableMap<String, AccountEntity>,
    ) : AccountDao {
        val writes = mutableListOf<Pair<String, Int>>()

        override fun observeAll(): Flow<List<AccountEntity>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<AccountEntity> = rows.values.toList()
        override suspend fun getById(localId: String) = rows[localId]
        override suspend fun getByExternalId(provider: String, externalAccountId: String) =
            rows.values.firstOrNull {
                it.provider == provider && it.externalAccountId == externalAccountId
            }

        override suspend fun upsert(account: AccountEntity) { rows[account.localId] = account }
        override suspend fun deleteById(localId: String) { rows.remove(localId) }
        override suspend fun markSynced(localId: String, timestamp: Long) = Unit

        override suspend fun setSortOrder(localId: String, order: Int) {
            writes += localId to order
            rows[localId]?.let { rows[localId] = it.copy(sortOrder = order) }
        }

        override suspend fun nextSortOrder(): Int =
            (rows.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
    }

    private class NoSnapshotDao : UsageSnapshotDao {
        override fun observeAll(): Flow<List<UsageSnapshotEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<UsageSnapshotEntity> = emptyList()
        override suspend fun getForAccount(accountId: String): UsageSnapshotEntity? = null
        override suspend fun deleteForAccount(accountId: String) = Unit
        override suspend fun upsert(snapshot: UsageSnapshotEntity) = Unit
    }

    private val now = 1_800_000_000_000L

    private fun row(id: String, order: Int) = AccountEntity(
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
        sortOrder = order,
    )

    @Test
    fun `a reorder renumbers the whole list, not just the pair that moved`() {
        val rows = linkedMapOf("A" to row("A", 0), "B" to row("B", 1), "C" to row("C", 2))
        val dao = OrderingAccountDao(rows)
        val repository = UsageRepository(dao, NoSnapshotDao(), transactions = DirectTransactionRunner)

        runBlocking { repository.reorderAccounts(listOf("C", "A", "B")) }

        assertEquals(
            "every id in the new order gets its index, so no two rows can end up sharing one",
            listOf("C" to 0, "A" to 1, "B" to 2),
            dao.writes,
        )
        assertEquals(0, rows["C"]!!.sortOrder)
        assertEquals(1, rows["A"]!!.sortOrder)
        assertEquals(2, rows["B"]!!.sortOrder)
    }

    @Test
    fun `an account the caller did not mention keeps its place`() {
        // Connected on another screen while the overview was open. Renumbering it to 0 would
        // move a card the user never touched.
        val rows = linkedMapOf("A" to row("A", 0), "B" to row("B", 1), "late" to row("late", 9))
        val dao = OrderingAccountDao(rows)
        val repository = UsageRepository(dao, NoSnapshotDao(), transactions = DirectTransactionRunner)

        runBlocking { repository.reorderAccounts(listOf("B", "A")) }

        assertEquals(9, rows["late"]!!.sortOrder)
    }

    @Test
    fun `a newly connected account joins the end, not the front`() {
        val rows = linkedMapOf("A" to row("A", 0), "B" to row("B", 1))
        val dao = OrderingAccountDao(rows)
        val repository = UsageRepository(dao, NoSnapshotDao(), transactions = DirectTransactionRunner)

        val created = runBlocking {
            repository.upsertFromLogin(
                provider = ProviderId.CLAUDE,
                externalAccountId = "ext-new",
                email = null,
                displayName = null,
                plan = null,
                attributes = emptyMap(),
            )
        }

        assertEquals(
            "the new account sorts after everything already arranged",
            2,
            rows[created.localId]!!.sortOrder,
        )
    }
}
