package com.usagelimits.core.sync

import com.usagelimits.core.auth.CredentialStore
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.ProviderRegistry
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-account outcome of a sync pass. */
data class SyncOutcome(
    val accountId: String,
    val success: Boolean,
    val message: String? = null,
)

/**
 * Refreshes usage for accounts.
 *
 * Three properties matter here:
 *
 *  - **Isolation.** Accounts sync concurrently and each failure is caught per account, so one
 *    revoked token or one provider outage cannot stop the others from updating.
 *  - **Refresh safety.** Token refreshes are serialised per credential reference. Without
 *    that, a periodic sync racing a manual pull-to-refresh could both refresh the same
 *    account, and providers invalidate the old refresh token on use — the loser would be left
 *    holding a dead token and the account would appear revoked.
 *  - **Stale over empty.** A failed fetch keeps the previous numbers and records the error, so
 *    the UI can say "last updated 37 minutes ago, refresh failed" instead of blanking a card.
 */
class SyncEngine(
    private val repository: UsageRepository,
    private val credentialStore: CredentialStore,
    private val registry: ProviderRegistry,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val refreshMutexes = mutableMapOf<String, Mutex>()
    private val mutexGuard = Mutex()

    /** Syncs every account concurrently. Never throws; failures surface per account. */
    suspend fun syncAll(): List<SyncOutcome> = coroutineScope {
        repository.accounts()
            .map { account -> async { syncAccount(account) } }
            .awaitAll()
    }

    suspend fun syncAccount(accountId: String): SyncOutcome {
        val account = repository.account(accountId)
            ?: return SyncOutcome(accountId, success = false, message = "Account not found")
        return syncAccount(account)
    }

    suspend fun syncAccount(account: ProviderAccount): SyncOutcome {
        return try {
            val provider = registry.forId(account.provider)
                ?: throw ProviderException.Unexpected("No provider for ${account.provider.id}")

            val credentials = validCredentials(account)
            val result = provider.fetchUsage(account, credentials)

            repository.saveSnapshot(
                UsageSnapshot(
                    accountId = account.localId,
                    fetchedAt = nowMs(),
                    status = SnapshotStatus.OK,
                    windows = result.windows,
                    resetCredits = result.resetCredits,
                    resetCreditCount = result.resetCreditCount,
                ),
            )
            SyncOutcome(account.localId, success = true)
        } catch (e: ProviderException) {
            repository.saveFailure(account.localId, e.userMessage(), nowMs())
            SyncOutcome(account.localId, success = false, message = e.userMessage())
        } catch (e: Exception) {
            // A provider bug must not take the whole sync pass down with it.
            val message = "Unexpected error while refreshing"
            repository.saveFailure(account.localId, message, nowMs())
            SyncOutcome(account.localId, success = false, message = message)
        }
    }

    /**
     * Returns usable credentials, refreshing first if the access token is at or near expiry.
     *
     * The refresh is guarded by a per-account mutex and the expiry is re-checked inside the
     * lock, so a caller that queued behind another refresh uses that result instead of
     * spending the (now-rotated) refresh token a second time.
     */
    suspend fun validCredentials(account: ProviderAccount): OAuthCredentials {
        val reference = account.credentialReference
        val current = credentialStore.load(reference)
            ?: throw ProviderException.Unauthorized("No stored credentials for this account")

        if (!current.needsRefresh(nowMs())) return current

        val mutex = mutexGuard.withLock { refreshMutexes.getOrPut(reference) { Mutex() } }
        return mutex.withLock {
            val latest = credentialStore.load(reference) ?: current
            if (!latest.needsRefresh(nowMs())) return@withLock latest

            val provider = registry.forId(account.provider)
                ?: throw ProviderException.Unexpected("No provider for ${account.provider.id}")

            val refreshed = provider.refresh(latest)
            credentialStore.save(reference, refreshed)
            refreshed
        }
    }
}

/** Message safe to show a user: never contains a token, URL, or raw provider body. */
fun ProviderException.userMessage(): String = when (this) {
    is ProviderException.Unauthorized -> "Sign-in expired — reconnect this account"
    is ProviderException.Forbidden -> "Access denied for this account"
    is ProviderException.RateLimited -> "Rate limited — try again shortly"
    is ProviderException.ServerError -> "Provider is having trouble ($statusCode)"
    is ProviderException.Offline -> "No network connection"
    is ProviderException.MalformedPayload -> "Unexpected response from provider"
    is ProviderException.LoginCancelled -> "Sign-in was cancelled"
    is ProviderException.Unexpected -> "Refresh failed"
}
