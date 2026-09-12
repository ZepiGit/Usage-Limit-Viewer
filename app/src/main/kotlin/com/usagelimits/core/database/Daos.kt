package com.usagelimits.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE localId = :localId")
    suspend fun getById(localId: String): AccountEntity?

    /**
     * Resolves an account by its provider-side identity. Used on login to recognise a
     * re-authentication of an existing account instead of creating a duplicate.
     */
    @Query("SELECT * FROM accounts WHERE provider = :provider AND externalAccountId = :externalAccountId")
    suspend fun getByExternalId(provider: String, externalAccountId: String): AccountEntity?


    @Upsert
    suspend fun upsert(account: AccountEntity)


    @Query("DELETE FROM accounts WHERE localId = :localId")
    suspend fun deleteById(localId: String)

    @Query("UPDATE accounts SET lastSuccessfulSync = :timestamp WHERE localId = :localId")
    suspend fun markSynced(localId: String, timestamp: Long)

    /** One step of a reorder. Callers write the whole list inside one transaction. */
    @Query("UPDATE accounts SET sortOrder = :order WHERE localId = :localId")
    suspend fun setSortOrder(localId: String, order: Int)

    /**
     * Where a newly connected account belongs: after everything already here.
     *
     * `sortOrder` used to be 0 for every row, so a manual order made the next account added
     * jump to the top of it.
     */
    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM accounts")
    suspend fun nextSortOrder(): Int
}

@Dao
interface UsageSnapshotDao {

    @Query("SELECT * FROM usage_snapshots")
    fun observeAll(): Flow<List<UsageSnapshotEntity>>

    @Query("SELECT * FROM usage_snapshots")
    suspend fun getAll(): List<UsageSnapshotEntity>

    @Query("SELECT * FROM usage_snapshots WHERE accountId = :accountId")
    suspend fun getForAccount(accountId: String): UsageSnapshotEntity?


    @Upsert
    suspend fun upsert(snapshot: UsageSnapshotEntity)

    @Query("DELETE FROM usage_snapshots WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}

@Dao
interface WidgetConfigDao {
    @Query("SELECT * FROM widget_configs WHERE appWidgetId = :appWidgetId")
    fun observe(appWidgetId: Int): Flow<WidgetConfigEntity?>

    @Query("SELECT * FROM widget_configs WHERE appWidgetId = :appWidgetId")
    suspend fun get(appWidgetId: Int): WidgetConfigEntity?

    @Query("SELECT * FROM widget_configs")
    suspend fun getAll(): List<WidgetConfigEntity>

    @Upsert
    suspend fun upsert(config: WidgetConfigEntity)

    @Query("DELETE FROM widget_configs WHERE appWidgetId = :appWidgetId")
    suspend fun delete(appWidgetId: Int)


}

/**
 * Claims notification events and carries per-account state.
 *
 * [claim] is the whole point: `OnConflictStrategy.IGNORE` returns -1 for a row that already
 * existed, which is how "has this alert already been delivered" is answered without a
 * read-then-write race between two sync workers.
 */
@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(event: NotificationEventEntity): Long

    @Query("SELECT * FROM notification_state")
    suspend fun allStates(): List<NotificationStateEntity>

    /**
     * The accounts that exist RIGHT NOW, read inside the publisher's transaction.
     *
     * The publisher is handed a list read moments earlier. An account disconnected in between
     * — the user tapping "Disconnect" while a background sync is mid-pass — is still in that
     * list, and both tables below carry a foreign key to it: inserting its event or upserting
     * its state raises SQLITE_CONSTRAINT_FOREIGNKEY and aborts the whole publication, the
     * surviving accounts' alerts included.
     */
    @Query("SELECT localId FROM accounts")
    suspend fun existingAccountIds(): List<String>

    @Upsert
    suspend fun upsertStates(states: List<NotificationStateEntity>)

    /**
     * Drops consumed records older than [cutoff].
     *
     * Without this the table grows for the life of the install. The cutoff has to be long
     * enough that a monthly window's reset key is still remembered when it comes round again.
     */
    @Query("DELETE FROM notification_events WHERE consumedAt < :cutoff")
    suspend fun pruneEventsBefore(cutoff: Long)
}
