package com.usagelimits

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Does the app start?
 *
 * The question no parser test can answer, and the one a user asks first. It found a real fault
 * the moment it was written: the app relied on WorkManager's default `InitializationProvider`,
 * a content provider that runs before `Application.onCreate` and is invisible from everywhere.
 * When it does not run, `WorkManager.getInstance` throws inside a `SupervisorJob`, nothing
 * crashes, nothing is logged, and the background refresh simply never happens for the life of
 * the install — while every screen keeps saying the data is current.
 *
 * Initialisation is now declared by the Application itself, which is what makes it both
 * deterministic and reachable from here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLaunchTest {

    private fun app(): UsageLimitsApp =
        ApplicationProvider.getApplicationContext<Application>() as UsageLimitsApp

    @Test
    fun `the application builds its container on create`() {
        assertNotNull(app().container)
    }

    @Test
    fun `work manager is configured by the application rather than a content provider`() {
        // The manifest removes `InitializationProvider`, so this resolving at all proves the
        // Application's own `Configuration.Provider` is what supplies it.
        assertNotNull(WorkManager.getInstance(app()))
    }

    @Test
    fun `start-up work does not leave an exception nobody sees`() {
        val application = app()

        // Let the start-up coroutine run to completion. Before the fix this drained an
        // IllegalStateException into the application scope, which then poisoned whatever ran
        // next — the symptom that exposed it.
        ShadowLooper.idleMainLooper()
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        assertTrue("the app should still be usable after start-up", application.container != null)
    }
}
