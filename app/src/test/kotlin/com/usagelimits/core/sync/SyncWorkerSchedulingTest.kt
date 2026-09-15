package com.usagelimits.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How the sync triggers land in WorkManager.
 *
 * The clock-driven trigger — the system's widget tick, the presentation repaint — must share
 * the one-shot's unique name, so a tick a moment after the user tapped refresh does not queue
 * a second pass, and a tap a moment after a tick does not either.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWorkerSchedulingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    /**
     * Nothing this class opened may still be running when the next class sets up: an open
     * WorkManager database races the native runtime set-up of the following sandbox, and the
     * symptom lands in whichever test comes next. See UsageLimitsApp.startupJob.
     */
    @After
    fun closeWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun oneShots(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncWorker.ONE_SHOT_WORK_NAME).get()

    @Test
    fun `a widget tick enqueues one conditional sync, waiting for the network`() {
        SyncWorker.syncIfCacheAged(context)

        val infos = oneShots()
        assertEquals(1, infos.size)
        // Network-constrained, so it waits rather than failing offline.
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun `a tap and a tick collapse into one pass`() {
        SyncWorker.syncNow(context)
        SyncWorker.syncIfCacheAged(context)
        SyncWorker.syncNow(context)

        assertEquals(1, oneShots().size)
    }
}
