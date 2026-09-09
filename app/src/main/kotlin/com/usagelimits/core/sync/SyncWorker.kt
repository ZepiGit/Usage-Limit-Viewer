package com.usagelimits.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.widget.WidgetUpdater
import java.util.concurrent.TimeUnit

/**
 * Background usage refresh.
 *
 * Runs the same [SyncEngine] the UI uses, then pushes the refreshed cache into the widgets.
 * A pass that fails for every account still returns success: [SyncEngine] already recorded
 * the per-account error and the next scheduled pass will retry, so asking WorkManager to
 * retry as well would double the request rate against a provider that is already failing.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as UsageLimitsApp).container

        val outcomes = container.syncEngine.syncAll()

        // Widgets read the cache, so they only need waking once the cache has moved.
        WidgetUpdater.refreshAll(applicationContext)

        NotificationPublisher(applicationContext, container.settingsStore)
            .publishFor(container.repository.accountUsageOnce())

        return if (outcomes.isEmpty() || outcomes.any { it.success }) {
            Result.success()
        } else {
            // Every account failed — likely a shared cause (offline, or the device was
            // asleep). Let WorkManager back off and retry rather than waiting a full period.
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "usage_sync_periodic"
        private const val ONE_SHOT_WORK_NAME = "usage_sync_now"

        /**
         * (Re)schedules the periodic pass.
         *
         * [ExistingPeriodicWorkPolicy.UPDATE] keeps the existing schedule when only the
         * interval changed, so changing the setting does not reset the timer and cause an
         * immediate extra sync.
         */
        fun schedulePeriodic(context: Context, intervalMinutes: Int) {
            val interval = intervalMinutes.coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES)

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                interval.toLong(),
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Fires an immediate sync — app start, pull-to-refresh, a widget's refresh tap. */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                androidx.work.ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }
}
