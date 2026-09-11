package com.usagelimits.feature.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.time.Countdown
import com.usagelimits.feature.overview.providerSymbol
import com.usagelimits.feature.overview.providerTint
import com.usagelimits.ui.components.IconBadge
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.StatusPill
import com.usagelimits.ui.components.ToggleRow
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.components.UsageWindowRow
import com.usagelimits.ui.theme.UsageColors

/**
 * One account in full: every window, its reset credits, and account management.
 *
 * This is the only place a reset credit can be spent, and only behind a confirmation dialog —
 * see [ResetCreditCard].
 */
@Composable
fun AccountDetailScreen(
    usage: AccountUsage?,
    nowMs: Long,
    staleAfterMs: Long,
    resetInFlight: Boolean,
    supportsResetCredits: Boolean,
    onRefresh: () -> Unit,
    onConsumeResetCredit: () -> Unit,
    onRemove: () -> Unit,
    notificationsEnabled: Boolean,
    onNotificationsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (usage == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(UsageColors.Background)
                .padding(16.dp),
        ) {
            Text(
                text = "This account is no longer available.",
                style = MaterialTheme.typography.bodyLarge,
                color = UsageColors.TextSecondary,
            )
        }
        return
    }

    var showRemoveDialog by remember { mutableStateOf(false) }
    val snapshot = usage.snapshot

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    symbol = providerSymbol(usage.account.provider),
                    tint = providerTint(usage.account.provider),
                    container = UsageColors.SurfaceElevated,
                    size = 46.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = buildString {
                            append(usage.account.provider.displayName)
                            usage.account.plan?.takeIf { it.isNotBlank() }
                                ?.let { append(" ").append(it) }
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = UsageColors.TextPrimary,
                    )
                    Text(
                        text = usage.account.maskedEmail ?: usage.account.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = UsageColors.TextSecondary,
                    )
                }
                snapshot?.severityAt(nowMs, staleAfterMs)?.let { StatusPill(it) }
            }
        }

        item {
            Text(
                text = Countdown.freshnessLabel(snapshot?.fetchedAt, nowMs),
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.TextTertiary,
            )
        }

        snapshot?.errorMessage?.let { error ->
            item {
                UsageCard(borderColor = UsageColors.AmberSurface) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = UsageColors.Amber,
                    )
                    Text(
                        text = "The figures below are the last values that loaded successfully.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = UsageColors.TextSecondary,
                    )
                }
            }
        }

        val windows = snapshot?.windows.orEmpty()
        if (windows.isNotEmpty()) {
            item { SectionHeader("Limits") }
            // Grouped windows keep their heading so a shared quota bucket reads as one thing.
            val ungrouped = windows.filter { it.group == null }
            if (ungrouped.isNotEmpty()) {
                item {
                    UsageCard {
                        ungrouped.forEach { window ->
                            UsageWindowRow(window, nowMs)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            windows.filter { it.group != null }.groupBy { it.group!! }.forEach { (group, rows) ->
                item(key = "group-$group") {
                    UsageCard {
                        Text(
                            text = group,
                            style = MaterialTheme.typography.titleMedium,
                            color = UsageColors.TextPrimary,
                        )
                        Spacer(Modifier.height(8.dp))
                        rows.forEach { window ->
                            UsageWindowRow(window, nowMs)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        if (supportsResetCredits) {
            item { SectionHeader("Reset credits") }
            item {
                ResetCreditCard(
                    heldCount = snapshot?.heldResetCredits ?: 0,
                    spendableCount = snapshot?.spendableResetCredits ?: 0,
                    expiresAt = snapshot?.resetCredits?.mapNotNull { it.expiresAt }?.minOrNull(),
                    nowMs = nowMs,
                    inFlight = resetInFlight,
                    onConsume = onConsumeResetCredit,
                )
            }
        }

        item { SectionHeader("Account") }
        item {
            UsageCard {
                // Per account, because the global switches in Settings decide WHICH kinds of
                // alert exist, not which accounts may raise them. Someone with a spare account
                // they never run down does not want to turn off "below 20%" for the account
                // they live in.
                ToggleRow(
                    title = "Notifications",
                    subtitle = "Alerts about this account's limits, resets and credits. Its " +
                        "numbers keep updating either way.",
                    checked = notificationsEnabled,
                    onChange = onNotificationsChange,
                )
            }
        }

        item {
            UsageCard {
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UsageColors.SurfaceElevated,
                        contentColor = UsageColors.TextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Refresh now") }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showRemoveDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remove account", color = UsageColors.Red) }
            }
        }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            containerColor = UsageColors.SurfaceElevated,
            title = { Text("Remove account?", color = UsageColors.TextPrimary) },
            text = {
                Text(
                    "Its stored credentials are deleted from this device. Nothing changes on " +
                        "the provider side, and you can add the account again at any time.",
                    color = UsageColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveDialog = false
                    onRemove()
                }) { Text("Remove", color = UsageColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text("Cancel", color = UsageColors.TextSecondary)
                }
            },
        )
    }
}

/**
 * Reset credits, and the only path that spends one.
 *
 * Spending is irreversible, so it is gated behind an explicit confirmation that states the
 * cost in plain words. Nothing in the app spends a credit automatically — not sync, not a
 * retry, not a widget.
 */
@Composable
private fun ResetCreditCard(
    heldCount: Int,
    spendableCount: Int,
    expiresAt: Long?,
    nowMs: Long,
    inFlight: Boolean,
    onConsume: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }

    // The provider reports credits held and credits that apply to the limit currently reached
    // as separate numbers. Showing the held count keeps a user who owns credits from being
    // told they have none, while gating the button on the spendable count keeps the app from
    // offering a tap the provider will refuse.
    val heldButUnspendable = heldCount > 0 && spendableCount == 0

    UsageCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (heldCount > 0) "$heldCount available" else "None available",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (heldCount > 0) UsageColors.Terracotta else UsageColors.TextSecondary,
                )
                Text(
                    text = when {
                        heldButUnspendable -> "None apply to your current limit yet"
                        expiresAt != null -> "Expires in ${Countdown.format(expiresAt - nowMs)}"
                        else -> "Credits appear here when your plan grants one"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.TextSecondary,
                )
            }
            if (inFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = UsageColors.Terracotta,
                )
            }
        }
        if (spendableCount > 0) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { showConfirm = true },
                enabled = !inFlight,
                colors = ButtonDefaults.buttonColors(
                    containerColor = UsageColors.Terracotta,
                    contentColor = UsageColors.Background,
                    disabledContainerColor = UsageColors.SurfaceMuted,
                    disabledContentColor = UsageColors.TextTertiary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset limit") }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = UsageColors.SurfaceElevated,
            title = { Text("Use a reset credit?", color = UsageColors.TextPrimary) },
            text = {
                Text(
                    "This consumes 1 reset credit and cannot be undone. Your rate limit will " +
                        "be reset immediately.",
                    color = UsageColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onConsume()
                }) { Text("Use credit", color = UsageColors.Terracotta) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel", color = UsageColors.TextSecondary)
                }
            },
        )
    }
}
