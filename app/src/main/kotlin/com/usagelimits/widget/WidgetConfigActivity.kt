package com.usagelimits.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.WidgetScope
import com.usagelimits.feature.overview.providerSymbol
import com.usagelimits.feature.overview.providerTint
import com.usagelimits.ui.components.IconBadge
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.components.ToggleRow
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.launch

/**
 * Chooses what a placed widget shows.
 *
 * Declared as each provider's `android:configure`, so the launcher opens it when a widget is
 * dropped. Without it every widget rendered the same automatic view, and the ACCOUNT and
 * PROVIDER scopes were unreachable — two widgets watching two different Codex accounts looked
 * identical.
 *
 * The result is set to CANCELED first and only flipped to OK on an explicit choice. That is
 * the contract: if the user backs out, the launcher removes the widget rather than leaving an
 * unconfigured one on the home screen.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setResult(RESULT_CANCELED, resultIntent())

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val container = (application as UsageLimitsApp).container

        setContent {
            UsageLimitsTheme {
                var accounts by remember { mutableStateOf<List<AccountUsage>>(emptyList()) }
                LaunchedEffect(Unit) { accounts = container.repository.accountUsageOnce() }

                WidgetConfigScreen(
                    accounts = accounts,
                    onChoose = { scope, accountId, provider, transparent ->
                        lifecycleScope.launch {
                            container.widgetConfigDao.upsert(
                                WidgetConfigEntity(
                                    appWidgetId = appWidgetId,
                                    scope = scope.name,
                                    accountId = accountId,
                                    provider = provider?.id,
                                    updatedAt = System.currentTimeMillis(),
                                    transparent = transparent,
                                ),
                            )
                            // Repaint immediately so the widget lands showing the chosen
                            // scope rather than the default until the next sync.
                            WidgetUpdater.refreshAll(this@WidgetConfigActivity)
                            setResult(RESULT_OK, resultIntent())
                            finish()
                        }
                    },
                )
            }
        }
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

@Composable
private fun WidgetConfigScreen(
    accounts: List<AccountUsage>,
    onChoose: (WidgetScope, String?, ProviderId?, Boolean) -> Unit,
) {
    val providers = accounts.map { it.account.provider }.distinct()

    // Chosen BEFORE the scope, because picking a scope is what commits the whole
    // configuration and closes this screen. A switch below the list the user never reaches
    // would be a setting that exists and cannot be set.
    // Saveable: a rotation recreates this activity, and a plain `remember` handed the user back
    // an unticked box — and then persisted transparent=false for the widget they had just
    // asked to be transparent.
    var transparent by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(UsageColors.Background)
            .safeDrawingPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text(
                    text = "Widget content",
                    style = MaterialTheme.typography.headlineLarge,
                    color = UsageColors.TextPrimary,
                )
                Text(
                    text = "Choose what this widget shows. You can place several widgets with " +
                        "different settings.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = UsageColors.TextSecondary,
                )
            }
        }

        item {
            UsageCard {
                ToggleRow(
                    title = "Transparent background",
                    subtitle = "Drop the panel and let the wallpaper through",
                    checked = transparent,
                    onChange = { transparent = it },
                )
            }
        }

        item {
            ChoiceRow(
                symbol = "◈",
                title = "Most important limits",
                subtitle = "Automatically follows whatever is closest to running out",
            ) { onChoose(WidgetScope.MOST_CRITICAL, null, null, transparent) }
        }

        item {
            ChoiceRow(
                symbol = "▤",
                title = "All accounts",
                subtitle = "Everything, combined",
            ) { onChoose(WidgetScope.ALL_ACCOUNTS, null, null, transparent) }
        }

        if (providers.isNotEmpty()) {
            item { SectionHeader("One provider") }
            items(providers.size) { index ->
                val provider = providers[index]
                ChoiceRow(
                    symbol = providerSymbol(provider),
                    tintFor = provider,
                    title = provider.displayName,
                    subtitle = "All ${provider.displayName} accounts",
                ) { onChoose(WidgetScope.PROVIDER, null, provider, transparent) }
            }
        }

        if (accounts.isNotEmpty()) {
            item { SectionHeader("One account") }
            items(accounts.size) { index ->
                val usage = accounts[index]
                ChoiceRow(
                    symbol = providerSymbol(usage.account.provider),
                    tintFor = usage.account.provider,
                    title = usage.account.label,
                    subtitle = usage.account.provider.displayName,
                ) { onChoose(WidgetScope.ACCOUNT, usage.account.localId, null, transparent) }
            }
        }

        if (accounts.isEmpty()) {
            item {
                UsageCard {
                    Text(
                        text = "No accounts yet. The widget will show your limits once you add " +
                            "one in the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = UsageColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    symbol: String,
    title: String,
    subtitle: String,
    tintFor: ProviderId? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(UsageColors.Surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            symbol = symbol,
            tint = tintFor?.let(::providerTint) ?: UsageColors.Terracotta,
            container = UsageColors.SurfaceElevated,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = UsageColors.TextPrimary,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}
