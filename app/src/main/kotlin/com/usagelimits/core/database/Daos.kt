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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity)

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE localId = :localId")
    suspend fun deleteById(localId: String)

    @Query("UPDATE accounts SET lastSuccessfulSync = :timestamp WHERE localId = :localId")
    suspend fun markSynced(localId: String, timestamp: Long)
}

@Dao
interface UsageSnapshotDao {

    @Query("SELECT * FROM usage_snapshots")
    fun observeAll(): Flow<List<UsageSnapshotEntity>>

    @Query("SELECT * FROM usage_snapshots")
    suspend fun getAll(): List<UsageSnapshotEntity>

    @Query("SELECT * FROM usage_snapshots WHERE accountId = :accountId")
    suspend fun getForAccount(accountId: String): UsageSnapshotEntity?

    @Query("SELECT * FROM usage_snapshots WHERE accountId = :accountId")
    fun observeForAccount(accountId: String): Flow<UsageSnapshotEntity?>

    @Upsert
    suspend fun upsert(snapshot: UsageSnapshotEntity)

    @Query("DELETE FROM usage_snapshots WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}

@Dao
interface WidgetConfigDao {

    @Query("SELECT * FROM widget_configs WHERE appWidgetId = :appWidgetId")
    suspend fun get(appWidgetId: Int): WidgetConfigEntity?

    @Query("SELECT * FROM widget_configs")
    suspend fun getAll(): List<WidgetConfigEntity>

    @Upsert
    suspend fun upsert(config: WidgetConfigEntity)

    @Query("DELETE FROM widget_configs WHERE appWidgetId = :appWidgetId")
    suspend fun delete(appWidgetId: Int)
}
