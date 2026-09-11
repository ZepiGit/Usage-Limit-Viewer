package com.usagelimits.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import com.usagelimits.core.di.AppContainer
import com.usagelimits.feature.UsageViewModel
import com.usagelimits.feature.accounts.AccountDetailScreen
import com.usagelimits.feature.accounts.AccountsScreen
import com.usagelimits.feature.accounts.AddAccountScreen
import com.usagelimits.feature.accounts.AddAccountState
import com.usagelimits.feature.accounts.AddAccountViewModel
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.feature.resets.ResetsScreen
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.ConstrainedContent
import com.usagelimits.ui.NavigationLayout
import com.usagelimits.ui.theme.UsageColors

/**
 * How often relative times refresh.
 *
 * Countdowns are rendered at minute resolution, so a shorter tick would recompose without
 * changing a single character; a longer one would let "1m" sit on screen after it became "now".
 */
private const val TICK_INTERVAL_MS = 30_000L

private sealed class Destination(val route: String) {
    data object Overview : Destination("overview")
    data object Accounts : Destination("accounts")
    data object Resets : Destination("resets")
    data object Settings : Destination("settings")
    data object AddAccount : Destination("add-account")
    data object AccountDetail : Destination("account/{accountId}") {
        fun of(accountId: String) = "account/$accountId"
    }
}

private data class TabItem(
    val destination: Destination,
    val label: String,
    val icon: ImageVector,
)

private val TABS = listOf(
    TabItem(Destination.Overview, "Overview", Icons.Default.Home),
    TabItem(Destination.Accounts, "Accounts", Icons.Default.People),
    TabItem(Destination.Resets, "Resets", Icons.Default.Schedule),
    TabItem(Destination.Settings, "Settings", Icons.Default.Settings),
)

/**
 * The app shell: four tabs plus two pushed routes.
 *
 * `nowMs` is read once per recomposition and threaded down rather than each countdown calling
 * the clock itself, so every relative time on screen is consistent with the others.
 */
