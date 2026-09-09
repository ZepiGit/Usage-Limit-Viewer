package com.usagelimits.core.time

/**
 * Countdown and staleness formatting.
 *
 * Kept free of Android and locale APIs so it is unit-testable and behaves identically on the
 * main screen and inside a widget, where a Composable context may not be available.
 */
object Countdown {

    /**
     * "1h 16m", "5d 2h", "now".
     *
     * Resolution drops as the distance grows — days-and-hours past a day, hours-and-minutes
     * below that — which is what the reference design shows and keeps the text narrow enough
     * for a widget row.
     */
    fun format(remainingMs: Long): String {
        if (remainingMs <= 0) return "now"
        val totalMinutes = remainingMs / 60_000
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 && hours > 0 -> "${days}d ${hours}h"
            days > 0 -> "${days}d"
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }

    /** "Reset in 1h 16m", or "Reset due" once the instant has passed. */
    fun resetLabel(resetAt: Long?, nowMs: Long): String? {
        if (resetAt == null) return null
        val remaining = resetAt - nowMs
        return if (remaining <= 0) "Reset due" else "Reset in ${format(remaining)}"
    }

    /** "Updated just now" / "Updated 37m ago" — the freshness line above the account list. */
    fun freshnessLabel(fetchedAt: Long?, nowMs: Long): String {
        if (fetchedAt == null) return "Never updated"
        val age = nowMs - fetchedAt
        if (age < 60_000) return "Updated just now"
        return "Updated ${format(age)} ago"
    }
}
