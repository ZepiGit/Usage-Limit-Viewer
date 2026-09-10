package com.usagelimits.feature

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.feature.accounts.AccountDetailScreen
import com.usagelimits.feature.accounts.AddAccountScreen
import com.usagelimits.feature.accounts.AddAccountState
import com.usagelimits.feature.accounts.AccountsScreen
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.feature.resets.ResetsScreen
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every screen, actually rendered.
 *
 * Until this file existed, no Compose code in this app had ever been executed anywhere: the unit
 * tests covered parsers and the CI job compiled the module, and neither of those runs a
 * composable. An app whose screens have never been drawn is an app nobody has run, and the
 * failures that hides — a crash in a lazy list key, a null dereference in a formatter, a theme
 * that cannot resolve — are exactly the ones a user meets first.
 *
 * Robolectric on the JVM rather than an instrumented test, because this environment has no KVM
 * and therefore no emulator. It is a weaker signal than a device, and it is the difference
 * between "never run" and "runs".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_757_000_000_000L

    private fun account(
        id: String = "a",
        provider: ProviderId = ProviderId.CODEX,
        plan: String? = "Plus",
    ) = ProviderAccount(
        localId = id,
        provider = provider,
        externalAccountId = "ext-$id",
        email = "someone@example.com",
        displayName = null,
        plan = plan,
        credentialReference = "ref-$id",
        createdAt = 0L,
        lastSuccessfulSync = now,
    )

    private fun window(usedPercent: Double?, label: String = "5h limit") = UsageWindow(
        id = "5h",
        label = label,
        category = WindowCategory.FIVE_HOUR,
        usedPercent = usedPercent,
        periodSeconds = 18_000,
        resetAt = now + 3_600_000,
        exhausted = false,
    )

    private fun usage(
        id: String = "a",
        usedPercent: Double? = 40.0,
        status: SnapshotStatus = SnapshotStatus.OK,
        provider: ProviderId = ProviderId.CODEX,
    ) = AccountUsage(
        account = account(id, provider),
        snapshot = UsageSnapshot(
            accountId = id,
            fetchedAt = now,
            status = status,
            windows = listOf(window(usedPercent)),
        ),
    )

    private fun state(vararg accounts: AccountUsage) = UsageUiState(
        accounts = accounts.toList(),
        settings = AppSettings(),
    )

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { UsageLimitsTheme { content() } }
        compose.waitForIdle()
    }

    // MARK: - The four screens, with data

    @Test
    fun `overview renders an account`() {
        render {
            OverviewScreen(
                state = state(usage()),
                nowMs = now,
                onRefresh = {},
                onAccountClick = {},
                onAddAccount = {},
                onReorder = {},
            )
        }

        // `onAllNodes`, not `onNode`: the label legitimately appears twice — once on the
        // summary card that leads the screen and once on the account card below it — and
        // asserting a single node fails on correct behaviour.
        assertTrue(
            "the window label should be on screen",
            compose.onAllNodesWithText("5h limit", substring = true)
                .fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `overview renders with no accounts at all`() {
        // The state every user sees first, and the one most likely to divide by a count of zero.
        render {
            OverviewScreen(
                state = state(),
                nowMs = now,
                onRefresh = {},
                onAccountClick = {},
                onAddAccount = {},
                onReorder = {},
            )
        }

        compose.waitForIdle()
    }

    @Test
    fun `overview survives a window whose percentage the provider never stated`() {
        // Unknown is not zero, and the formatter has to say so rather than divide by it.
        render {
            OverviewScreen(
                state = state(usage(usedPercent = null)),
                nowMs = now,
                onRefresh = {},
                onAccountClick = {},
                onAddAccount = {},
                onReorder = {},
            )
        }

        compose.waitForIdle()
    }

    @Test
    fun `overview renders a failed account`() {
        render {
            OverviewScreen(
                state = state(usage(status = SnapshotStatus.FAILED)),
                nowMs = now,
                onRefresh = {},
                onAccountClick = {},
                onAddAccount = {},
                onReorder = {},
            )
        }

        compose.waitForIdle()
    }

    @Test
    fun `overview renders an account that has never synced`() {
        // A snapshot of null is a distinct shape from a failed one, and it reaches every
        // formatter on the card.
        render {
            OverviewScreen(
                state = UsageUiState(
                    accounts = listOf(AccountUsage(account = account(), snapshot = null)),
                ),
                nowMs = now,
                onRefresh = {},
                onAccountClick = {},
                onAddAccount = {},
                onReorder = {},
            )
        }

        compose.waitForIdle()
    }

    @Test
    fun `accounts renders one card per provider`() {
        render {
            AccountsScreen(
                state = state(
                    usage("a", provider = ProviderId.CODEX),
                    usage("b", provider = ProviderId.CLAUDE),
                    usage("c", provider = ProviderId.ANTIGRAVITY),
                    usage("d", provider = ProviderId.XAI),
                ),
                nowMs = now,
                onAccountClick = {},
                onAddAccount = {},
            )
        }

        compose.waitForIdle()
    }

    @Test
    fun `resets renders upcoming windows`() {
        render { ResetsScreen(state = state(usage()), nowMs = now) }

        compose.waitForIdle()
    }

    @Test
    fun `resets renders with nothing scheduled`() {
        render { ResetsScreen(state = state(), nowMs = now) }

        compose.waitForIdle()
    }

    @Test
    fun `settings renders and its toggles report back`() {
        var toggled = false
        render {
            SettingsScreen(
                state = state(usage()),
                onSyncIntervalChange = {},
                onNotifyBelow20 = { toggled = true },
                onNotifyBelow10 = {},
                onNotifyExhausted = {},
                onNotifyResetCredit = {},
                onNotifyAuthExpired = {},
                onNotifyResetApproaching = {},
                onNotifyCreditExpiring = {},
            )
        }

        compose.onAllNodesWithText("Below 20% left", substring = true)
            .fetchSemanticsNodes()
            .let { assertTrue("the 20% toggle should be on screen", it.isNotEmpty()) }
    }

    @Test
    fun `account detail renders and can be refreshed`() {
        var refreshed = false
        render {
            AccountDetailScreen(
                usage = usage(),
                nowMs = now,
                staleAfterMs = 3_600_000,
                resetInFlight = false,
                supportsResetCredits = true,
                onRefresh = { refreshed = true },
                onConsumeResetCredit = {},
                onRemove = {},
            )
        }

        compose.waitForIdle()
    }

    @Test
    fun `account detail renders for an account that is gone`() {
        // Reachable by deleting an account while its detail screen is open.
        render {
            AccountDetailScreen(
                usage = null,
                nowMs = now,
                staleAfterMs = 3_600_000,
                resetInFlight = false,
                supportsResetCredits = false,
                onRefresh = {},
                onConsumeResetCredit = {},
                onRemove = {},
            )
        }

        compose.waitForIdle()
    }

    /**
     * Signing in with a device code means carrying the code from this screen to a browser on the
     * same phone. Reading eight characters off the screen and typing them into another app is
     * where that flow was breaking down, so the code goes to the clipboard on its own, and the
     * card offers to do it again by hand.
     *
     * Both halves are asserted because both can fail on their own: the automatic copy is a
     * `LaunchedEffect` that a refactor can drop without breaking the layout, and the buttons are
     * the fallback for a browser that never opened.
     */
    @Test
    fun `the device code is copied for you and can be copied again`() {
        render {
            AddAccountScreen(
                state = AddAccountState.AwaitingDeviceCode(
                    provider = ProviderId.CODEX,
                    userCode = "WDJB-MJHT",
                    verificationUri = "https://auth.openai.com/device",
                ),
                providers = listOf(ProviderId.CODEX),
                onStart = {},
                onCancel = {},
                onDone = {},
            )
        }

        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            "the code should be on the clipboard without anyone pressing anything",
            "WDJB-MJHT",
            clipboard.primaryClip?.getItemAt(0)?.text?.toString(),
        )

        assertTrue(
            "the code should still be copyable by hand",
            compose.onAllNodesWithText("Copy code").fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "and the sign-in page reopenable, for when the browser never came up",
            compose.onAllNodesWithText("Open page").fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
