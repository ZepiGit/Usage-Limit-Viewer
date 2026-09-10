package com.usagelimits.core.sync

import com.usagelimits.core.auth.CredentialStore
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.ProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
                    applicableResetCreditCount = result.applicableResetCreditCount,
                ),
            )
            SyncOutcome(account.localId, success = true)
        } catch (e: CancellationException) {
            // Cancellation is not a sync failure and must never be recorded as one.
            //
            // `CancellationException` IS an `Exception` on the JVM, so the catch below used to
            // swallow it: WorkManager stopping a periodic pass — a constraint lost, doze, the
            // system reclaiming the worker — wrote "Unexpected error while refreshing" onto
            // every account still in flight. Accounts that were perfectly healthy came back as
            // error cards, and swallowing it also breaks structured concurrency, since the
            // coroutine carries on after its scope has been cancelled.
            //
            // `recordFailure` already rethrew it for exactly this reason; this is the same
            // hazard one level up.
            throw e
        } catch (e: ProviderException) {
            // A cancelled call reaches here too, wearing the wrong clothes: OkHttp answers
            // cancellation with IOException("Canceled"), which the client maps to Offline. So
            // the scope is checked before anything is persisted — otherwise a cancelled sync
            // records "No network connection" against an account with a working connection.
            currentCoroutineContext().ensureActive()
            recordFailure(account.localId, e.userMessage())
            SyncOutcome(account.localId, success = false, message = e.userMessage())
        } catch (e: Exception) {
            // A provider bug must not take the whole sync pass down with it.
            currentCoroutineContext().ensureActive()
            val message = "Unexpected error while refreshing"
            recordFailure(account.localId, message)
            SyncOutcome(account.localId, success = false, message = message)
        }
    }

    /**
     * Best-effort persistence of a failure.
     *
     * These calls sit in catch blocks, outside any try. A throw from here would leave
     * [syncAccount] — documented as never throwing — and cancel the sibling accounts sharing
     * the [syncAll] scope, discarding their freshly fetched numbers.
     */
    private suspend fun recordFailure(accountId: String, message: String) {
        try {
            repository.saveFailure(accountId, message, nowMs())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing further to do: the error is already reflected in the returned outcome.
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
            // The account can be removed while the refresh round-trip is in flight. Saving
            // unconditionally would put a live, freshly rotated refresh token back into the
            // store under a reference no account row names any more — nothing reads it and
            // nothing ever deletes it, so it would outlive the account the user removed.
            if (credentialStore.load(reference) != null) {
                credentialStore.save(reference, refreshed)
            }
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
