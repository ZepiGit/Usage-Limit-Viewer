package com.usagelimits.feature

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.sync.publishAfterSync
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.sync.SyncWorker
import com.usagelimits.core.sync.userMessage
import com.usagelimits.core.network.ProviderException
import com.usagelimits.widget.WidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

/** A reset event on the Resets timeline. */
data class UpcomingReset(
    val accountId: String,
    val accountLabel: String,
    val provider: ProviderId,
    /**
     * The originating window's id, not its label.
     *
     * Labels are provider display text and repeat freely — Antigravity names a bucket
     * "Weekly" once per quota group — so accountId + label + resetAt is not unique and made a
     * duplicate LazyColumn key, which Compose throws on. Ids are unique per account.
     */
    val windowId: String,
    val windowLabel: String,
    val resetAt: Long,
    val severity: Severity,
)

/** Everything the screens render. */
data class UsageUiState(
    val accounts: List<AccountUsage> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val isRefreshing: Boolean = false,
    val message: String? = null,
) {
    val accountCount: Int get() = accounts.size

    /**
     * How old a snapshot may be before this screen stops trusting it.
     *
     * Derived from the interval the user chose rather than fixed, because at the three-hour
     * setting the app offers, a fixed hour marks every account stale permanently.
     */
    val staleAfterMs: Long get() = Severity.staleAfterMs(settings.syncIntervalMinutes)

    /**
     * The accounts in the order this screen should show them.
     *
     * Two orders, and which one applies is a choice the user has already made. Until anyone
     * drags a card, the list is ranked most-urgent-first — that ranking is what the app is for,
     * and it uses `urgency` rather than `Severity`'s declaration order, which put stale and
     * never-fetched cards above exhausted ones.
     *
     * Once someone has arranged their accounts by hand, that order wins and nothing re-ranks
     * it. The repository already returns rows `ORDER BY sortOrder`, so the manual case is the
     * list exactly as it arrived.
     */
    fun orderedAccounts(nowMs: Long): List<AccountUsage> =
        if (settings.accountsManuallyOrdered) {
            accounts
        } else {
            accounts.sortedBy {
                it.snapshot?.severityAt(nowMs, staleAfterMs)?.urgency ?: Int.MAX_VALUE
            }
        }

    fun healthyCountAt(nowMs: Long): Int =
        accounts.count { it.snapshot?.severityAt(nowMs, staleAfterMs) == Severity.HEALTHY }

    /** Newest data wins for the "last updated" line — it describes the screen as a whole. */
    val lastUpdated: Long?
        get() = accounts.mapNotNull { it.snapshot?.fetchedAt }.maxOrNull()

    /**
     * The soonest rollover still ahead.
     *
     * Ahead is the point. Taking the minimum over every window's reset instant meant that
     * once ONE window's reset had passed — and a snapshot keeps that instant until the next
     * fetch replaces it — the minimum was anchored to the past and the summary card read
     * "Next reset: now" for hours while the real next rollover was never shown. The Resets
     * screen already drops passed instants; this now matches it.
     */
    fun nextResetAt(nowMs: Long): Long? =
        accounts.flatMap { it.snapshot?.windows.orEmpty() }
            .mapNotNull { it.resetAt }
            .filter { it > nowMs }
            .minOrNull()

    fun overallSeverityAt(nowMs: Long): Severity =
        accounts.mapNotNull { it.snapshot?.severityAt(nowMs, staleAfterMs) }.maxOrNull()
            ?: Severity.STALE

    /** The single most-depleted window anywhere — what the summary card leads with. */
    /**
     * The soonest rollover still ahead for ONE account — the one the summary card leads with.
     *
     * The card puts "Next reset" beside the most-depleted window, separated by a hairline, and
     * a fleet-wide minimum there read "Next reset 12m | 0% Weekly left": an invitation to
     * believe the exhausted weekly limit returns in twelve minutes, when the twelve minutes
     * belonged to a healthy account's five-hour window. The widget fixed exactly this pairing
     * by scoping its reset to the leading account; the card now does the same.
     */
    fun nextResetAt(nowMs: Long, accountId: String): Long? =
        accounts.firstOrNull { it.account.localId == accountId }
            ?.snapshot?.windows.orEmpty()
            .mapNotNull { it.resetAt }
            .filter { it > nowMs }
            .minOrNull()

    val mostCritical: Pair<AccountUsage, UsageWindow>?
        get() = accounts.mapNotNull { usage ->
            usage.snapshot?.mostCritical?.let { usage to it }
        }.minByOrNull { it.second.remainingPercent ?: Double.MAX_VALUE }

    /** Chronological reset timeline; past events are dropped by the screen. */
    fun upcomingResets(nowMs: Long): List<UpcomingReset> =
        accounts.flatMap { usage ->
            usage.snapshot?.windows.orEmpty().mapNotNull { window ->
                val resetAt = window.resetAt ?: return@mapNotNull null
                if (resetAt <= nowMs) return@mapNotNull null
                UpcomingReset(
                    accountId = usage.account.localId,
                    accountLabel = usage.account.label,
                    provider = usage.account.provider,
                    windowId = window.id,
                    windowLabel = window.label,
                    resetAt = resetAt,
                    severity = window.severity,
                )
            }
        }.sortedBy { it.resetAt }
}

