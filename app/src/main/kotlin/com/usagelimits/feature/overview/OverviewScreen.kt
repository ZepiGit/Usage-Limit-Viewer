package com.usagelimits.feature.overview

import com.usagelimits.ui.theme.LocalMotionEnabled
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
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
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.components.UsageWindowRow
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.AppIcons

/** Short symbol standing in for a provider mark. */
fun providerSymbol(provider: ProviderId): String = when (provider) {
    // Five silhouettes that cannot be mistaken for each other at badge size, which is the only
    // size these are ever drawn at. The previous set had a hollow diamond, a six-pointed star
    // and a four-pointed star: three variations on "small pointy thing", and on a row of cards
    // the eye could not tell the second from the third without reading the name underneath.
    //
    // Each is also the closest single character to the provider's own mark rather than an
    // arbitrary assignment — an asterisk for Anthropic, an X for xAI, a moon for Moonshot.
    ProviderId.CODEX -> "⬡"
    ProviderId.CLAUDE -> "✳"
    ProviderId.ANTIGRAVITY -> "◆"
    ProviderId.XAI -> "✕"
    ProviderId.KIMI -> "☾"
}

fun providerTint(provider: ProviderId): Color = when (provider) {
    ProviderId.CODEX -> UsageColors.Teal
    ProviderId.CLAUDE -> UsageColors.Terracotta
    ProviderId.ANTIGRAVITY -> UsageColors.Green
    ProviderId.XAI -> UsageColors.TextPrimary
    ProviderId.KIMI -> UsageColors.Indigo
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
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Drag state. `liveOrder` is the list as the finger has rearranged it — non-null only while
    // a drag is in flight, and for the moment afterwards before the stored order comes back
    // through the flow. Dropping it the instant the finger lifts would show the old order again
    // for one frame, which reads as the drag having failed.
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var liveOrder by remember { mutableStateOf<List<AccountUsage>?>(null) }

    val shown = liveOrder ?: state.orderedAccounts(nowMs)

    LaunchedEffect(state.accounts, state.settings.accountsManuallyOrdered) {
        val pending = liveOrder ?: return@LaunchedEffect
        if (draggingId == null &&
            state.orderedAccounts(nowMs).map { it.account.localId } ==
            pending.map { it.account.localId }
        ) {
            liveOrder = null
        }
    }

    LazyColumn(
        state = listState,
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

        items(items = shown, key = { it.account.localId }) { usage ->
            val id = usage.account.localId
            val dragging = draggingId == id
            val motionEnabled = LocalMotionEnabled.current

            AccountCard(
                usage = usage,
                nowMs = nowMs,
                staleAfterMs = state.staleAfterMs,
                showTier = state.settings.showSubscriptionTier,
                showRenewal = state.settings.showRenewalTime,
                modifier = Modifier
                    .then(if (motionEnabled) Modifier.animateItem(placementSpec = if (dragging) null else spring()) else Modifier)
                    // Above its neighbours while it is being carried, or the cards it passes
                    // over are drawn on top of it.
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                    .pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingId = id
                                dragOffset = 0f
                                liveOrder = shown
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y

                                val current = liveOrder ?: return@detectDragGesturesAfterLongPress
                                val visible = listState.layoutInfo.visibleItemsInfo
                                val self = visible.firstOrNull { it.key == id }
                                    ?: return@detectDragGesturesAfterLongPress

                                // Where the card's middle now sits, and whichever card that
                                // point has landed inside is the one to trade places with.
                                val centre = self.offset + self.size / 2f + dragOffset
                                val over = visible.firstOrNull { other ->
                                    other.key != id &&
                                        centre >= other.offset &&
                                        centre <= other.offset + other.size
                                } ?: return@detectDragGesturesAfterLongPress

                                val from = current.indexOfFirst { it.account.localId == id }
                                val to = current.indexOfFirst { it.account.localId == over.key }
                                if (from < 0 || to < 0 || from == to) {
                                    return@detectDragGesturesAfterLongPress
                                }

                                liveOrder = current.toMutableList()
                                    .apply { add(to, removeAt(from)) }
                                // The card has swapped into the other's slot, so the finger's
                                // travel so far is now measured from there. Without this the
                                // card jumps by its own height on every swap.
                                dragOffset -= (over.offset - self.offset)
                            },
                            onDragEnd = {
                                draggingId = null
                                dragOffset = 0f
                                liveOrder?.let { order ->
                                    onReorder(order.map { it.account.localId })
                                }
                            },
                            onDragCancel = {
                                draggingId = null
                                dragOffset = 0f
                                liveOrder = null
                            },
                        )
                    },
            ) { onAccountClick(id) }
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
 * The two numbers worth knowing before scrolling: how many accounts are fine, and when the next
 * limit rolls over.
 *
 * It used to carry a third stat — the tightest window's remaining percent — and a sentence
 * naming the overall severity. Both were dropped as noise: the per-account cards below already
 * show every window with its own bar, so the summary was restating the first card, and the
 * sentence restated the colour of the ring beside it.
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
                    Text(
                        text = "Healthy accounts",
                        style = MaterialTheme.typography.titleMedium,
                        color = UsageColors.TextPrimary,
                    )
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
    }
}

/**
 * When the account's LONG allowance comes back, as opposed to its next reset.
 *
 * The next reset is nearly always the short rolling window — five hours on Codex — and it is
 * already on the card. What is not on the card is the date the weekly or monthly allowance
 * starts over, which is the one people plan around.
 *
 * The provider does not state a subscription renewal date anywhere in the usage payload, so
 * this is derived: the longest-period window's own reset. That makes it exactly "when the big
 * bucket refills" and nothing more — it is not a billing date and does not claim to be.
 *
 * Null when there is only one window, since then the renewal IS the next reset and printing it
 * twice under different names is worse than leaving it out.
 */
private fun renewalLabel(usage: AccountUsage, nowMs: Long): String? {
    val windows = usage.snapshot?.windows.orEmpty()
    if (windows.size < 2) return null
    // `periodSeconds` is nullable — a provider that does not state a window's duration
    // sorts below every window that does, rather than being treated as the longest.
    val longest = windows.maxByOrNull { it.periodSeconds ?: -1L } ?: return null
    val soonest = windows.mapNotNull { it.resetAt }.filter { it > nowMs }.minOrNull()
    val renewsAt = longest.resetAt?.takeIf { it > nowMs } ?: return null
    if (renewsAt == soonest) return null
    return "${longest.label} renews ${Countdown.format(renewsAt - nowMs)}"
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
    showTier: Boolean = true,
    showRenewal: Boolean = false,
    // Before `onClick`, so the trailing-lambda call sites keep binding their lambda to the
    // click and not to this.
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val snapshot = usage.snapshot
    val severity = snapshot?.severityAt(nowMs, staleAfterMs) ?: Severity.STALE

    UsageCard(modifier = modifier.clickable(onClick = onClick)) {
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
                        if (showTier) {
                            usage.account.plan
                                ?.takeIf { it.isNotBlank() }
                                ?.let { append(" ").append(it) }
                        }
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
                if (showRenewal) {
                    renewalLabel(usage, nowMs)?.let { renewal ->
                        Text(
                            text = renewal,
                            style = MaterialTheme.typography.bodySmall,
                            color = UsageColors.TextTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            StatusPill(severity)
            Icon(
                imageVector = AppIcons.ChevronRight,
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
