package com.usagelimits.feature

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import com.usagelimits.core.model.ProviderId
import com.usagelimits.feature.accounts.AddAccountScreen
import com.usagelimits.feature.accounts.AddAccountState
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignInTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `provider hints describe their default login flow`() {
        val provider = mutableStateOf(ProviderId.KIMI)
        compose.setContent {
            UsageLimitsTheme {
                AddAccountScreen(
                    state = AddAccountState.PickProvider, providers = listOf(provider.value),
                    onStart = {}, onCancel = {}, onDone = {}, onSubmitApiKey = {},
                )
            }
        }
        compose.onNodeWithText("Sign in with a device code").assertExists()
        compose.runOnIdle { provider.value = ProviderId.CODEX }
        compose.onNodeWithText("Sign in in your browser").assertExists()
    }

    @Test fun `the browser wait offers the device code and a failed code attempt retries the code`() {
        val state = mutableStateOf<AddAccountState>(
            AddAccountState.AwaitingBrowser(ProviderId.CODEX, deviceCodeAlternative = true, authorizationUrl = "https://example.test/auth"),
        )
        var browserStarts = 0
        var codeStarts = 0
        compose.setContent {
            UsageLimitsTheme {
                AddAccountScreen(
                    state = state.value, providers = listOf(ProviderId.CODEX),
                    onStart = { browserStarts++ }, onCancel = {}, onDone = {}, onSubmitApiKey = {},
                    onStartWithDeviceCode = { codeStarts++ },
                )
            }
        }
        compose.onNodeWithText("Use a device code").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, codeStarts) }

        compose.runOnIdle {
            state.value = AddAccountState.Failed(ProviderId.CODEX, "Device login expired", deviceCodeAlternative = true, viaDeviceCode = true)
        }
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Use the browser").assertExists()
        compose.runOnIdle {
            assertEquals("try again repeats the flow that failed", 2, codeStarts)
            assertEquals(0, browserStarts)
        }
    }

    @Test fun `the outgoing provider chooser cannot start another login`() {
        val state = mutableStateOf<AddAccountState>(AddAccountState.PickProvider)
        var starts = 0
        compose.setContent {
            UsageLimitsTheme {
                AddAccountScreen(
                    state = state.value, providers = listOf(ProviderId.KIMI),
                    onStart = { starts++ }, onCancel = {}, onDone = {}, onSubmitApiKey = {},
                )
            }
        }
        compose.onNodeWithText(ProviderId.KIMI.displayName).assertExists()
        val outgoingClick = compose.onNodeWithText(ProviderId.KIMI.displayName)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state.value = AddAccountState.Starting(ProviderId.KIMI) }
        compose.onNodeWithText("Contacting ${ProviderId.KIMI.displayName}…").assertExists()
        // A queued click belongs to the old step even if delivered after it stops rendering.
        compose.runOnIdle {
            outgoingClick()
            assertEquals(0, starts)
        }
    }
}
