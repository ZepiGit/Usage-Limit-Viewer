package com.usagelimits.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.Severity
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.feature.UsageUiState
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.ToggleRow
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors

/** Sync cadence choices. 15 minutes is WorkManager's floor for periodic work. */
private val INTERVAL_CHOICES = listOf(15, 30, 60, 180)

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SettingsScreen(
    state: UsageUiState,
    onSyncIntervalChange: (Int) -> Unit,
    onNotifyBelow20: (Boolean) -> Unit,
    onNotifyBelow10: (Boolean) -> Unit,
    onNotifyExhausted: (Boolean) -> Unit,
    onNotifyResetCredit: (Boolean) -> Unit,
    onNotifyAuthExpired: (Boolean) -> Unit,
    onNotifyResetApproaching: (Boolean) -> Unit,
    onNotifyCreditExpiring: (Boolean) -> Unit,
    onShowTier: (Boolean) -> Unit,
    onShowRenewal: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onProviderIconChange: (com.usagelimits.core.model.ProviderId, String) -> Unit = { _, _ -> },
) {
    val settings = state.settings

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineLarge,
                color = UsageColors.TextPrimary,
            )
        }

        item { ProviderIconSettings(settings.providerIcons, onProviderIconChange) }
        item { SectionHeader("Sync") }
        item {
            UsageCard {
                Text(
                    text = "Background refresh interval",
                    style = MaterialTheme.typography.titleMedium,
                    color = UsageColors.TextPrimary,
                )
                Text(
                    text = "Android may delay background work to save battery, so this is a " +
                        "target rather than a guarantee.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    INTERVAL_CHOICES.forEach { minutes ->
                        IntervalChip(
                            minutes = minutes,
                            selected = settings.syncIntervalMinutes == minutes,
                            onClick = { onSyncIntervalChange(minutes) },
                        )
                    }
                }
            }
        }

        item { SectionHeader("Overview") }
        item {
            UsageCard {
                ToggleRow(
                    title = "Show subscription tier",
                    subtitle = "Plus, Pro, Max — beside the provider name",
                    checked = settings.showSubscriptionTier,
                    onChange = onShowTier,
                )
                ToggleRow(
                    title = "Show renewal time",
                    subtitle = "When the weekly or monthly allowance starts over, as opposed to " +
                        "the next reset. Overview only — the widgets stay on one number.",
                    checked = settings.showRenewalTime,
                    onChange = onShowRenewal,
                )
            }
        }

        item { SectionHeader("Notifications") }
        item {
            UsageCard {
                ToggleRow(
                    title = "Below 20% left",
                    subtitle = "Once, the first time a limit drops under a fifth remaining",
                    checked = settings.notifyBelow20Percent,
                    onChange = onNotifyBelow20,
                )
                ToggleRow(
                    title = "Below 10% left",
                    subtitle = "Once more when it gets tight, even after the 20% warning",
                    checked = settings.notifyBelow10Percent,
                    onChange = onNotifyBelow10,
                )
                ToggleRow(
                    title = "Limit exhausted",
                    subtitle = "When a window is fully consumed",
                    checked = settings.notifyOnExhausted,
                    onChange = onNotifyExhausted,
                )
                ToggleRow(
                    title = "Reset credit available",
                    subtitle = "When Codex reports a usable rate-limit reset credit",
                    checked = settings.notifyOnResetCreditAvailable,
                    onChange = onNotifyResetCredit,
                )
                ToggleRow(
                    title = "Reset approaching",
                    subtitle = "About ${settings.resetApproachingMinutes} minutes before a " +
                        "window rolls over",
                    checked = settings.notifyOnResetApproaching,
                    onChange = onNotifyResetApproaching,
                )
                ToggleRow(
                    title = "Unused reset credit expires soon",
                    subtitle = "Notify me when an unused Codex reset credit expires within 24 hours. A credit lets you reset an eligible usage limit.",
                    checked = settings.notifyOnResetCreditExpiring,
                    onChange = onNotifyCreditExpiring,
                )
                ToggleRow(
                    title = "Sign-in expired",
                    subtitle = "When an account needs to be reconnected",
                    checked = settings.notifyOnAuthExpired,
                    onChange = onNotifyAuthExpired,
                )
            }
        }

        item { SectionHeader("Thresholds") }
        item {
            UsageCard {
                Text(
                    text = "Status is derived from the percentage still available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                ThresholdRow("Healthy", "above ${Severity.HEALTHY_ABOVE.toInt()}%", UsageColors.Green)
                ThresholdRow(
                    "Moderate",
                    "${Severity.MEDIUM_ABOVE.toInt()}–${Severity.HEALTHY_ABOVE.toInt()}%",
                    UsageColors.Amber,
                )
                ThresholdRow("Low", "1–${Severity.MEDIUM_ABOVE.toInt()}%", UsageColors.Amber)
                ThresholdRow("Exhausted", "0%", UsageColors.Red)
            }
        }

        item { SectionHeader("Diagnostics") }
        item {
            UsageCard {
                InfoRow("Accounts", state.accountCount.toString())
                InfoRow(
                    "Last successful sync",
                    state.lastUpdated?.let { com.usagelimits.core.time.Countdown.freshnessLabel(it, System.currentTimeMillis()) }
                        ?: "Never",
                )
                InfoRow("Sync interval", "${settings.syncIntervalMinutes} min")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Diagnostics deliberately exclude tokens, account identifiers and " +
                        "raw provider responses.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun IntervalChip(minutes: Int, selected: Boolean, onClick: () -> Unit) {
    val label = if (minutes >= 60) "${minutes / 60}h" else "${minutes}m"
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) UsageColors.Background else UsageColors.TextSecondary,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) UsageColors.Terracotta else UsageColors.SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}


@Composable
private fun ThresholdRow(label: String, range: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = UsageColors.TextPrimary)
        Spacer(Modifier.weight(1f))
        Text(range, style = MaterialTheme.typography.bodyMedium, color = UsageColors.TextSecondary)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = UsageColors.TextPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = UsageColors.TextSecondary,
        )
    }
}
