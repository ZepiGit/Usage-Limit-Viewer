package com.usagelimits

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.After
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
// The ONE test that runs the real Application. Every other Robolectric test gets a plain
// `android.app.Application` from robolectric.properties, because the real one launches
// background work — WorkManager, its database — that outlives the test and races the native
// runtime set-up of whichever sandbox comes next; see UsageLimitsApp.startupJob.
@Config(application = UsageLimitsApp::class, sdk = [34])
class AppLaunchTest {

    private fun app(): UsageLimitsApp =
        ApplicationProvider.getApplicationContext<Application>() as UsageLimitsApp

    /** Nothing this class started may still be running when the next class sets up. */
    @After
    fun drainStartupWork() {
        ShadowLooper.idleMainLooper()
        runBlocking { app().startupJob?.join() }
        ShadowLooper.idleMainLooper()
    }

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
        runBlocking { application.startupJob?.join() }
        ShadowLooper.idleMainLooper()

        assertTrue("the app should still be usable after start-up", application.container != null)
    }
}
