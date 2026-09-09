package com.usagelimits.feature

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.di.AppContainer
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
import kotlinx.coroutines.launch

/** A reset event on the Resets timeline. */
data class UpcomingReset(
    val accountId: String,
    val accountLabel: String,
    val provider: ProviderId,
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

    fun healthyCountAt(nowMs: Long): Int =
        accounts.count { it.snapshot?.severityAt(nowMs) == Severity.HEALTHY }

    /** Newest data wins for the "last updated" line — it describes the screen as a whole. */
    val lastUpdated: Long?
        get() = accounts.mapNotNull { it.snapshot?.fetchedAt }.maxOrNull()

    val nextReset: Long?
        get() = accounts.mapNotNull { it.snapshot?.nextReset }.minOrNull()

    fun overallSeverityAt(nowMs: Long): Severity =
        accounts.mapNotNull { it.snapshot?.severityAt(nowMs) }.maxOrNull() ?: Severity.STALE

    /** The single most-depleted window anywhere — what the summary card leads with. */
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
                WidgetUpdater.refreshAll(appContext)
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
                WidgetUpdater.refreshAll(appContext)
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

                container.syncEngine.syncAccount(account)
                WidgetUpdater.refreshAll(appContext)
                transientMessage.value = "Limit reset applied"
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
            val account = container.repository.account(accountId) ?: return@launch
            // Credentials go first: if this crashed between the two, an orphaned account row
            // is recoverable, an orphaned credential is not visible to the user at all.
            container.credentialStore.delete(account.credentialReference)
            container.repository.deleteAccount(accountId)
            WidgetUpdater.refreshAll(appContext)
        }
    }

    fun setSyncInterval(minutes: Int) {
        viewModelScope.launch {
            container.settingsStore.setSyncInterval(minutes)
            // Writing the preference alone changed nothing: schedulePeriodic is otherwise
            // called once at process start, so a new interval only took effect after the app
            // was killed. UPDATE keeps the existing schedule rather than firing immediately.
            SyncWorker.schedulePeriodic(appContext, minutes)
        }
    }

    fun setNotifyLowUsage(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setNotifyOnLowUsage(enabled) }
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
