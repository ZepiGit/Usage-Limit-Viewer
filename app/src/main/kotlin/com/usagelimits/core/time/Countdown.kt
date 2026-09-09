package com.usagelimits.core.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Countdown and staleness formatting.
 *
 * Kept free of Android APIs so it is unit-testable and behaves identically on the main screen
 * and inside a widget, where a Composable context may not be available. `java.time` is fine
 * here: `minSdk` is 26, which is where it landed natively.
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

    /**
     * "Reset in 1h 16m", or "Reset due" once the instant has passed.
     *
     * For a surface that re-renders on a clock. A widget does not — see [absoluteResetLabel].
     */
    fun resetLabel(resetAt: Long?, nowMs: Long): String? {
        if (resetAt == null) return null
        val remaining = resetAt - nowMs
        return if (remaining <= 0) "Reset due" else "Reset in ${format(remaining)}"
    }

    /**
     * "Resets 14:05", "Resets Tue 09:00", "Resets 16 Sep".
     *
     * The form a surface that cannot tick must use. A Glance widget recomposes only when its
     * worker updates it, so a relative countdown is frozen at composition: rendered as
     * "Reset in 20m" and read twenty-five minutes later, it is not merely stale but wrong in
     * the one direction that matters, because the limit has already reset and the user is
     * still being told to wait. An absolute instant stays true however old the render is.
     *
     * The day is included beyond today because a bare clock time cannot say whether a weekly
     * limit returns this evening or next Tuesday, and dropped within today because that is the
     * common case and the date would only be noise.
     */
    fun absoluteResetLabel(
        resetAt: Long?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String? {
        if (resetAt == null) return null
        if (resetAt <= nowMs) return "Reset due"

        val reset = Instant.ofEpochMilli(resetAt).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, reset.toLocalDate())

        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .format(reset)

        return when {
            days == 0L -> "Resets $time"
            // Inside a week the weekday is the most readable anchor; beyond it the day of the
            // month is, because "Tue" stops being unambiguous once more than one has passed.
            days in 1..6 -> "Resets ${
                DateTimeFormatter.ofPattern("EEE", locale).format(reset)
            } $time"
            else -> "Resets ${DateTimeFormatter.ofPattern("d MMM", locale).format(reset)}"
        }
    }

    /** "Updated just now" / "Updated 37m ago" — for a surface that re-renders on a clock. */
    fun freshnessLabel(fetchedAt: Long?, nowMs: Long): String {
        if (fetchedAt == null) return "Never updated"
        val age = nowMs - fetchedAt
        if (age < 60_000) return "Updated just now"
        return "Updated ${format(age)} ago"
    }

    /**
     * "As of 12:40" — the widget's freshness line.
     *
     * Same reasoning as [absoluteResetLabel] and the same defect avoided: "Updated 2m ago"
     * composed once and left on the home screen for three hours claims the numbers are two
     * minutes old for as long as the widget is not refreshed. A wall-clock stamp cannot age
     * into a lie, and it is what lets a reader judge the numbers for themselves.
     */
    fun asOfLabel(
        fetchedAt: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (fetchedAt == null) return "Never updated"
        val stamp = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(fetchedAt).atZone(zone))
        return "As of $stamp"
    }
}
