package com.usagelimits.core.sync

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.settings.AppSettings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background usage refresh.
 *
 * Runs the same [SyncEngine] the UI uses, then pushes the refreshed cache into the widgets.
 * A pass where *some* accounts succeeded returns success — [SyncEngine] already recorded each
 * per-account error, and asking WorkManager to retry would double the request rate against a
 * provider that is already failing. A periodic pass where *every* account failed returns retry,
 * since that points at a shared cause (offline, or the device asleep) worth backing off and
 * retrying sooner than the next period.
 *
 * A one-shot pass never asks for a retry. It is enqueued under one unique name with
 * [ExistingWorkPolicy.KEEP], so a retrying one-shot sitting in WorkManager's backoff queue
 * swallowed every later trigger — app start, `onResume`, the widget's refresh button — for as
 * long as the backoff lasted. The user tapped refresh and nothing happened. The failure is
 * already recorded against each account; the next trigger should simply run.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as UsageLimitsApp).container
        val now = System.currentTimeMillis()

        // A widget-driven trigger only fetches when the cache has actually aged. The system
        // asks every placed widget to update every `updatePeriodMillis`, which is also the
        // moment a widget was just placed or resized — none of which should cost a provider
        // round trip when a pass landed a minute ago.
        if (inputData.getBoolean(KEY_ONLY_IF_AGED, false)) {
            val accounts = container.repository.accountUsageOnce()
            val interval = container.settingsStore.settings.first().syncIntervalMinutes
            if (!cacheNeedsSync(accounts, interval, now)) return Result.success()
        }

        container.settingsStore.recordSyncAttempt(now)

        val outcomes = container.syncEngine.syncAll()

        container.settingsStore.recordSyncFinished(System.currentTimeMillis())

        container.publishAfterSync(applicationContext)

        // New cache, new presentation boundaries: the repaint that flips a tile to "stale" or
        // crosses a reset is scheduled from the freshly written cache, without the network.
        com.usagelimits.widget.WidgetPresentationWorker.scheduleNext(applicationContext)

        return resultFor(outcomes, periodic = inputData.getBoolean(KEY_PERIODIC, false))
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "usage_sync_periodic"
        internal const val ONE_SHOT_WORK_NAME = "usage_sync_now"

        /** Set on the periodic request only: the one pass allowed to ask WorkManager for a retry. */
        internal const val KEY_PERIODIC = "periodic"

        /** Set by widget-driven triggers: fetch only when the cache is older than the interval. */
        internal const val KEY_ONLY_IF_AGED = "onlyIfAged"

        /**
         * How long a periodic pass waits after every account failed.
         *
         * Linear and short. WorkManager's default is exponential from 30 seconds, and a run of
         * all-account failures — a captive portal, a DNS outage — grew that into hours, during
         * which the widgets aged with nothing scheduled to move them.
         */
        private const val RETRY_BACKOFF_MINUTES = 10L

        /**
         * Which result a pass reports, given what happened to each account.
         *
         * Pure, so the policy is testable: only a periodic pass in which nothing succeeded asks
         * for a retry.
         */
        fun resultFor(outcomes: List<SyncOutcome>, periodic: Boolean): Result = when {
            outcomes.isEmpty() || outcomes.any { it.success } -> Result.success()
            periodic -> Result.retry()
            else -> Result.failure()
        }

        /**
         * Whether the cache is old enough — or overtaken by a reset — to justify a fetch.
         *
         * Two conditions, either sufficient. The newest snapshot is older than the interval the
         * user chose, so a scheduled pass has been missed. Or a window's reset instant has
         * passed since its snapshot was taken, so the cached number is wrong rather than merely
         * old: the provider has moved to a fresh window and the tile still shows the spent one.
         * A snapshot fetched AFTER the reset is not affected — the provider had already
         * reported the new window by then.
         */
        fun cacheNeedsSync(accounts: List<AccountUsage>, syncIntervalMinutes: Int, nowMs: Long): Boolean {
            if (accounts.isEmpty()) return false
            val snapshots = accounts.mapNotNull { it.snapshot }
            if (snapshots.isEmpty()) return true
            val intervalMs = syncIntervalMinutes.coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES) * 60_000L
            val aged = snapshots.maxOf { it.fetchedAt } + intervalMs <= nowMs
            val overtaken = snapshots.any { snapshot ->
                snapshot.windows.any { window ->
                    val resetAt = window.resetAt ?: return@any false
                    resetAt <= nowMs && snapshot.fetchedAt < resetAt
                }
            }
            return aged || overtaken
        }

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
                .setConstraints(connected())
                .setInputData(workDataOf(KEY_PERIODIC to true))
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Fires an immediate sync — app start, pull-to-refresh, a widget's refresh tap. */
        fun syncNow(context: Context) = enqueueOneShot(context, onlyIfAged = false)

        /**
         * Fires a sync only if the cache has aged past the interval or been overtaken by a
         * reset — the trigger for anything that runs on a clock rather than a user's tap: the
         * system's periodic widget update, and the cache-only presentation repaint.
         */
        fun syncIfCacheAged(context: Context) = enqueueOneShot(context, onlyIfAged = true)

        private fun enqueueOneShot(context: Context, onlyIfAged: Boolean) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(connected())
                .setInputData(workDataOf(KEY_ONLY_IF_AGED to onlyIfAged))
                .apply {
                    // Expedited so a refresh the user just asked for — or one the system just
                    // asked for on behalf of a widget — does not wait for the next Doze
                    // maintenance window. Below Android 12 an expedited CoroutineWorker must
                    // supply a foreground notification, which a quota check does not warrant;
                    // there the request stays ordinary.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()

            // KEEP: an impatient user tapping five times gets one pass. Safe now that a
            // one-shot never lingers in a retry backoff — see the class comment.
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private fun connected() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }
}
