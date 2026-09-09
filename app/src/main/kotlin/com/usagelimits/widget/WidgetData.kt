package com.usagelimits.widget

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WidgetScope
import com.usagelimits.core.model.WindowCategory

/** One bar in a widget. */
data class WidgetRow(
    val label: String,
    val remainingPercent: Double?,
    val resetAt: Long?,
    val severity: Severity,
)

/** One account block in the larger widget. */
data class WidgetAccount(
    val accountId: String,
    val title: String,
    val subtitle: String?,
    val rows: List<WidgetRow>,
    val severity: Severity,
)

/**
 * Everything a widget renders, already reduced from the cache.
 *
 * Contains no tokens and no provider payloads — by construction, since it is built only from
 * the normalised Room cache. That is what makes it safe to hand to the home screen process.
 */
data class WidgetSnapshot(
    val accounts: List<WidgetAccount>,
    val accountCount: Int,
    val updatedAt: Long?,
    val nextResetAt: Long?,
    val overallSeverity: Severity,
    val headlineShort: WidgetRow?,
    val headlineLong: WidgetRow?,
) {
    companion object {
        val Empty = WidgetSnapshot(
            accounts = emptyList(),
            accountCount = 0,
            updatedAt = null,
            nextResetAt = null,
            overallSeverity = Severity.STALE,
            headlineShort = null,
            headlineLong = null,
        )
    }
}

/**
 * Reduces the cache to what a given widget configuration needs.
 *
 * Kept as pure functions over already-loaded data so it is unit-testable and so widget
 * rendering never touches a provider or the credential store.
 */
object WidgetDataBuilder {

    fun build(
        all: List<AccountUsage>,
        nowMs: Long,
        scope: WidgetScope,
        accountId: String?,
        providerId: String?,
    ): WidgetSnapshot {
        val selected = when (scope) {
            WidgetScope.ACCOUNT -> all.filter { it.account.localId == accountId }
            WidgetScope.PROVIDER -> all.filter { it.account.provider.id == providerId }
            WidgetScope.ALL_ACCOUNTS, WidgetScope.MOST_CRITICAL -> all
        }

        if (selected.isEmpty()) return WidgetSnapshot.Empty

        val accounts = selected.map { it.toWidgetAccount(nowMs) }

        // For the auto scope, lead with whatever is closest to running out.
        val ordered = if (scope == WidgetScope.MOST_CRITICAL) {
            accounts.sortedByDescending { it.severity.ordinal }
        } else {
            accounts
        }

        val allWindows = selected.flatMap { it.snapshot?.windows.orEmpty() }

        return WidgetSnapshot(
            accounts = ordered,
            accountCount = selected.size,
            updatedAt = selected.mapNotNull { it.snapshot?.fetchedAt }.maxOrNull(),
            nextResetAt = allWindows.mapNotNull { it.resetAt }.minOrNull(),
            overallSeverity = ordered.maxOfOrNull { it.severity } ?: Severity.STALE,
            headlineShort = headline(allWindows, WindowCategory.FIVE_HOUR),
            // A monthly window stands in for the long horizon when a plan has no weekly one.
            headlineLong = headline(allWindows, WindowCategory.WEEKLY)
                ?: headline(allWindows, WindowCategory.MONTHLY),
        )
    }

    /** The tightest window of a category — the number worth surfacing in one tile. */
    private fun headline(windows: List<UsageWindow>, category: WindowCategory): WidgetRow? =
        windows.filter { it.category == category }
            .minByOrNull { it.remainingPercent ?: Double.MAX_VALUE }
            ?.toRow()

    private fun AccountUsage.toWidgetAccount(nowMs: Long): WidgetAccount {
        val windows = snapshot?.windows.orEmpty()

        // Show the two horizons that matter, not every window an account reports — a widget
        // has room for about two rows before it stops being glanceable.
        val rows = listOfNotNull(
            headline(windows, WindowCategory.FIVE_HOUR),
            headline(windows, WindowCategory.WEEKLY) ?: headline(windows, WindowCategory.MONTHLY),
        )

        return WidgetAccount(
            accountId = account.localId,
            title = buildString {
                append(account.provider.displayName)
                account.plan?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
            },
            subtitle = account.maskedEmail,
            rows = rows,
            severity = snapshot?.severityAt(nowMs) ?: Severity.STALE,
        )
    }

    private fun UsageWindow.toRow() = WidgetRow(
        label = when (category) {
            WindowCategory.FIVE_HOUR -> "5h limit"
            WindowCategory.WEEKLY -> "Weekly"
            WindowCategory.MONTHLY -> "Monthly"
            WindowCategory.OTHER -> label
        },
        remainingPercent = remainingPercent,
        resetAt = resetAt,
        severity = severity,
    )
}
