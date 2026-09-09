package com.usagelimits.feature.resets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.usagelimits.core.time.Countdown
import com.usagelimits.feature.UpcomingReset
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.overview.providerSymbol
import com.usagelimits.feature.overview.providerTint
import com.usagelimits.ui.components.IconBadge
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every upcoming rollover, chronologically.
 *
 * Grouped by calendar day ("Today", "Tomorrow", then a weekday name) because a flat list of
 * countdowns gives no sense of *when* — "in 19h" and "tomorrow 08:20" answer different questions.
 */
@Composable
fun ResetsScreen(
    state: UsageUiState,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val resets = state.upcomingResets(nowMs)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    text = "Resets",
                    style = MaterialTheme.typography.headlineLarge,
                    color = UsageColors.TextPrimary,
                )
                Text(
                    text = if (resets.isEmpty()) {
                        "No upcoming resets"
                    } else {
                        "${resets.size} upcoming"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = UsageColors.TextSecondary,
                )
            }
        }

        if (resets.isEmpty()) {
            item {
                UsageCard {
                    Text(
                        text = "Reset times appear once an account has been synced and the " +
                            "provider reports a rollover time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = UsageColors.TextSecondary,
                    )
                }
            }
        }

        resets.groupBy { Instant.ofEpochMilli(it.resetAt).atZone(zone).toLocalDate() }
            .forEach { (date, dayResets) ->
                item(key = "header-$date") {
                    SectionHeader(title = dayLabel(date, today))
                }
                items(dayResets.size, key = { i -> dayResets[i].let { "${it.accountId}-${it.windowLabel}-${it.resetAt}" } }) { index ->
                    ResetRow(dayResets[index], nowMs, zone)
                }
            }
    }
}

@Composable
private fun ResetRow(reset: UpcomingReset, nowMs: Long, zone: ZoneId) {
    val time = remember(reset.resetAt) {
        Instant.ofEpochMilli(reset.resetAt).atZone(zone)
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    UsageCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = time,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = UsageColors.TextPrimary,
                modifier = Modifier.width(58.dp),
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(SeverityPalette.accent(reset.severity)),
            )
            Spacer(Modifier.width(12.dp))
            IconBadge(
                symbol = providerSymbol(reset.provider),
                tint = providerTint(reset.provider),
                container = UsageColors.SurfaceElevated,
                size = 32.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reset.windowLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = UsageColors.TextPrimary,
                    maxLines = 1,
                )
                Text(
                    text = reset.accountLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.TextSecondary,
                    maxLines = 1,
                )
            }
            Text(
                text = Countdown.format(reset.resetAt - nowMs),
                style = MaterialTheme.typography.labelLarge,
                color = UsageColors.TextSecondary,
            )
        }
    }
}

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> date.format(DateTimeFormatter.ofPattern("EEEE d MMM"))
}