/**
 * Shared state for every screen.
 *
 * One view model rather than four: all four tabs project the same cache, and splitting it
 * would mean four collectors over the same Flow and four ways for them to disagree about
 * refresh state.
 */
class UsageViewModel(
    private val container: AppContainer,
    private val appContext: Context,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val transientMessage = MutableStateFlow<String?>(null)

    val state: StateFlow<UsageUiState> = combine(
        container.repository.observeAccountUsage(),
        container.settingsStore.settings,
        refreshing,
        transientMessage,
    ) { accounts, settings, isRefreshing, message ->
        UsageUiState(accounts, settings, isRefreshing, message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageUiState())

    private val _resetCreditInFlight = MutableStateFlow<String?>(null)

    /** Account id whose reset credit is currently being spent, for a per-card spinner. */
    val resetCreditInFlight: StateFlow<String?> = _resetCreditInFlight.asStateFlow()

    /**
     * Persists the order the user dragged the cards into.
     *
     * Latches `accountsManuallyOrdered` at the same time, in the same coroutine: writing the
     * order without the flag would store an arrangement the overview then ignores, which reads
     * as the drag having done nothing.
     */
    fun reorderAccounts(idsInOrder: List<String>) {
        viewModelScope.launch {
            container.repository.reorderAccounts(idsInOrder)
            container.settingsStore.setAccountsManuallyOrdered(true)
        }
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                val outcomes = container.syncEngine.syncAll()
                val failures = outcomes.count { !it.success }
                transientMessage.value = when {
                    outcomes.isEmpty() -> null
                    failures == 0 -> null
                    failures == outcomes.size -> "Refresh failed"
                    else -> "$failures of ${outcomes.size} accounts failed to refresh"
                }
                container.publishAfterSync(appContext)
            } finally {
                refreshing.value = false
            }
        }
    }

    fun refreshAccount(accountId: String) {
        viewModelScope.launch {
            refreshing.value = true
            try {
                container.syncEngine.syncAccount(accountId)
                container.publishAfterSync(appContext)
            } finally {
                refreshing.value = false
            }
        }
    }

    /**
     * Spends one Codex reset credit, then immediately re-syncs.
     *
     * Only ever reached from a confirmed dialog. The re-sync is what makes the result visible:
     * both the quota windows and the remaining credit count change server-side.
     */
    fun consumeResetCredit(accountId: String) {
        if (_resetCreditInFlight.value != null) return
        viewModelScope.launch {
            _resetCreditInFlight.value = accountId
            try {
                val account = container.repository.account(accountId) ?: return@launch
                val provider = container.providerRegistry.forId(account.provider) ?: return@launch
                if (!provider.supportsResetCredits) return@launch

                val credentials = container.syncEngine.validCredentials(account)
                provider.consumeResetCredit(account, credentials)

                // The spend has HAPPENED at the provider from here on. The follow-up sync is
                // what makes it visible, and it can fail on its own — and when it does, the
                // message must not say "applied" over a card still showing the pre-spend quota
                // and an unspent-looking credit, or the user will try again.
                val outcome = container.syncEngine.syncAccount(account)
                container.publishAfterSync(appContext)
                transientMessage.value = if (outcome.success) {
                    "Limit reset applied"
                } else {
                    "Reset requested — the new limit will show on the next refresh"
                }
            } catch (e: CancellationException) {
                // Not a failure, and not ours to swallow: the credit may already be spent, and
                // the next sync will show whatever the provider did.
                throw e
            } catch (e: ProviderException) {
                transientMessage.value = e.userMessage()
            } catch (e: Exception) {
                transientMessage.value = "Could not apply the reset"
            } finally {
                _resetCreditInFlight.value = null
            }
        }
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            // Not cancellable once begun. The screen that owns this scope is the one the user
            // leaves right after tapping "remove", and a cancellation between the row's
            // deletion and the widget refresh left the home screen showing the account that
            // was just removed — widgets do not observe the database, they are told.
            withContext(NonCancellable) {
                val account = container.repository.account(accountId) ?: return@withContext
                // Under the same lock a refresh holds, which this did not take before. A
                // refresh checks that the reference still exists and then writes the rotated
                // pair back; a delete landing between those two steps was undone by the write,
                // and the account the user removed kept a live credential in the keystore with
                // no row naming it. Each store call is atomic on its own — it is the SEQUENCE
                // that has to be serialised, so sign-out and refresh share one lock.
                container.syncEngine.withCredentialLock(account.credentialReference) {
                    // Credentials go first: if this crashed between the two, an orphaned
                    // account row is recoverable, an orphaned credential is not visible to
                    // the user at all.
                    container.credentialStore.delete(account.credentialReference)
                    container.repository.deleteAccount(accountId)
                }
                // A widget pinned to this account would otherwise render the empty snapshot
                // for ever; dropping its config returns it to the automatic scope.
                container.widgetConfigDao.deleteForAccount(accountId)
                WidgetUpdater.refreshAll(appContext)
            }
        }
    }

    fun setSyncInterval(minutes: Int) {
        viewModelScope.launch {
            container.settingsStore.setSyncInterval(minutes)
            // Writing the preference alone changed nothing: schedulePeriodic is otherwise
            // called once at process start, so a new interval only took effect after the app
            // was killed. UPDATE keeps the existing schedule rather than firing immediately.
            SyncWorker.schedulePeriodic(appContext, minutes)
            // Staleness is derived from this interval at render time, so a widget composed
            // under the old interval kept calling a 45-minute-old snapshot stale for hours
            // after the app's own screen had turned it green.
            WidgetUpdater.refreshAll(appContext)
        }
    }

    /** Silences one account, or lets it speak again. */
    fun setAccountNotifications(accountId: String, enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.setAccountNotifications(accountId, enabled)
        }
    }

    fun setShowSubscriptionTier(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setShowSubscriptionTier(enabled) }
    }

    fun setShowRenewalTime(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setShowRenewalTime(enabled) }
    }

    fun setNotifyBelow20(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setNotifyBelow20Percent(enabled) }
    }

    fun setNotifyBelow10(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setNotifyBelow10Percent(enabled) }
    }

    fun setNotifyResetApproaching(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setNotifyOnResetApproaching(enabled) }
    }

    fun setNotifyCreditExpiring(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setNotifyOnResetCreditExpiring(enabled) }
    }

    fun setNotifyExhausted(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setNotifyOnExhausted(enabled) }
    }

    fun setNotifyResetCredit(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setNotifyOnResetCredit(enabled) }
    }

    fun setNotifyAuthExpired(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setNotifyOnAuthExpired(enabled) }
    }

    fun clearMessage() {
        transientMessage.value = null
    }

    class Factory(
        private val container: AppContainer,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UsageViewModel(container, appContext) as T
    }
}
