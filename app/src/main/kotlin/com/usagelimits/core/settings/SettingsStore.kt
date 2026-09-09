package com.usagelimits.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User-configurable preferences. */
data class AppSettings(
    val syncIntervalMinutes: Int = DEFAULT_SYNC_INTERVAL_MINUTES,
    val notifyOnLowUsage: Boolean = true,
    val notifyOnExhausted: Boolean = true,
    val notifyOnResetCreditAvailable: Boolean = true,
    val notifyOnAuthExpired: Boolean = true,
    /** Remaining-percent threshold below which a low-usage notification fires. */
    val lowUsageThreshold: Int = DEFAULT_LOW_THRESHOLD,
) {
    companion object {
        /**
         * 15 minutes is the floor WorkManager enforces for periodic work, and also about the
         * fastest cadence that stays polite to the providers.
         */
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 30
        const val MIN_SYNC_INTERVAL_MINUTES = 15
        const val DEFAULT_LOW_THRESHOLD = 20
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

    suspend fun setNotifyOnLowUsage(enabled: Boolean) = edit { it[Keys.NOTIFY_LOW] = enabled }

    suspend fun setNotifyOnExhausted(enabled: Boolean) = edit { it[Keys.NOTIFY_EXHAUSTED] = enabled }

    suspend fun setNotifyOnResetCredit(enabled: Boolean) = edit { it[Keys.NOTIFY_CREDIT] = enabled }

    suspend fun setNotifyOnAuthExpired(enabled: Boolean) = edit { it[Keys.NOTIFY_AUTH] = enabled }

    suspend fun setLowUsageThreshold(percent: Int) = edit {
        it[Keys.LOW_THRESHOLD] = percent.coerceIn(1, 99)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun Preferences.toSettings() = AppSettings(
        syncIntervalMinutes = this[Keys.SYNC_INTERVAL] ?: AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES,
        notifyOnLowUsage = this[Keys.NOTIFY_LOW] ?: true,
        notifyOnExhausted = this[Keys.NOTIFY_EXHAUSTED] ?: true,
        notifyOnResetCreditAvailable = this[Keys.NOTIFY_CREDIT] ?: true,
        notifyOnAuthExpired = this[Keys.NOTIFY_AUTH] ?: true,
        lowUsageThreshold = this[Keys.LOW_THRESHOLD] ?: AppSettings.DEFAULT_LOW_THRESHOLD,
    )

    private object Keys {
        val SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
        val NOTIFY_LOW = booleanPreferencesKey("notify_low_usage")
        val NOTIFY_EXHAUSTED = booleanPreferencesKey("notify_exhausted")
        val NOTIFY_CREDIT = booleanPreferencesKey("notify_reset_credit")
        val NOTIFY_AUTH = booleanPreferencesKey("notify_auth_expired")
        val LOW_THRESHOLD = intPreferencesKey("low_usage_threshold")
    }
}
