package com.usagelimits.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User-configurable preferences. */
data class AppSettings(
    val syncIntervalMinutes: Int = DEFAULT_SYNC_INTERVAL_MINUTES,
    /** Warn once when a limit first drops below 20 % remaining. */
    val notifyBelow20Percent: Boolean = true,
    /** Escalate once when it drops below 10 %, even if the 20 % warning already fired. */
    val notifyBelow10Percent: Boolean = true,
    val notifyOnExhausted: Boolean = true,
    val notifyOnResetCreditAvailable: Boolean = true,
    val notifyOnAuthExpired: Boolean = true,
    /**
     * Off by default: an approaching reset is usually information rather than something to act
     * on, and a limit that resets nightly would otherwise notify every night.
     */
    val notifyOnResetApproaching: Boolean = false,
    val resetApproachingMinutes: Int = DEFAULT_RESET_LEAD_MINUTES,
    val notifyOnResetCreditExpiring: Boolean = true,
    val resetCreditExpiryLeadMinutes: Int = DEFAULT_CREDIT_LEAD_MINUTES,
    /**
     * Whether the overview follows the order the user dragged the accounts into.
     *
     * False until they drag one. Until then the list is ranked by urgency, which is what the
     * app is for — but once someone has arranged their accounts deliberately, rearranging them
     * again on every sync is the app overruling them.
     */
    val accountsManuallyOrdered: Boolean = false,
    /** Show the plan tier — Plus, Pro, Max — beside the provider name. */
    val showSubscriptionTier: Boolean = true,
    /**
     * Show when the long allowance renews, on the overview only.
     *
     * Off by default, and deliberately not in the widgets: the widget's job is the number you
     * are about to run out of, and a second date competing with the next reset is the kind of
     * detail that makes a glanceable tile unglanceable.
     */
    val showRenewalTime: Boolean = false,
    /**
     * Accounts that should never notify, by local id.
     *
     * A set of ids rather than a column on the account, because it is a preference about an
     * account and not a fact about it — and adding a column costs a migration. A stale id left
     * behind by a deleted account is inert: ids are UUIDs, so re-adding the same provider
     * account mints a new one and cannot inherit an old mute.
     */
    val mutedAccountIds: Set<String> = emptySet(),
) {
    companion object {
        /**
         * 15 minutes is the floor WorkManager enforces for periodic work, and also about the
         * fastest cadence that stays polite to the providers.
         */
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 30
        const val MIN_SYNC_INTERVAL_MINUTES = 15
        const val DEFAULT_RESET_LEAD_MINUTES = 30

        /** A day: long enough to act on, short enough not to be background noise. */
        const val DEFAULT_CREDIT_LEAD_MINUTES = 1_440
    }
}

private val Context.dataStore by preferencesDataStore(name = "usage_limits_settings")