@Composable
fun UsageLimitsNavigation(container: AppContainer, windowSizeClass: WindowSizeClass) {
    val navController = rememberNavController()
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val viewModel: UsageViewModel =
        viewModel(factory = UsageViewModel.Factory(container, appContext))
    val state by viewModel.state.collectAsState()
    val resetInFlight by viewModel.resetCreditInFlight.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // Nav chrome is hidden on the pushed routes (add-account, account detail) so those
    // screens own the full window.
    val showNavigation = TABS.any { tab ->
        currentRoute?.hierarchy?.any { it.route == tab.destination.route } == true
    }
    val navigationLayout = NavigationLayout.forWidth(windowSizeClass.widthSizeClass)
    val showBottomBar = showNavigation && navigationLayout == NavigationLayout.BOTTOM_BAR
    val showSideRail = showNavigation && navigationLayout == NavigationLayout.SIDE_RAIL

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            // Keep a single copy of each tab on the back stack and restore where the user
            // was when they return to it.
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(UsageColors.Background)) {
        if (showSideRail) {
            NavigationRail(containerColor = UsageColors.Surface) {
                TABS.forEach { tab ->
                    val selected = currentRoute?.hierarchy
                        ?.any { it.route == tab.destination.route } == true
                    NavigationRailItem(
                        selected = selected,
                        onClick = { navigateToTab(tab.destination.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = UsageColors.Terracotta,
                            selectedTextColor = UsageColors.Terracotta,
                            indicatorColor = UsageColors.TerracottaSurface,
                            unselectedIconColor = UsageColors.TextTertiary,
                            unselectedTextColor = UsageColors.TextTertiary,
                        ),
                    )
                }
            }
        }

    Scaffold(
        containerColor = UsageColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = UsageColors.Surface) {
                    TABS.forEach { tab ->
                        val selected = currentRoute?.hierarchy
                            ?.any { it.route == tab.destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateToTab(tab.destination.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = UsageColors.Terracotta,
                                selectedTextColor = UsageColors.Terracotta,
                                indicatorColor = UsageColors.TerracottaSurface,
                                unselectedIconColor = UsageColors.TextTertiary,
                                unselectedTextColor = UsageColors.TextTertiary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // A plain System.currentTimeMillis() read is not a Compose state read, so with no
        // data change every countdown on screen froze at whatever the tab was composed with.
        // For an app made of countdowns that is visible within a minute.
        val nowMs by produceState(initialValue = System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                delay(TICK_INTERVAL_MS)
            }
        }

        ConstrainedContent(
            modifier = Modifier
                .fillMaxSize()
                .background(UsageColors.Background)
                .padding(padding),
        ) {
            NavHost(navController = navController, startDestination = Destination.Overview.route) {
                composable(Destination.Overview.route) {
                    OverviewScreen(
                        state = state,
                        nowMs = nowMs,
                        onRefresh = viewModel::refresh,
                        onAccountClick = { navController.navigate(Destination.AccountDetail.of(it)) },
                        onAddAccount = { navController.navigate(Destination.AddAccount.route) },
                        onReorder = viewModel::reorderAccounts,
                    )
                }

                composable(Destination.Accounts.route) {
                    AccountsScreen(
                        state = state,
                        nowMs = nowMs,
                        onAccountClick = { navController.navigate(Destination.AccountDetail.of(it)) },
                        onAddAccount = { navController.navigate(Destination.AddAccount.route) },
                    )
                }

                composable(Destination.Resets.route) {
                    ResetsScreen(state = state, nowMs = nowMs)
                }

                composable(Destination.Settings.route) {
                    SettingsScreen(
                        state = state,
                        onSyncIntervalChange = viewModel::setSyncInterval,
                        onNotifyBelow20 = viewModel::setNotifyBelow20,
                        onNotifyBelow10 = viewModel::setNotifyBelow10,
                        onNotifyExhausted = viewModel::setNotifyExhausted,
                        onNotifyResetCredit = viewModel::setNotifyResetCredit,
                        onNotifyAuthExpired = viewModel::setNotifyAuthExpired,
                        onNotifyResetApproaching = viewModel::setNotifyResetApproaching,
                        onNotifyCreditExpiring = viewModel::setNotifyCreditExpiring,
                        onShowTier = viewModel::setShowSubscriptionTier,
                        onShowRenewal = viewModel::setShowRenewalTime,
                    )
                }

                composable(Destination.AddAccount.route) {
                    val addViewModel: AddAccountViewModel =
                        viewModel(factory = AddAccountViewModel.Factory(container))
                    val addState by addViewModel.state.collectAsState()
                    val context = androidx.compose.ui.platform.LocalContext.current

                    AddAccountScreen(
                        state = addState,
                        providers = addViewModel.availableProviders(),
                        onStart = { addViewModel.startLogin(context, it) },
                        onSubmitApiKey = addViewModel::submitApiKey,
                        onCancel = {
                            addViewModel.cancel()
                            navController.popBackStack()
                        },
                        onDone = {
                            addViewModel.reset()
                            navController.popBackStack()
                        },
                    )
                }

                composable(Destination.AccountDetail.route) { entry ->
                    val accountId = entry.arguments?.getString("accountId")
                    val usage = state.accounts.firstOrNull { it.account.localId == accountId }
                    val supportsCredits = usage?.account?.provider
                        ?.let { container.providerRegistry.forId(it)?.supportsResetCredits }
                        ?: false

                    AccountDetailScreen(
                        usage = usage,
                        nowMs = nowMs,
                        staleAfterMs = state.staleAfterMs,
                        resetInFlight = resetInFlight == accountId,
                        supportsResetCredits = supportsCredits,
                        onRefresh = { accountId?.let(viewModel::refreshAccount) },
                        onConsumeResetCredit = { accountId?.let(viewModel::consumeResetCredit) },
                        onRemove = {
                            accountId?.let(viewModel::removeAccount)
                            navController.popBackStack()
                        },
                        notificationsEnabled =
                            accountId !in state.settings.mutedAccountIds,
                        onNotificationsChange = { enabled ->
                            accountId?.let { viewModel.setAccountNotifications(it, enabled) }
                        },
                    )
                }
            }
        }
    }
    }
}
