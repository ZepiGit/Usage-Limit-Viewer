package com.usagelimits.core.database

import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ResetCredit
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** An account paired with its latest snapshot — what every screen and widget renders. */
data class AccountUsage(
    val account: ProviderAccount,
    val snapshot: UsageSnapshot?,
)

/**
 * The app's single read/write surface over the local cache.
 *
 * Converts between Room entities and domain models, keeping JSON serialisation of the
 * normalised window list an implementation detail. Callers never see an entity.
 */
class UsageRepository(
    private val accountDao: AccountDao,
    private val snapshotDao: UsageSnapshotDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    // Persisted shapes. Kept separate from the domain models so a UI-facing change (adding a
    // computed property, renaming a label) cannot silently alter the on-disk format.

    @Serializable
    private data class StoredWindow(
        val id: String,
        val label: String,
        val category: String,
        val usedPercent: Double? = null,
        val periodSeconds: Long? = null,
        val resetAt: Long? = null,
        val exhausted: Boolean = false,
        val group: String? = null,
    )

    @Serializable
    private data class StoredCredit(
        val id: String,
        val grantedAt: Long? = null,
        val expiresAt: Long? = null,
        val status: String,
    )

    @Serializable
    private data class StoredAttributes(val values: Map<String, String> = emptyMap())

    fun observeAccountUsage(): Flow<List<AccountUsage>> =
        combine(accountDao.observeAll(), snapshotDao.observeAll()) { accounts, snapshots ->
            val byAccount = snapshots.associateBy { it.accountId }
            accounts.map { entity ->
                AccountUsage(
                    account = entity.toDomain(),
                    snapshot = byAccount[entity.localId]?.toDomain(),
                )
            }
        }

    fun observeAccounts(): Flow<List<ProviderAccount>> =
        accountDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun accounts(): List<ProviderAccount> = accountDao.getAll().map { it.toDomain() }

    suspend fun account(localId: String): ProviderAccount? = accountDao.getById(localId)?.toDomain()

    suspend fun snapshot(accountId: String): UsageSnapshot? =
        snapshotDao.getForAccount(accountId)?.toDomain()

    /** Snapshot of the whole cache, for widget rendering off the main thread. */
    suspend fun accountUsageOnce(): List<AccountUsage> {
        val snapshots = snapshotDao.getAll().associateBy { it.accountId }
        return accountDao.getAll().map { entity ->
            AccountUsage(entity.toDomain(), snapshots[entity.localId]?.toDomain())
        }
    }

    /**
     * Creates or updates an account from a completed login.
     *
     * Identity is provider + external account id, so re-authenticating an existing account
     * refreshes it in place — including its local id, which widgets may already reference —
     * instead of producing a second entry.
     */
    suspend fun upsertFromLogin(
        provider: ProviderId,
        externalAccountId: String,
        email: String?,
        displayName: String?,
        plan: String?,
        attributes: Map<String, String>,
    ): ProviderAccount {
        val existing = accountDao.getByExternalId(provider.id, externalAccountId)
        val localId = existing?.localId ?: UUID.randomUUID().toString()
        val entity = AccountEntity(
            localId = localId,
            provider = provider.id,
            externalAccountId = externalAccountId,
            email = email,
            displayName = displayName,
            plan = plan,
            // Reference stays stable across re-auth so the stored credential is replaced,
            // not orphaned alongside a new one.
            credentialReference = existing?.credentialReference ?: "${provider.id}_$localId",
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastSuccessfulSync = existing?.lastSuccessfulSync,
            attributesJson = json.encodeToString(
                StoredAttributes.serializer(),
                StoredAttributes(attributes),
            ),
            sortOrder = existing?.sortOrder ?: 0,
        )
        accountDao.upsert(entity)
        return entity.toDomain()
    }

    suspend fun deleteAccount(localId: String) {
        snapshotDao.deleteForAccount(localId)
        accountDao.deleteById(localId)
    }

    /**
     * Records a successful fetch.
     *
     * No-ops when the account is gone. `usage_snapshots.accountId` is a foreign key onto
     * `accounts`, so writing a snapshot for a row the user removed while the fetch was in
     * flight raises SQLITE_CONSTRAINT_FOREIGNKEY — which Room rethrows, which escapes the
     * sync pass, cancels the sibling accounts' writes and reaches the uncaught handler.
     * An account that no longer exists has no usage worth recording.
     */
    suspend fun saveSnapshot(snapshot: UsageSnapshot) {
        if (accountDao.getById(snapshot.accountId) == null) return
        snapshotDao.upsert(
            UsageSnapshotEntity(
                accountId = snapshot.accountId,
                fetchedAt = snapshot.fetchedAt,
                status = snapshot.status.name,
                errorMessage = snapshot.errorMessage,
                windowsJson = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(StoredWindow.serializer()),
                    snapshot.windows.map { it.toStored() },
                ),
                resetCreditsJson = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(StoredCredit.serializer()),
                    snapshot.resetCredits.map { it.toStored() },
                ),
                resetCreditCount = snapshot.resetCreditCount,
                applicableResetCreditCount = snapshot.applicableResetCreditCount,
            ),
        )
        if (snapshot.status != SnapshotStatus.FAILED) {
            accountDao.markSynced(snapshot.accountId, snapshot.fetchedAt)
        }
    }

    /**
     * Records a failed sync while preserving the previous numbers.
     *
     * The UI keeps showing the last known usage with a staleness note, which is far more
     * useful than blanking a card because one refresh failed.
     *
     * No-ops for a removed account, for the same foreign-key reason as [saveSnapshot].
     */
    suspend fun saveFailure(accountId: String, message: String, nowMs: Long) {
        if (accountDao.getById(accountId) == null) return
        val previous = snapshotDao.getForAccount(accountId)
        snapshotDao.upsert(
            UsageSnapshotEntity(
                accountId = accountId,
                // Keep the original fetch time: the data is as old as it was, and the
                // failure is carried separately.
                fetchedAt = previous?.fetchedAt ?: nowMs,
                status = SnapshotStatus.FAILED.name,
                errorMessage = message,
                windowsJson = previous?.windowsJson ?: "[]",
                resetCreditsJson = previous?.resetCreditsJson ?: "[]",
                resetCreditCount = previous?.resetCreditCount,
                applicableResetCreditCount = previous?.applicableResetCreditCount,
            ),
        )
    }

    private fun AccountEntity.toDomain(): ProviderAccount = ProviderAccount(
        localId = localId,
        provider = ProviderId.fromId(provider) ?: ProviderId.CODEX,
        externalAccountId = externalAccountId,
        email = email,
        displayName = displayName,
        plan = plan,
        credentialReference = credentialReference,
        createdAt = createdAt,
        lastSuccessfulSync = lastSuccessfulSync,
        attributes = runCatching {
            json.decodeFromString(StoredAttributes.serializer(), attributesJson).values
        }.getOrDefault(emptyMap()),
    )

    private fun UsageSnapshotEntity.toDomain(): UsageSnapshot = UsageSnapshot(
        accountId = accountId,
        fetchedAt = fetchedAt,
        status = runCatching { SnapshotStatus.valueOf(status) }.getOrDefault(SnapshotStatus.FAILED),
        windows = runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(StoredWindow.serializer()),
                windowsJson,
            ).map { it.toDomain() }
        }.getOrDefault(emptyList()),
        resetCredits = runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(StoredCredit.serializer()),
                resetCreditsJson,
            ).map { it.toDomain() }
        }.getOrDefault(emptyList()),
        resetCreditCount = resetCreditCount,
        applicableResetCreditCount = applicableResetCreditCount,
        errorMessage = errorMessage,
    )

    private fun UsageWindow.toStored() = StoredWindow(
        id = id,
        label = label,
        category = category.name,
        usedPercent = usedPercent,
        periodSeconds = periodSeconds,
        resetAt = resetAt,
        exhausted = exhausted,
        group = group,
    )

    private fun StoredWindow.toDomain() = UsageWindow(
        id = id,
        label = label,
        category = runCatching { WindowCategory.valueOf(category) }
            .getOrDefault(WindowCategory.OTHER),
        usedPercent = usedPercent,
        periodSeconds = periodSeconds,
        resetAt = resetAt,
        exhausted = exhausted,
        group = group,
    )

    private fun ResetCredit.toStored() = StoredCredit(id, grantedAt, expiresAt, status)

    private fun StoredCredit.toDomain() = ResetCredit(id, grantedAt, expiresAt, status)
}
