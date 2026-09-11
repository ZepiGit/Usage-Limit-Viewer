package com.usagelimits.feature

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.usagelimits.core.model.ProviderId
import com.usagelimits.feature.accounts.AddAccountScreen
import com.usagelimits.feature.accounts.AddAccountState
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "w568dp-h320dp-land")
class AdaptiveScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `all providers remain reachable in a short landscape window`() {
        compose.setContent {
            UsageLimitsTheme {
                AddAccountScreen(
                    state = AddAccountState.PickProvider, providers = ProviderId.entries,
                    onStart = {}, onCancel = {}, onDone = {}, onSubmitApiKey = {},
                )
            }
        }
        compose.onNodeWithText(ProviderId.KIMI.displayName).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ProviderId.CODEX.displayName).performScrollTo().assertIsDisplayed()
    }
}
