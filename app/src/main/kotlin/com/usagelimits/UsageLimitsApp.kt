package com.usagelimits

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UsageLimitsApp : Application(), Configuration.Provider {

    /**
     * WorkManager's configuration, supplied on demand.
     *
     * The manifest removes the default `InitializationProvider`, so this is the only path by
     * which WorkManager is configured — deterministic and ordered after `onCreate`, rather than
     * a content provider that runs before the app exists and fails invisibly when it does not.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    lateinit var container: AppContainer
        private set

    /**
     * Application-lifetime scope for start-up work.
     *
     * SupervisorJob so a failure in one start-up task cannot cancel the others.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The start-up work, so a test can wait for it instead of sleeping and hoping.
     *
     * It matters beyond tidiness: this job opens WorkManager's database on a background
     * thread, and a test that ends while it is still running leaves it racing the next test
     * class's set-up in the same JVM. Robolectric's native runtime unpacks its fonts through a
     * process-wide zip filesystem, and two sandboxes doing that at once fail the second one
     * with `FileSystemAlreadyExistsException` — after which every SQLite call in that sandbox
     * throws `UnsatisfiedLinkError`, in tests that never touched WorkManager.
     */
    @Volatile
    var startupJob: kotlinx.coroutines.Job? = null
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        startupJob = appScope.launch {
            val interval = runCatching { container.settingsStore.settings.first().syncIntervalMinutes }
                .getOrDefault(AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES)

            // Contained, and logged rather than swallowed. This runs in a SupervisorJob, so a
            // throw here does not crash the app — it simply means the background refresh is
            // never scheduled, silently, for the life of the install. That is the worse failure
            // of the two, and it deserves a line in the log rather than an uncaught exception
            // nobody sees.
            runCatching {
                SyncWorker.schedulePeriodic(this@UsageLimitsApp, interval)

                // Refresh on launch so the first screen is current rather than however stale the
                // last background pass left it.
                SyncWorker.syncNow(this@UsageLimitsApp)
            }.onFailure { error ->
                Log.w(TAG, "background sync could not be scheduled", error)
            }
        }
    }

    private companion object {
        const val TAG = "UsageLimitsApp"
    }
}