/**
 * Reads and writes [AppSettings].
 *
 * DataStore rather than SharedPreferences because reads are a Flow the UI can collect
 * directly, and writes are transactional. Nothing sensitive is stored here — credentials live
 * in the Keystore-backed store.
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = dataStore.data.map { it.toSettings() }

    suspend fun setSyncInterval(minutes: Int) = edit {
        it[Keys.SYNC_INTERVAL] = minutes.coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES)
    }

    suspend fun setNotifyBelow20Percent(enabled: Boolean) = edit {
        it[Keys.NOTIFY_BELOW_20] = enabled
    }

    suspend fun setNotifyBelow10Percent(enabled: Boolean) = edit {
        it[Keys.NOTIFY_BELOW_10] = enabled
    }

    suspend fun setNotifyOnExhausted(enabled: Boolean) = edit { it[Keys.NOTIFY_EXHAUSTED] = enabled }

    suspend fun setNotifyOnResetCredit(enabled: Boolean) = edit { it[Keys.NOTIFY_CREDIT] = enabled }

    suspend fun setNotifyOnAuthExpired(enabled: Boolean) = edit { it[Keys.NOTIFY_AUTH] = enabled }

    suspend fun setNotifyOnResetApproaching(enabled: Boolean) = edit {
        it[Keys.NOTIFY_RESET_APPROACHING] = enabled
    }

    suspend fun setResetApproachingMinutes(minutes: Int) = edit {
        it[Keys.RESET_LEAD_MINUTES] = minutes.coerceIn(5, 24 * 60)
    }

    suspend fun setNotifyOnResetCreditExpiring(enabled: Boolean) = edit {
        it[Keys.NOTIFY_CREDIT_EXPIRING] = enabled
    }

    suspend fun setResetCreditExpiryLeadMinutes(minutes: Int) = edit {
        it[Keys.CREDIT_LEAD_MINUTES] = minutes.coerceIn(60, 7 * 24 * 60)
    }

    /**
     * Latched the first time an account is dragged, and never cleared on its own.
     *
     * Once someone has arranged their accounts deliberately, re-ranking them by urgency on the
     * next sync would be the app overruling a choice they made by hand.
     */
    suspend fun setAccountsManuallyOrdered(ordered: Boolean) = edit {
        it[Keys.ACCOUNTS_MANUAL_ORDER] = ordered
    }

    suspend fun setShowSubscriptionTier(show: Boolean) = edit { it[Keys.SHOW_TIER] = show }

    suspend fun setShowRenewalTime(show: Boolean) = edit { it[Keys.SHOW_RENEWAL] = show }

    /** Silences one account, or lets it speak again. */
    suspend fun setAccountNotifications(accountId: String, enabled: Boolean) = edit { prefs ->
        val current = prefs[Keys.MUTED_ACCOUNTS] ?: emptySet()
        prefs[Keys.MUTED_ACCOUNTS] =
            if (enabled) current - accountId else current + accountId
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun Preferences.toSettings() = AppSettings(
        syncIntervalMinutes = this[Keys.SYNC_INTERVAL] ?: AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES,
        // The single low-usage switch became two tiers. Someone who turned the old one off
        // meant "stop telling me about low quota", so their opt-out carries to both rather
        // than silently re-enabling alerts on upgrade.
        notifyBelow20Percent = this[Keys.NOTIFY_BELOW_20] ?: this[Keys.NOTIFY_LOW] ?: true,
        notifyBelow10Percent = this[Keys.NOTIFY_BELOW_10] ?: this[Keys.NOTIFY_LOW] ?: true,
        notifyOnExhausted = this[Keys.NOTIFY_EXHAUSTED] ?: true,
        notifyOnResetCreditAvailable = this[Keys.NOTIFY_CREDIT] ?: true,
        notifyOnAuthExpired = this[Keys.NOTIFY_AUTH] ?: true,
        notifyOnResetApproaching = this[Keys.NOTIFY_RESET_APPROACHING] ?: false,
        resetApproachingMinutes =
            this[Keys.RESET_LEAD_MINUTES] ?: AppSettings.DEFAULT_RESET_LEAD_MINUTES,
        notifyOnResetCreditExpiring = this[Keys.NOTIFY_CREDIT_EXPIRING] ?: true,
        resetCreditExpiryLeadMinutes =
            this[Keys.CREDIT_LEAD_MINUTES] ?: AppSettings.DEFAULT_CREDIT_LEAD_MINUTES,
        accountsManuallyOrdered = this[Keys.ACCOUNTS_MANUAL_ORDER] ?: false,
        showSubscriptionTier = this[Keys.SHOW_TIER] ?: true,
        showRenewalTime = this[Keys.SHOW_RENEWAL] ?: false,
        mutedAccountIds = this[Keys.MUTED_ACCOUNTS] ?: emptySet(),
    )

    private object Keys {
        val SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
        /** Read only to carry a pre-tier opt-out forward; nothing writes it any more. */
        val NOTIFY_LOW = booleanPreferencesKey("notify_low_usage")
        val NOTIFY_BELOW_20 = booleanPreferencesKey("notify_below_20_percent")
        val NOTIFY_BELOW_10 = booleanPreferencesKey("notify_below_10_percent")
        val NOTIFY_EXHAUSTED = booleanPreferencesKey("notify_exhausted")
        val NOTIFY_CREDIT = booleanPreferencesKey("notify_reset_credit")
        val NOTIFY_AUTH = booleanPreferencesKey("notify_auth_expired")
        val NOTIFY_RESET_APPROACHING = booleanPreferencesKey("notify_reset_approaching")
        val RESET_LEAD_MINUTES = intPreferencesKey("reset_approaching_minutes")
        val NOTIFY_CREDIT_EXPIRING = booleanPreferencesKey("notify_reset_credit_expiring")
        val CREDIT_LEAD_MINUTES = intPreferencesKey("reset_credit_expiry_lead_minutes")
        val ACCOUNTS_MANUAL_ORDER = booleanPreferencesKey("accounts_manually_ordered")
        val SHOW_TIER = booleanPreferencesKey("show_subscription_tier")
        val SHOW_RENEWAL = booleanPreferencesKey("show_renewal_time")
        val MUTED_ACCOUNTS = stringSetPreferencesKey("muted_account_ids")
    }
}
