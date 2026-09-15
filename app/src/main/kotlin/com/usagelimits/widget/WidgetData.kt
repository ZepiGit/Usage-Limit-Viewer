package com.usagelimits.widget

import com.usagelimits.core.model.title
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WidgetScope
import com.usagelimits.core.model.WindowCategory

/** One bar in a widget. */
data class WidgetRow(
    val label: String,
    /** Carried so a headline can be chosen by horizon rather than by position in [rows]. */
    val category: WindowCategory,
    val remainingPercent: Double?,
    val resetAt: Long?,
    val severity: Severity,
    /**
     * The window's reset instant has passed since this snapshot was fetched.
     *
     * The cached figure is then not old but WRONG: the provider has opened a fresh window and
     * this row still describes the spent one. Surfaces draw such a row in the stale treatment
     * and say "reset due" until a fetch confirms the new number — a full ring is not claimed
     * either, because nothing has reported one. A snapshot fetched after the reset is
     * unaffected: the provider had already described the new window by then.
     */
    val resetElapsed: Boolean = false,
) {
    /** The validity a surface should colour this row with, when its account's own is null. */
    val validity: Severity? get() = if (resetElapsed) Severity.STALE else null
}

/** One account block in the larger widget. */
data class WidgetAccount(
    val accountId: String,
    val title: String,
    val subtitle: String?,
    val rows: List<WidgetRow>,
    val severity: Severity,
    val providerId: String = "",
    val requiresReauthentication: Boolean = false,
    val iconChoiceId: String? = null,
    val accountLabel: String? = null,
    val resetTimes: List<Long> = rows.mapNotNull { it.resetAt },
) {
    /**
     * Whether the cached reading itself is to be trusted: STALE or ERROR while it is not, null
     * while it is. Deliberately narrower than [severity] — an exhausted account's data is
     * perfectly trustworthy, and funnelling quota severity in here would recolour a healthy
     * five-hour row red because a different, longer window ran out. A validity state may
     * override the row's own colour; a quota state may not.
     */
    val dataValidity: Severity?
        get() = when {
            requiresReauthentication -> Severity.ERROR
            severity == Severity.ERROR || severity == Severity.STALE -> severity
            else -> null
        }
}

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
    /**
     * The data validity of the account the headline numbers come from — the compact tile draws
     * its two quota tiles from this account, so its staleness is the validity those numbers
     * must answer to. Null while the lead's data is trustworthy.
     */
    val leadValidity: Severity? = null,
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
        /**
         * How old a snapshot may be before it reads as stale.
         *
         * Passed in rather than taken from a constant so a widget agrees with the app about
         * what "stale" means — and so the three-hour sync interval the app offers does not
         * grey out every tile permanently.
         */
        staleAfterMs: Long = Severity.STALE_AFTER_MS,
        customAccountIds: List<String> = emptyList(),
        providerIcons: Map<String, String> = emptyMap(),
    ): WidgetSnapshot {
        val selected = when (scope) {
            WidgetScope.ACCOUNT -> all.filter { it.account.localId == accountId }
            WidgetScope.PROVIDER -> all.filter { it.account.provider.id == providerId }
            WidgetScope.CUSTOM -> customAccountIds.distinct().mapNotNull { id -> all.firstOrNull { it.account.localId == id } }
            WidgetScope.ALL_ACCOUNTS, WidgetScope.MOST_CRITICAL, WidgetScope.CLOSEST_RESETS -> all
        }

        if (selected.isEmpty()) return WidgetSnapshot.Empty

        val accounts = selected.map { it.toWidgetAccount(nowMs, staleAfterMs, providerIcons) }

        // For the auto scope, lead with whatever is closest to running out — see [criticality].
        val ordered = if (scope == WidgetScope.MOST_CRITICAL) {
            accounts.sortedWith(
                compareBy({ criticality(it.severity) }, { band(it.tightestRemaining()) }),
            )
        } else if (scope == WidgetScope.CLOSEST_RESETS) {
            accounts.sortedBy { account ->
                selected.first { it.account.localId == account.accountId }.snapshot?.windows.orEmpty()
                    .mapNotNull { it.resetAt }.filter { it > nowMs }.minOrNull() ?: Long.MAX_VALUE
            }
        } else {
            accounts
        }

        // The headline belongs to ONE account — the one leading the list — not to a pool.
        //
        // Reducing across every account produced a number attributed to nobody. On a tile
        // showing two of six accounts, "5h: 3 %" could be the sixth account's window, so the
        // user read a figure they could not locate. Worse, `weekly ?: monthly` applied to the
        // pool meant any one account having a weekly window suppressed every monthly one, so
        // the headline could read 80 % while a visible row read 3 %.
        val lead = ordered.firstOrNull()

        return WidgetSnapshot(
            accounts = ordered,
            accountCount = selected.size,
            updatedAt = selected.mapNotNull { it.snapshot?.fetchedAt }.maxOrNull(),
            // The soonest reset OF THE LEADING ACCOUNT. Taken across all accounts it paired the
            // headline state with an unrelated account's clock: "0 % left · resets in 12m",
            // where the twelve minutes belonged to a healthy account's five-hour window. And
            // because healthy five-hour windows reset constantly, that was the common case.
            // And one still AHEAD. A snapshot keeps a window's reset instant until the next fetch
            // replaces it, so once one had passed the minimum was anchored in the past and the
            // compact widget's tile named a reset that was already over. The overview's view
            // model drops passed instants for the same reason.
            nextResetAt = lead?.resetTimes?.filter { it > nowMs }?.minOrNull(),
            // Deliberately NOT the leading account's severity. This answers "is anything wrong
            // anywhere", which is a different question from "what should I look at first" —
            // and it is the only thing that still surfaces a broken account once the ordering
            // below stops letting rowless accounts occupy a two-slot widget.
            overallSeverity = ordered.maxOfOrNull { it.severity } ?: Severity.STALE,
            headlineShort = lead?.rows?.firstOrNull { it.category == WindowCategory.FIVE_HOUR },
            headlineLong = lead?.rows?.firstOrNull { it.category != WindowCategory.FIVE_HOUR },
            leadValidity = lead?.dataValidity,
        )
    }

    /**
     * Rank for the auto scope, lowest first.
     *
     * Deliberately not [Severity.ordinal]. That enum is ordered best-to-worst so `maxOf` finds
     * an account's worst window, which puts STALE and ERROR *after* EXHAUSTED — so ranking by
     * the ordinal led with an account the app merely failed to read, above one the user has
     * genuinely run out on.
     *
     * ERROR and STALE then sort LAST, below HEALTHY, which reads wrong until you count the
     * slots. Both states are rowless — a never-fetched or failed account has no windows to show
     * — so ranking them highly filled a two-tile widget with blank cards and pushed an account
     * at 3 % off the screen entirely. A blank tile answers nothing; the account with a real
     * number does. That an account is unreadable still reaches the user through
     * [WidgetSnapshot.overallSeverity] and through the app's own list, neither of which is
     * slot-limited.
     */
    private fun criticality(severity: Severity): Int = severity.urgency

    /**
     * Tie-break within one rank: the tightest number the card actually shows, ascending.
     *
     * An account with no readable number sorts last inside its rank — there is nothing to
     * compare, and a known percentage is the more useful thing to put first.
     */
    private fun WidgetAccount.tightestRemaining(): Double =
        rows.mapNotNull { it.remainingPercent }.minOrNull() ?: Double.MAX_VALUE

    /**
     * Five-point buckets, so sub-point drift cannot reorder a home screen.
     *
     * Two healthy accounts drifting 47.2 to 46.8 and 46.9 to 47.1 swapped places on every
     * refresh. On a home screen that means the reader re-scans from scratch each time and stops
     * trusting the position of anything.
     */
    private fun band(remaining: Double): Int =
        if (remaining == Double.MAX_VALUE) Int.MAX_VALUE else (remaining / 5).toInt()

    /** The tightest window of a category — the number worth surfacing in one tile. */
    /**
     * The tightest window of a category — the number worth surfacing in one row.
     *
     * An unknown percentage sorts as MOST urgent, not least. It resolves to [Severity.ERROR],
     * so treating it as the largest possible number made the one window the app could not read
     * the last one it would ever show.
     */
    private fun headline(windows: List<UsageWindow>, category: WindowCategory, fetchedAt: Long, nowMs: Long): WidgetRow? =
        windows.filter { it.category == category }
            .minByOrNull { it.remainingPercent ?: -1.0 }
            ?.toRow(fetchedAt, nowMs)

    /**
     * The one long-horizon row, chosen by how much it needs attention rather than by category.
     *
     * This used to be `WEEKLY ?: MONTHLY ?: OTHER`, which reads as a sensible preference and is
     * not one: the elvis short-circuits on the mere EXISTENCE of a weekly window, so an account
     * reporting a healthy weekly limit and an exhausted monthly one showed the weekly. The
     * monthly window was never evaluated. A user whose monthly quota had run out saw a full bar
     * on the home screen, which is the single worst thing this app can show — and the widget is
     * where they were most likely to look.
     *
     * [Severity.urgency] is the ranking, because the file that defines it says it is the one
     * answer the overview list and the widget both use, so they cannot disagree. It also settles
     * what an unreadable window is worth here: ERROR sits BELOW exhausted, so a window whose
     * percentage could not be read does not displace one known to be empty. Ties go to the lower
     * remaining percentage, and then to the better-understood category — which is what keeps
     * OTHER, whose duration no provider documents, a last resort among equals rather than a
     * category that can never appear.
     */
    private fun longHeadline(windows: List<UsageWindow>, fetchedAt: Long, nowMs: Long): WidgetRow? =
        windows.filter { it.category != WindowCategory.FIVE_HOUR }
            .minWithOrNull(
                compareBy(
                    { it.severity.urgency },
                    { it.remainingPercent ?: Double.MAX_VALUE },
                    { longCategoryRank(it.category) },
                )
            )
            ?.toRow(fetchedAt, nowMs)

    private fun longCategoryRank(category: WindowCategory): Int = when (category) {
        WindowCategory.WEEKLY -> 0
        WindowCategory.MONTHLY -> 1
        else -> 2
    }

    private fun AccountUsage.toWidgetAccount(nowMs: Long, staleAfterMs: Long, providerIcons: Map<String, String>): WidgetAccount {
        val windows = snapshot?.windows.orEmpty()
        val fetchedAt = snapshot?.fetchedAt ?: Long.MAX_VALUE

        // Show the two horizons that matter, not every window an account reports — a widget
        // has room for about two rows before it stops being glanceable.
        val rows = listOfNotNull(
            headline(windows, WindowCategory.FIVE_HOUR, fetchedAt, nowMs),
            longHeadline(windows, fetchedAt, nowMs),
        )

        return WidgetAccount(
            accountId = account.localId,
            title = account.title(),
            subtitle = account.maskedEmail,
            rows = rows,
            severity = snapshot?.severityAt(nowMs, staleAfterMs) ?: Severity.STALE,
            providerId = account.provider.id,
            iconChoiceId = providerIcons[account.provider.id],
            accountLabel = account.label,
            requiresReauthentication = snapshot?.connectionStatus == com.usagelimits.core.model.ConnectionStatus.RECONNECT_REQUIRED,
            resetTimes = windows.mapNotNull { it.resetAt },
        )
    }

    private fun UsageWindow.toRow(fetchedAt: Long, nowMs: Long) = WidgetRow(
        category = category,
        label = when (category) {
            WindowCategory.FIVE_HOUR -> "5h limit"
            WindowCategory.WEEKLY -> "Weekly"
            WindowCategory.MONTHLY -> "Monthly"
            WindowCategory.OTHER -> label
        },
        remainingPercent = remainingPercent,
        resetAt = resetAt,
        severity = severity,
        resetElapsed = resetElapsedAt(resetAt, fetchedAt, nowMs),
    )

    /**
     * Whether a window's reset has come and gone since its snapshot was taken. Pure, and the
     * same rule `SyncWorker.cacheNeedsSync` fetches on, so the tile that says "reset due" and
     * the sync that answers it cannot disagree about when that is.
     */
    fun resetElapsedAt(resetAt: Long?, fetchedAt: Long, nowMs: Long): Boolean =
        resetAt != null && resetAt <= nowMs && fetchedAt < resetAt
}
