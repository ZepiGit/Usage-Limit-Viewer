package com.usagelimits.core.sync

import com.usagelimits.core.auth.CredentialStore
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.notifications.NotificationEvaluator
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.ProviderRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

    /**
     * One fetch-through-save at a time per account.
     *
     * Two passes over one account — the periodic worker and a pull-to-refresh, say — used to
     * race from fetch to save with nothing between them. A fetch that returned 80 % and was
     * slow to complete could land AFTER a later fetch that returned 5 %, and because
     * `fetchedAt` is stamped at completion the older figure arrived wearing the newer time:
     * the account rose from 5 % to 80 % with a reassuring fresh timestamp. Wall-clock
     * completion time is not an ordering. Holding this lock from fetch to save makes the
     * order of starts the order of writes, so the later attempt's figures are the ones kept.
     */
    private val accountLocks = mutableMapOf<String, Mutex>()

    private suspend fun accountLock(accountId: String): Mutex =
        mutexGuard.withLock { accountLocks.getOrPut(accountId) { Mutex() } }

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

    suspend fun syncAccount(account: ProviderAccount): SyncOutcome = accountLock(account.localId).withLock {
        // Emulator fixtures never contact a provider. R8 removes this branch from release APKs.
        if (com.usagelimits.BuildConfig.DEBUG && account.attributes["screenshotFixture"] == "true") {
            repository.snapshot(account.localId)?.let { repository.saveSnapshot(it.copy(fetchedAt = nowMs())) }
            return@withLock SyncOutcome(account.localId, success = true)
        }
        try {
            val provider = registry.forId(account.provider)
                ?: throw ProviderException.Unexpected("No provider for ${account.provider.id}")

            val credentials = validCredentials(account)
            val result = try {
                provider.fetchUsage(account, credentials)
            } catch (e: ProviderException.Unauthorized) {
                // The reactive half of the refresh policy, and what makes the proactive half
                // safe to keep conservative. The provider has said this token is dead, which
                // beats any expiry the account did or did not carry.
                //
                // Once only: if the renewed pair is refused too, the credential is genuinely
                // revoked and a second attempt is just another way to fail.
                val renewed = validCredentials(account, rejectedAccessToken = credentials.accessToken)
                provider.fetchUsage(account, renewed)
            }

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
            recordFailure(account.localId, e.userMessage(), e is ProviderException.Unauthorized)
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
    private suspend fun recordFailure(accountId: String, message: String, requiresReauthentication: Boolean = false) {
        try {
            repository.saveFailure(accountId, message, nowMs(), requiresReauthentication)
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
     *
     * [rejectedAccessToken] is the access token a request actually presented and the provider
     * actually refused. It is stronger evidence than any clock, and it is the only thing that
     * rescues an account whose provider never states an expiry: `needsRefresh` is false when
     * `expiresAt` is null, so for such an account the proactive branch can never fire, and
     * before this every usage call 401'd for ever with no attempt to renew. A stored expiry
     * set too far in the future produced the same silence until that date.
     *
     * It is passed as the token itself rather than as a `force` flag on purpose. A concurrent
     * pass may already have saved a DIFFERENT token, which this request never presented and
     * nothing has refused — so it is offered first, and only the refused token costs a
     * rotation. Presenting an already-spent refresh token is how a provider revokes the whole
     * grant, so the difference is not academic. iOS decides it the same way.
     */
    /**
     * Runs [block] holding the per-reference credential lock.
     *
     * Sign-out has to take this too, and did not. `validCredentials` checks that the reference
     * still exists and then saves the rotated pair, both inside this lock — but a delete that
     * did not hold it could land in between, so the save put the credential the user had just
     * removed straight back. The store's own operations are each atomic; what needs
     * serialising is the sequence, and that means one lock shared by everything that changes a
     * credential's existence.
     *
     * Keyed by credential reference rather than by account, because two accounts can share
     * one reference and both would otherwise spend the same rotating refresh token.
     */
    suspend fun <T> withCredentialLock(reference: String, block: suspend () -> T): T {
        val mutex = mutexGuard.withLock { refreshMutexes.getOrPut(reference) { Mutex() } }
        return mutex.withLock { block() }
    }

    suspend fun validCredentials(
        account: ProviderAccount,
        rejectedAccessToken: String? = null,
    ): OAuthCredentials {
        val reference = account.credentialReference
        val current = credentialStore.load(reference)
            ?: throw ProviderException.Unauthorized("No stored credentials for this account")

        // A rejection overrides the clock, but only for the token that was rejected.
        val currentWasRejected = rejectedAccessToken != null &&
            current.accessToken == rejectedAccessToken
        if (!currentWasRejected && !current.needsRefresh(nowMs())) return current

        return withCredentialLock(reference) {
            val latest = credentialStore.load(reference)
                ?: throw ProviderException.Unauthorized("Account was removed while waiting to refresh")
            val latestWasRejected = rejectedAccessToken != null &&
                latest.accessToken == rejectedAccessToken
            // Whoever held the lock may have rotated already. If what they saved is not the
            // token this caller was refused, it is worth trying before spending another.
            if (!latestWasRejected && !latest.needsRefresh(nowMs())) return@withCredentialLock latest

            val provider = registry.forId(account.provider)
                ?: throw ProviderException.Unexpected("No provider for ${account.provider.id}")

            // The exchange and the write that follows it are one indivisible step.
            //
            // A rotating provider invalidates `latest` the moment it answers. If the worker is
            // cancelled while the response is in flight — a lost constraint, doze, the system
            // reclaiming it — the result can be discarded on the way out of the IO dispatcher,
            // or the coroutine can stop between receiving it and writing it. Either way the
            // provider has moved on and the store still holds a pair that is now dead: the
            // account is signed out, with nothing anywhere recording why, and the user's only
            // route back is to notice and reconnect it by hand.
            //
            // Cancellation is honoured everywhere else, and is still honoured here — just
            // AFTER the pair is safe. `withContext(NonCancellable)` covers only the exchange
            // and its persistence, so a cancelled sync stops at the next suspension point
            // rather than at the one place where stopping costs the account.
            withContext(NonCancellable) {
                val refreshed = provider.refresh(latest)
                // Written back only if the store still holds the very pair this exchange
                // started from — a compare-and-swap on content, not merely a presence check.
                //
                // Presence alone answered the wrong question. It catches the account being
                // REMOVED mid-flight, which is why it was there, and misses the account being
                // signed in AGAIN: the user reconnects the same provider while this refresh is
                // still in the air, the login writes fresh credentials under the same
                // reference, and this then overwrites them with a pair derived from the
                // session they just replaced. The symptom is the one users report as "I logged
                // in and it signed me straight back out", and it is the same failure as losing
                // a rotated pair, arriving from the other direction.
                //
                // `OAuthCredentials` is a data class, so identity here is the whole pair. If
                // anything at all changed underneath — a newer login, another refresh that
                // finished first — this result is stale by definition and is dropped rather
                // than allowed to win on arrival order.
                if (credentialStore.load(reference) == latest) {
                    credentialStore.save(reference, refreshed)
                }
                refreshed
            }
        }
    }
}

/** Message safe to show a user: never contains a token, URL, or raw provider body. */
fun ProviderException.userMessage(): String = when (this) {
    // The evaluator's constant, not a second copy of the sentence. See
    // `NotificationEvaluator.SIGN_IN_EXPIRED_MESSAGE` for the notification a divergence here
    // silently disabled on the other platform.
    is ProviderException.Unauthorized -> NotificationEvaluator.SIGN_IN_EXPIRED_MESSAGE
    is ProviderException.Forbidden -> "Access denied for this account"
    is ProviderException.RateLimited -> "Rate limited — try again shortly"
    is ProviderException.ServerError -> "Provider is having trouble ($statusCode)"
    // The client names the host and the kind of failure; the generic line is only the fallback.
    is ProviderException.Offline -> message ?: "No network connection"
    is ProviderException.MalformedPayload -> "Unexpected response from provider"
    is ProviderException.LoginCancelled -> "Sign-in was cancelled"
    is ProviderException.Unexpected -> "Refresh failed"
}
