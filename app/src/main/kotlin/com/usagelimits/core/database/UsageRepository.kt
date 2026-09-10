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
 * Runs a block with the database's write serialisation held for its whole duration.
 *
 * Exists because several writes here are read-then-write: they check a row, then act on what
 * they read. Between those two statements the row can change, and every such pair in this file
 * had a way to go wrong — a snapshot written for an account deleted a moment earlier, a failure
 * blanking a success that landed in between, two logins minting two ids for one account.
 * Wrapping the pair makes the decision and the write one step.
 *
 * An interface rather than the database itself, so the unit tests can keep handing the
 * repository fake DAOs with no Room behind them.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/**
 * Runs the block as-is.
 *
 * The default, and correct for the tests: a fake DAO pair has no shared connection to serialise
 * against, and no concurrency for a transaction to protect them from.
 */
object DirectTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

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
    private val transactions: TransactionRunner = DirectTransactionRunner,
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
            accounts.mapNotNull { entity ->
                AccountUsage(
                    account = entity.toDomain() ?: return@mapNotNull null,
                    snapshot = byAccount[entity.localId]?.toDomain(),
                )
            }
        }

    fun observeAccounts(): Flow<List<ProviderAccount>> =
        accountDao.observeAll().map { list -> list.mapNotNull { it.toDomain() } }

    suspend fun accounts(): List<ProviderAccount> = accountDao.getAll().mapNotNull { it.toDomain() }

    suspend fun account(localId: String): ProviderAccount? = accountDao.getById(localId)?.toDomain()

    suspend fun snapshot(accountId: String): UsageSnapshot? =
        snapshotDao.getForAccount(accountId)?.toDomain()

    /** Snapshot of the whole cache, for widget rendering off the main thread. */
    suspend fun accountUsageOnce(): List<AccountUsage> {
        val snapshots = snapshotDao.getAll().associateBy { it.accountId }
        return accountDao.getAll().mapNotNull { entity ->
            AccountUsage(entity.toDomain() ?: return@mapNotNull null, snapshots[entity.localId]?.toDomain())
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
    ): ProviderAccount = transactions.inTransaction {
        // Read and write together. Two concurrent logins for the same external account could
        // otherwise both miss this lookup, both mint a local id, and the second insert would
        // meet the unique index on (provider, externalAccountId).
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
        // Built from a known ProviderId a moment ago, so this cannot be null.
        checkNotNull(entity.toDomain())
    }

    /**
     * Removes an account and everything hanging off it.
     *
     * The snapshot delete is redundant — the foreign key cascades — but it is explicit and
     * costs nothing, and both statements are in one transaction so a failure between them
     * cannot leave the account gone and its snapshot behind.
     */
    suspend fun deleteAccount(localId: String) = transactions.inTransaction {
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
    suspend fun saveSnapshot(snapshot: UsageSnapshot) = transactions.inTransaction {
        // The guard and the write are one step. Apart, the account could be deleted in the gap
        // between them and the insert would raise the very constraint violation the guard
        // exists to prevent — the failure the paragraph above describes, still reachable.
        if (accountDao.getById(snapshot.accountId) != null) {
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
    }

    /**
     * Records a failed sync while preserving the previous numbers.
     *
     * The UI keeps showing the last known usage with a staleness note, which is far more
     * useful than blanking a card because one refresh failed.
     *
     * No-ops for a removed account, for the same foreign-key reason as [saveSnapshot].
     */
    suspend fun saveFailure(accountId: String, message: String, nowMs: Long) =
        transactions.inTransaction {
            // Reading the previous row and replacing it must be one step. Between them a
            // concurrent successful sync can land, and this would then overwrite fresh
            // numbers with a FAILED row carrying the values it read before they arrived —
            // the card flipping from just-refreshed back to blank.
            if (accountDao.getById(accountId) != null) {
                val previous = snapshotDao.getForAccount(accountId)
                snapshotDao.upsert(
                    UsageSnapshotEntity(
                        accountId = accountId,
                        // Keep the original fetch time: the data is as old as it was, and
                        // the failure is carried separately.
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
        }

    /**
     * Null for a provider this build does not know.
     *
     * It used to substitute Codex, which handed the account Codex's label, Codex's reset-credit
     * button, and — worse — routed its credentials to the Codex implementation on the next
     * sync. A row this build cannot interpret is left out of every list until a build that
     * can read it comes along; it is never relabelled as something it is not.
     */
    private fun AccountEntity.toDomain(): ProviderAccount? {
        val providerId = ProviderId.fromId(provider) ?: return null
        return ProviderAccount(
        localId = localId,
        provider = providerId,
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
    }

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
