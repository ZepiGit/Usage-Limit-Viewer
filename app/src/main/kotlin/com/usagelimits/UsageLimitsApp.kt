package com.usagelimits

import android.app.Application
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UsageLimitsApp : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * Application-lifetime scope for start-up work.
     *
     * SupervisorJob so a failure in one start-up task cannot cancel the others.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        appScope.launch {
            val interval = runCatching { container.settingsStore.settings.first().syncIntervalMinutes }
                .getOrDefault(AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES)

            SyncWorker.schedulePeriodic(this@UsageLimitsApp, interval)

            // Refresh on launch so the first screen is current rather than however stale the
            // last background pass left it.
            SyncWorker.syncNow(this@UsageLimitsApp)
        }
    }
}
