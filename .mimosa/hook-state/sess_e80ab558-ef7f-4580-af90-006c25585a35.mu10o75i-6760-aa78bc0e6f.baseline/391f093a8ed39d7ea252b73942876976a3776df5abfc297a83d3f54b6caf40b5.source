package com.usagelimits.providers.antigravity

import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.time.Instants
import kotlinx.serialization.json.JsonObject

/**
 * Turns the Antigravity `retrieveUserQuotaSummary` payload into normalised windows.
 *
 * The payload is already grouped:
 *
 * ```
 * { "groups": [ { "displayName": "Gemini Pro",
 *                 "buckets": [ { "bucketId": "...", "window": "5h", "remainingFraction": 0.53 } ] } ] }
 * ```
 *
 * The grouping is the point. Antigravity meters dozens of models against a handful of shared
 * quota buckets, and the server has already collapsed them — so this parser reads the buckets
 * and never enumerates models. Doing it the other way round is what produces hundreds of
 * near-identical rows for what is really one limit.
 *
 * [UsageWindow.group] carries the group's display name through to the UI, which renders one
 * card per group and therefore shows each shared bucket exactly once.
 */
object AntigravityQuotaParser {

    /**
     * Parses the quota summary.
     *
     * [nowMs] is part of the parser contract shared with the other providers; Antigravity
     * reports absolute `resetTime` stamps only, so nothing here currently needs it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun parse(payload: JsonObject, nowMs: Long): List<UsageWindow> {
        val windows = mutableListOf<UsageWindow>()
        JsonSupport.array(payload, "groups").forEachIndexed { index, element ->
            val group = element as? JsonObject ?: return@forEachIndexed
            windows += windowsFor(group, index)
        }
        return windows
    }

    /**
     * Buckets of one group, ordered and filtered.
     *
     * A group whose buckets all drop out contributes nothing, so an entirely unusable group
     * disappears rather than rendering as an empty card.
     */
    private fun windowsFor(group: JsonObject, groupIndex: Int): List<UsageWindow> {
        val groupName = JsonSupport.string(group, "display_name", "displayName")
        // Only used to build fallback ids, so an unnamed group still yields stable, unique ones.
        val groupSlug = slug(groupName ?: "group-$groupIndex")

        return JsonSupport.array(group, "buckets")
            .mapIndexedNotNull { bucketIndex, element ->
                val bucket = element as? JsonObject ?: return@mapIndexedNotNull null
                toWindow(bucket, groupName, groupSlug, bucketIndex)
            }
            .sortedWith(BUCKET_ORDER)
    }

    private fun toWindow(
        bucket: JsonObject,
        groupName: String?,
        groupSlug: String,
        bucketIndex: Int,
    ): UsageWindow? {
        // The only field the row cannot be drawn without. A bucket that reports no fraction is
        // skipped so the rest of the group still renders.
        val remainingFraction =
            JsonSupport.double(bucket, "remaining_fraction", "remainingFraction") ?: return null
        val clamped = remainingFraction.coerceIn(0.0, 1.0)

        val window = JsonSupport.string(bucket, "window")
        val (category, periodSeconds) = classify(window)

        val id = JsonSupport.string(bucket, "bucket_id", "bucketId")
            ?: "$groupSlug-${window?.let(::slug) ?: bucketIndex}"

        return UsageWindow(
            id = id,
            label = JsonSupport.string(bucket, "display_name", "displayName") ?: id,
            category = category,
            // The provider reports what is left; the app's model stores what was consumed.
            usedPercent = (1.0 - clamped) * 100.0,
            periodSeconds = periodSeconds,
            resetAt = Instants.parse(JsonSupport.string(bucket, "reset_time", "resetTime")),
            exhausted = clamped <= 0.0,
            group = groupName,
        )
    }

    /**
     * Maps the declared window name onto a category and its duration.
     *
     * The names are free-form strings rather than an enum upstream, and both `5h` and
     * `five_hour` have been observed, so matching is case-insensitive over a small alias set.
     * Anything unrecognised stays [WindowCategory.OTHER] with no duration, which still renders
     * — a window this app has not seen before is better shown unclassified than dropped.
     */
    private fun classify(window: String?): Pair<WindowCategory, Long?> =
        when (window?.trim()?.lowercase()) {
            "5h", "five-hour", "five_hour" -> WindowCategory.FIVE_HOUR to FIVE_HOURS_SECONDS
            "weekly", "week" -> WindowCategory.WEEKLY to WEEK_SECONDS
            else -> WindowCategory.OTHER to null
        }

    /**
     * 5-hour first, then weekly, then the rest alphabetically.
     *
     * The two known windows are pinned to a fixed order because the UI reads top-to-bottom as
     * "soonest limit first"; unknown windows sort by label only so their order is stable
     * across refreshes regardless of how the server happens to emit them.
     */
    private val BUCKET_ORDER = compareBy<UsageWindow>(
        { rank(it.category) },
        { if (rank(it.category) == RANK_OTHER) it.label.lowercase() else "" },
    )

    private fun rank(category: WindowCategory): Int = when (category) {
        WindowCategory.FIVE_HOUR -> 0
        WindowCategory.WEEKLY -> 1
        else -> RANK_OTHER
    }

    private fun slug(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "quota" }

    private const val RANK_OTHER = 2
    private const val FIVE_HOURS_SECONDS = 18_000L
    private const val WEEK_SECONDS = 604_800L
}
