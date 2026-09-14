package com.usagelimits.feature.accounts

import com.usagelimits.ui.theme.LocalMotionEnabled
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.overview.AccountCard
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors

/** Filters over the account list. Kept local to the screen — it is view state, not app state. */
internal data class AccountFilter(
    val provider: ProviderId? = null,
    val onlyProblems: Boolean = false,
) {
    fun matches(usage: AccountUsage): Boolean {
        if (provider != null && usage.account.provider != provider) return false
        if (onlyProblems) {
            return usage.snapshot?.connectionStatus == com.usagelimits.core.model.ConnectionStatus.RECONNECT_REQUIRED
        }
        return true
    }
}

/**
 * The full account list with provider and status filters.
 *
 * Reuses the Overview card so an account looks identical wherever it appears — two divergent
 * renderings of the same thing is how a list stops feeling like one app.
 */
@Composable
fun AccountsScreen(
    state: UsageUiState,
    nowMs: Long,
    onAccountClick: (String) -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(AccountFilter()) }
    val visible = state.accounts.filter(filter::matches)
    val presentProviders = state.accounts.map { it.account.provider }.distinct()

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
                    text = "Accounts",
                    style = MaterialTheme.typography.headlineLarge,
                    color = UsageColors.TextPrimary,
                )
                Text(
                    text = "${visible.size} of ${state.accountCount} shown",
                    style = MaterialTheme.typography.bodyLarge,
                    color = UsageColors.TextSecondary,
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip("All", filter.provider == null && !filter.onlyProblems) {
                    filter = AccountFilter()
                }
                FilterChip("Needs attention", filter.onlyProblems) {
                    filter = filter.copy(onlyProblems = !filter.onlyProblems)
                }
                presentProviders.forEach { provider ->
                    FilterChip(provider.displayName, filter.provider == provider) {
                        filter = filter.copy(
                            provider = if (filter.provider == provider) null else provider,
                        )
                    }
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                UsageCard {
                    Text(
                        text = if (state.accounts.isEmpty()) {
                            "No accounts yet. Add one to start tracking usage."
                        } else {
                            "No accounts match this filter."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = UsageColors.TextSecondary,
                    )
                }
            }
        }

        items(visible, key = { it.account.localId }) { usage ->
            AccountCard(usage, nowMs, state.staleAfterMs,
                modifier = if (LocalMotionEnabled.current) Modifier.animateItem() else Modifier,
            ) {
                onAccountClick(usage.account.localId)
            }
        }

        item {
            Text(
                text = "+ Add account",
                style = MaterialTheme.typography.titleMedium,
                color = UsageColors.Terracotta,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAddAccount)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) UsageColors.Background else UsageColors.TextSecondary,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) UsageColors.Terracotta else UsageColors.SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
