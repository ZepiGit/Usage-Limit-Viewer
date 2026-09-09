package com.usagelimits

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.usagelimits.core.sync.SyncWorker
import com.usagelimits.navigation.UsageLimitsNavigation
import com.usagelimits.ui.theme.UsageLimitsTheme

class MainActivity : ComponentActivity() {


    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as UsageLimitsApp).container

        // Notifications are an optional convenience, so this is asked for once and never
        // blocks any part of the app when declined.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            // Recomputed on every configuration change, so unfolding a foldable, entering
            // split-screen or resizing a freeform window re-lays-out the shell immediately.
            val windowSizeClass = calculateWindowSizeClass(this)
            UsageLimitsTheme {
                UsageLimitsNavigation(container, windowSizeClass)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the app is a strong signal the user wants current numbers.
        SyncWorker.syncNow(this)
    }
}
