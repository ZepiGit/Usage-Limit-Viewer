package com.usagelimits.widget

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.Severity
import kotlinx.coroutines.flow.first

/**
 * Repaints the widgets at the next moment their PRESENTATION changes on its own.
 *
 * The sync worker fetches new data and network-constrains itself accordingly, but two visible
 * transitions do not need the network and were not covered by any event: a cached snapshot
 * crossing its staleness threshold, and a cached reset instant arriving. Neither moves the
 * Room cache, so the data-driven observe flow never re-emits, and a healthy-looking tile
 * stayed healthy-looking for as long as nothing else happened to run — exactly offline, which
 * is when "as of when?" matters most. This worker exists to cross those boundaries: it reads
 * only the local cache, repaints, and schedules the next boundary. No network constraint, no
 * credentials, no polling loop, no exact alarm — WorkManager may defer it, which is why the
 * views also age every timestamp themselves when they do render.
 */
class WidgetPresentationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        WidgetUpdater.refreshAll(applicationContext)
        // One boundary repainted; the next one needs its own reservation.
        scheduleNext(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "widget_presentation_boundary"

        /** Never wake the device more often than this for a presentation boundary. */
        private const val MIN_DELAY_MS = 60_000L

        /** Beyond a day the routine sync will repaint long before anything ages further. */
        private const val MAX_DELAY_MS = 24L * 60 * 60 * 1000

        /**
         * The next instant at which some cached presentation is wrong rather than merely old:
         * the soonest staleness threshold or future reset across the cache. Pure, so the
         * arithmetic is testable without WorkManager or a database.
         */
        fun nextPresentationBoundary(
            fetchedAtMs: List<Long>,
            resetAtMs: List<Long>,
            staleAfterMs: Long,
            nowMs: Long,
        ): Long? = (fetchedAtMs.map { it + staleAfterMs } + resetAtMs).filter { it > nowMs }.minOrNull()

        /**
         * (Re)computes the boundary across every cached account and reserves one repaint for
         * it. Coalesced under one unique name, so widgets, syncs and config saves all agree on
         * a single next wake; REPLACE keeps the soonest request rather than stacking them.
         */
        suspend fun scheduleNext(context: Context) {
            val app = context.applicationContext as? UsageLimitsApp ?: return
            val container = app.container
            val accounts = container.repository.observeAccountUsage().first()
            val settings = container.settingsStore.settings.first()
            val now = System.currentTimeMillis()
            val staleAfter = Severity.staleAfterMs(settings.syncIntervalMinutes)

            val boundary = nextPresentationBoundary(
                fetchedAtMs = accounts.mapNotNull { it.snapshot?.fetchedAt },
                resetAtMs = accounts.flatMap { it.snapshot?.windows.orEmpty().mapNotNull { w -> w.resetAt } },
                staleAfterMs = staleAfter,
                nowMs = now,
            )

            val workManager = WorkManager.getInstance(context)
            if (boundary == null) {
                // Nothing cached ages on its own — no accounts, or nothing fetched. The next
                // sync will reschedule this when there is.
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val delay = (boundary - now).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
            workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetPresentationWorker>()
                    .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build(),
            )
        }
    }
}
