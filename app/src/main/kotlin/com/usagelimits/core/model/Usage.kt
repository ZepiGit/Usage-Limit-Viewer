package com.usagelimits.core.model

/**
 * How long a quota window spans. Derived from the window duration reported by the provider
 * rather than from its position in the payload, because providers reorder and rename windows.
 */
enum class WindowCategory {
    FIVE_HOUR,
    WEEKLY,
    MONTHLY,
    OTHER;

    companion object {
        private const val FIVE_HOURS_SECONDS = 5L * 60 * 60          // 18_000
        private const val WEEK_SECONDS = 7L * 24 * 60 * 60           // 604_800
        private const val MIN_MONTH_SECONDS = 28L * 24 * 60 * 60     // 2_419_200
        private const val MAX_MONTH_SECONDS = 31L * 24 * 60 * 60     // 2_678_400

        /**
         * Classifies a window by its declared duration.
         *
         * Months are a range, not a constant, because calendar months differ in length and
         * providers report the real period. Anything else stays [OTHER] so an unknown future
         * window still renders instead of being forced into the wrong bucket.
         */
        fun fromPeriodSeconds(periodSeconds: Long?): WindowCategory = when {
            periodSeconds == null -> OTHER
            periodSeconds == FIVE_HOURS_SECONDS -> FIVE_HOUR
            periodSeconds == WEEK_SECONDS -> WEEKLY
            periodSeconds in MIN_MONTH_SECONDS..MAX_MONTH_SECONDS -> MONTHLY
            else -> OTHER
        }
    }
}

/**
 * One quota bar in the UI, normalised from whatever shape the provider used.
 *
 * The app's display value is [remainingPercent]: the reference design reads
 * "82 % remaining", while every provider reports consumption instead. Both are kept so a
 * screen can show either without re-deriving and rounding twice.
 */
data class UsageWindow(
    val id: String,
    val label: String,
    val category: WindowCategory,
    val usedPercent: Double?,
    val periodSeconds: Long?,
    /** Epoch millis when the window rolls over, if the provider says so. */
    val resetAt: Long?,
    val exhausted: Boolean,
    /** Optional grouping key, e.g. an Antigravity quota group or Codex "code review". */
    val group: String? = null,
) {
    val remainingPercent: Double?
        get() {
            // A non-finite figure is UNKNOWN, not zero and not full. A limit of zero divided
            // into anything yields one, and the clamp propagates it rather than catching it:
            // NaN compares false against every bound, so coerceIn hands it straight back. The
            // iOS twin refuses it for the same reason, where the consequence is worse — Swift's
            // Int conversion traps on it inside a widget extension rather than saturating.
            val used = usedPercent ?: return null
            if (!used.isFinite()) return null
            return (100.0 - used).coerceIn(0.0, 100.0)
        }

    val severity: Severity
        get() = Severity.fromRemainingPercent(remainingPercent, exhausted)
}

/** Why a snapshot looks the way it does. Drives the "stale"/"failed" banners. */
enum class SnapshotStatus { OK, PARTIAL, FAILED }

/**
 * The result of one usage fetch for one account.
 *
 * [fetchedAt] is what the UI ages ("last updated 37 minutes ago"). A [FAILED] snapshot still
 * carries the previous [windows] so the app can keep showing known-stale numbers rather than
 * blanking the screen.
 */
data class UsageSnapshot(
    val accountId: String,
    val fetchedAt: Long,
    val status: SnapshotStatus,
    val windows: List<UsageWindow>,
    val resetCredits: List<ResetCredit> = emptyList(),
    /** Provider-reported count; authoritative over [resetCredits].size when present. */
    val resetCreditCount: Int? = null,
    /** Of [resetCreditCount], how many apply to the limit currently reached. Null if unstated. */
    val applicableResetCreditCount: Int? = null,
    val errorMessage: String? = null,
) {
    /**
     * How many credits the user holds, spendable or not — the number worth displaying.
     *
     * The provider's count is authoritative when it states one, because the row list can be
     * truncated or filtered while the count stays exact. Falling back to the rows counts only
     * the AVAILABLE ones: a spent credit is still listed, and counting it told a user with one
     * consumed credit and nothing else that they held one to spend.
     */
    val heldResetCredits: Int
        get() = resetCreditCount
            ?: resetCredits.count { it.status.equals("available", ignoreCase = true) }

    /**
     * How many credits can actually be spent right now — what the redeem button is gated on.
     *
     * Falls back to the held count when the provider does not distinguish, so a provider that
     * only reports one number keeps working exactly as before.
     */
    val spendableResetCredits: Int
        get() = applicableResetCreditCount ?: heldResetCredits

    /** The window closest to running out — what the summary card leads with. */
    val mostCritical: UsageWindow?
        get() = windows.minByOrNull { it.remainingPercent ?: Double.MAX_VALUE }

    /** The next rollover across all windows, used by the Resets screen and the widgets. */
    val nextReset: Long?
        get() = windows.mapNotNull { it.resetAt }.minOrNull()

    /**
     * Severity ignoring age. Prefer [severityAt] wherever a clock is available.
     */
    val severity: Severity
        get() = when (status) {
            SnapshotStatus.FAILED -> Severity.ERROR
            else -> windows.maxOfOrNull { it.severity } ?: Severity.ERROR
        }

    /**
     * Severity including staleness.
     *
     * Age has to be part of the verdict. Without it, a snapshot that stopped refreshing —
     * doze, no network, an OEM battery manager — keeps whatever pill it had when it last
     * succeeded, so day-old numbers still read "Healthy". Showing a confidently green status
     * over stale data is the exact failure this app exists to prevent.
     */
    fun severityAt(nowMs: Long, staleAfterMs: Long = Severity.STALE_AFTER_MS): Severity {
        val base = severity
        if (base == Severity.ERROR) return base
        val age = nowMs - fetchedAt
        return if (age >= staleAfterMs) Severity.STALE else base
    }

    fun isStaleAt(nowMs: Long, staleAfterMs: Long = Severity.STALE_AFTER_MS): Boolean =
        nowMs - fetchedAt >= staleAfterMs
}

/**
 * A Codex rate-limit reset credit.
 *
 * Only credits the provider reports as available *and* of the Codex rate-limit type are
 * modelled; see CodexUsageParser.parseResetCredits for the filtering rules.
 */
data class ResetCredit(
    val id: String,
    val grantedAt: Long?,
    val expiresAt: Long?,
    val status: String,
)
