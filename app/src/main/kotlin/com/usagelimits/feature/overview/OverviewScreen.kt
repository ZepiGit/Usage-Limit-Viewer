package com.usagelimits.feature.overview

import androidx.compose.foundation.background
import com.usagelimits.core.model.percentLabel
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
import com.usagelimits.core.time.Countdown
import com.usagelimits.feature.UsageUiState
import com.usagelimits.ui.components.IconBadge
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.StatusPill
import com.usagelimits.ui.components.UsageBar
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.components.UsageWindowRow
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors

/** Short symbol standing in for a provider mark. */
fun providerSymbol(provider: ProviderId): String = when (provider) {
    ProviderId.CODEX -> "◇"
    ProviderId.CLAUDE -> "✳"
    ProviderId.ANTIGRAVITY -> "✦"
    ProviderId.XAI -> "✕"
}

fun providerTint(provider: ProviderId): Color = when (provider) {
    ProviderId.CODEX -> UsageColors.Teal
    ProviderId.CLAUDE -> UsageColors.Terracotta
    ProviderId.ANTIGRAVITY -> UsageColors.Green
    ProviderId.XAI -> UsageColors.TextPrimary
}

/**
 * The landing screen: a health summary, then one card per account.
 *
 * Ordered worst-first so the account that needs attention is the one already on screen —
 * scrolling to find a problem defeats the point of a glanceable app.
 */
@Composable
fun OverviewScreen(
    state: UsageUiState,
    nowMs: Long,
    onRefresh: () -> Unit,
    onAccountClick: (String) -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { OverviewHeader(state, nowMs, onRefresh) }

        item { SummaryCard(state, nowMs) }

        if (state.accounts.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Accounts",
                    trailing = {
                        Text(
                            text = Countdown.freshnessLabel(state.lastUpdated, nowMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = UsageColors.TextTertiary,
                        )
                    },
                )
            }
        }

        items(
            // Most urgent first, by the same ranking the widget uses — not by Severity's
            // declaration order, which put stale and never-fetched cards above exhausted ones.
            items = state.accounts.sortedBy {
                it.snapshot?.severityAt(nowMs, state.staleAfterMs)?.urgency ?: Int.MAX_VALUE
            },
            key = { it.account.localId },
        ) { usage ->
            AccountCard(usage, nowMs, state.staleAfterMs) {
                onAccountClick(usage.account.localId)
            }
        }

        item { AddAccountCard(onAddAccount) }
    }
}

@Composable
private fun OverviewHeader(state: UsageUiState, nowMs: Long, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            symbol = "▮",
            tint = UsageColors.Terracotta,
            container = UsageColors.TerracottaSurface,
            size = 46.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Usage Limits",
                style = MaterialTheme.typography.headlineLarge,
                color = UsageColors.TextPrimary,
            )
            Text(
                text = "${state.accountCount} account${if (state.accountCount == 1) "" else "s"} monitored",
                style = MaterialTheme.typography.bodyLarge,
                color = UsageColors.TextSecondary,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(UsageColors.SurfaceElevated)
                    .clickable(enabled = !state.isRefreshing, onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = UsageColors.Terracotta,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh usage",
                        tint = UsageColors.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * The three numbers worth knowing before scrolling: how many accounts are fine, when the next
 * limit rolls over, and how depleted the tightest window currently is.
 */
@Composable
private fun SummaryCard(state: UsageUiState, nowMs: Long) {
    val severity = state.overallSeverityAt(nowMs)
    val critical = state.mostCritical

    UsageCard(borderColor = SeverityPalette.container(severity)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1.15f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .border(3.dp, SeverityPalette.accent(severity), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${state.healthyCountAt(nowMs)}/${state.accountCount}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = SeverityPalette.accent(severity),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = headline(state, nowMs),
                            style = MaterialTheme.typography.titleMedium,
                            color = UsageColors.TextPrimary,
                        )
                        Text(
                            text = "Healthy accounts",
                            style = MaterialTheme.typography.bodyMedium,
                            color = UsageColors.TextSecondary,
                        )
                    }
                }
            }

            VerticalRule()

            SummaryStat(
                modifier = Modifier.weight(0.8f),
                symbol = "◷",
                tint = UsageColors.Terracotta,
                container = UsageColors.TerracottaSurface,
                // The reset of the account the card is ABOUT. Falls back to the fleet-wide
                // soonest only when there is no most-depleted account to scope it to.
                value = (critical?.first?.account?.localId?.let { state.nextResetAt(nowMs, it) }
                    ?: critical?.let { null } ?: state.nextResetAt(nowMs))
                    ?.let { Countdown.format(it - nowMs) } ?: "—",
                label = "Next reset",
            )

            VerticalRule()

            SummaryStat(
                modifier = Modifier.weight(0.9f),
                symbol = "▮",
                tint = SeverityPalette.accent(critical?.second?.severity ?: Severity.STALE),
                container = SeverityPalette.container(critical?.second?.severity ?: Severity.STALE),
                value = percentLabel(critical?.second?.remainingPercent),
                label = critical?.second?.label?.let { "$it left" } ?: "No data",
                bar = critical?.second,
            )
        }
    }
}

