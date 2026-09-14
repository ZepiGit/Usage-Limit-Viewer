package com.usagelimits.providers.kimi

import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import kotlinx.serialization.json.JsonObject

/**
 * Reads Kimi Code's `GET /coding/v1/usages`.
 *
 * The response carries two kinds of window, and — this is the part worth knowing — they report
 * consumption in OPPOSITE terms:
 *
 *   - the weekly block under `usage` has `limit` and `used`, and never `remaining`
 *   - each entry in `limits[].detail` has `limit` and `remaining`, and never `used`
 *
 * Reading only `remaining` therefore computes nothing at all for the weekly window, which
 * renders as an untouched bar on an account that has spent its whole week. That is a real
 * defect other clients shipped and later fixed; this parser takes either field from the start
 * and derives the percentage from whichever one is present.
 */
object KimiUsageParser {

    private const val FIVE_HOURS_SECONDS = 5L * 60 * 60
    private const val WEEK_SECONDS = 7L * 24 * 60 * 60

    /**
     * Epoch seconds for any date this decade are about 1.8e9; epoch millis about 1.8e12.
     * Anything below this is seconds.
     */
    private const val SECONDS_CUTOFF = 100_000_000_000L

    fun parse(payload: JsonObject): List<UsageWindow> {
        val windows = mutableListOf<UsageWindow>()
        weeklyWindow(payload)?.let(windows::add)
        windows += rateLimitWindows(payload)
        return windows
    }

    /** The membership's weekly request allowance, which Kimi states as `used`. */
    private fun weeklyWindow(payload: JsonObject): UsageWindow? {
        val usage = JsonSupport.obj(payload, "usage") ?: return null
        val percent = usedPercent(
            limit = JsonSupport.double(usage, "limit", "total"),
            used = JsonSupport.double(usage, "used"),
            remaining = JsonSupport.double(usage, "remaining"),
        ) ?: return null

        return UsageWindow(
            id = "kimi-weekly",
            label = "Weekly",
            category = WindowCategory.WEEKLY,
            usedPercent = percent,
            periodSeconds = WEEK_SECONDS,
            resetAt = resetInstant(usage),
            exhausted = percent >= 100.0,
        )
    }

    /**
     * The rolling rate limits, which is where the five-hour window lives.
     *
     * The duration is read from the entry rather than assumed from its position. Classifying
     * by position is how a monthly bucket ends up labelled as a week.
     */
    private fun rateLimitWindows(payload: JsonObject): List<UsageWindow> =
        JsonSupport.array(payload, "limits").mapIndexedNotNull { index, element ->
            val entry = element as? JsonObject ?: return@mapIndexedNotNull null
            val detail = JsonSupport.obj(entry, "detail") ?: entry

            val percent = usedPercent(
                limit = JsonSupport.double(detail, "limit", "total"),
                used = JsonSupport.double(detail, "used"),
                remaining = JsonSupport.double(detail, "remaining"),
            ) ?: return@mapIndexedNotNull null

            val seconds = JsonSupport.long(entry, "windowSeconds", "window_seconds")
                ?: JsonSupport.long(detail, "windowSeconds", "window_seconds")
                ?: FIVE_HOURS_SECONDS
            val label = JsonSupport.string(entry, "name", "label")
                ?: JsonSupport.string(detail, "name", "label")
                ?: defaultLabel(seconds)

            UsageWindow(
                id = "kimi-limit-$index",
                label = label,
                category = WindowCategory.fromPeriodSeconds(seconds),
                usedPercent = percent,
                periodSeconds = seconds,
                resetAt = resetInstant(detail) ?: resetInstant(entry),
                exhausted = percent >= 100.0,
            )
        }

    /**
     * Consumption as a percentage, from whichever of the two fields the block carries.
     *
     * `used` wins when both are present: the weekly block states it directly, and a derived
     * number should never override a reported one.
     */
    private fun usedPercent(limit: Double?, used: Double?, remaining: Double?): Double? {
        // A zero limit is not a full bar — it is a provider that stated no limit, and dividing
        // by it gives infinity or NaN.
        if (limit == null || limit <= 0.0) return null
        val consumed = used ?: remaining?.let { limit - it } ?: return null
        return (consumed / limit * 100.0).coerceIn(0.0, 100.0)
    }

    /** The reset instant, in whichever unit it arrived. */
    private fun resetInstant(obj: JsonObject?): Long? {
        if (obj == null) return null
        val raw = JsonSupport.long(obj, "resetTime", "reset_time", "resetAt", "reset_at")
            ?: return null
        if (raw <= 0) return null
        return if (raw < SECONDS_CUTOFF) raw * 1000 else raw
    }

    private fun defaultLabel(seconds: Long): String = when (seconds) {
        FIVE_HOURS_SECONDS -> "5h limit"
        WEEK_SECONDS -> "Weekly"
        else -> "Rate limit"
    }
}
