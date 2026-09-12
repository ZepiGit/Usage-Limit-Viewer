package com.usagelimits.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReducedMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a system setting change updates an already open composition`() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        Settings.Global.putFloat(resolver, setting, 1f)
        compose.setContent {
            UsageLimitsTheme { Text(if (LocalMotionEnabled.current) "Motion on" else "Motion off") }
        }
        compose.onNodeWithText("Motion on").assertExists()
        compose.runOnIdle {
            Settings.Global.putFloat(resolver, setting, 0f)
            resolver.notifyChange(Settings.Global.getUriFor(setting), null)
        }
        compose.onNodeWithText("Motion off").assertExists()
        compose.runOnIdle {
            Settings.Global.putFloat(resolver, setting, 1f)
            resolver.notifyChange(Settings.Global.getUriFor(setting), null)
        }
        compose.onNodeWithText("Motion on").assertExists()
    }
}