@Composable
private fun VerticalRule() {
    Box(
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .width(1.dp)
            .height(54.dp)
            .background(UsageColors.Outline),
    )
}

@Composable
private fun SummaryStat(
    symbol: String,
    tint: Color,
    container: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    bar: com.usagelimits.core.model.UsageWindow? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(symbol = symbol, tint = tint, container = container, size = 32.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = UsageColors.TextPrimary,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
        if (bar != null) {
            UsageBar(
                remainingPercent = bar.remainingPercent,
                severity = bar.severity,
                modifier = Modifier.fillMaxWidth(),
                height = 6.dp,
            )
        }
    }
}

private fun headline(state: UsageUiState, nowMs: Long): String = when (
    if (state.accounts.isEmpty()) null else state.overallSeverityAt(nowMs)
) {
    null -> "No accounts yet"
    Severity.HEALTHY -> "All systems good"
    Severity.EXHAUSTED -> "A limit is exhausted"
    Severity.ERROR -> "Needs attention"
    // Age is its own headline: green over day-old numbers is the failure this app prevents.
    Severity.STALE -> "Data may be out of date"
    else -> "Running low"
}

/**
 * One account: identity, status, and its quota rows.
 *
 * Windows are grouped when the provider supplies a group (Antigravity quota groups, Codex code
 * review), so a shared bucket is shown once with its members named rather than repeated per
 * model.
 */
@Composable
fun AccountCard(
    usage: AccountUsage,
    nowMs: Long,
    staleAfterMs: Long,
    onClick: () -> Unit,
) {
    val snapshot = usage.snapshot
    val severity = snapshot?.severityAt(nowMs, staleAfterMs) ?: Severity.STALE

    UsageCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                symbol = providerSymbol(usage.account.provider),
                tint = providerTint(usage.account.provider),
                container = UsageColors.SurfaceElevated,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildString {
                        append(usage.account.provider.displayName)
                        usage.account.plan?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = UsageColors.TextPrimary,
                    maxLines = 1,
                )
                Text(
                    text = usage.account.maskedEmail ?: usage.account.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.TextSecondary,
                    maxLines = 1,
                )
            }
            StatusPill(severity)
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = UsageColors.TextTertiary,
            )
        }

        val windows = snapshot?.windows.orEmpty()
        if (windows.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = snapshot?.errorMessage ?: "No usage data yet",
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.TextTertiary,
            )
        } else {
            Spacer(Modifier.height(10.dp))
            // Ungrouped windows first — they are the account's headline limits.
            windows.filter { it.group == null }.take(4).forEach { window ->
                UsageWindowRow(window, nowMs)
                Spacer(Modifier.height(6.dp))
            }

            val groups = windows.filter { it.group != null }.groupBy { it.group!! }
            groups.forEach { (group, groupWindows) ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = group,
                    style = MaterialTheme.typography.labelMedium,
                    color = UsageColors.TextTertiary,
                )
                Spacer(Modifier.height(4.dp))
                groupWindows.forEach { window ->
                    UsageWindowRow(window, nowMs)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // The summary line reports what the user holds; whether any of it can be spent right
        // now is a detail-screen concern, where the button lives.
        val creditCount = snapshot?.heldResetCredits ?: 0
        if (creditCount > 0) {
            HorizontalDivider(color = UsageColors.Outline, modifier = Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Reset credits",
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.TextSecondary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$creditCount available",
                    style = MaterialTheme.typography.labelLarge,
                    color = UsageColors.Terracotta,
                )
            }
        }

        if (snapshot?.errorMessage != null && windows.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${snapshot.errorMessage} · showing last known data",
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.Amber,
            )
        }
    }
}

@Composable
private fun AddAccountCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, UsageColors.Outline, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(UsageColors.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = UsageColors.Terracotta)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "Add account",
                style = MaterialTheme.typography.titleMedium,
                color = UsageColors.TextPrimary,
            )
            Text(
                text = "Monitor another AI subscription",
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.TextSecondary,
            )
        }
    }
}
