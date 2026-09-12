package com.usagelimits.providers.codex

import com.usagelimits.core.model.planLabel

import com.usagelimits.core.model.ResetCredit
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.time.Instants
import kotlinx.serialization.json.JsonObject

/**
 * Turns the Codex `/backend-api/wham/usage` payload into normalised windows.
 *
 * The payload nests three families of limit, each with a `primary_window` and a
 * `secondary_window`:
 *
 *  - `rate_limit` — ordinary Codex usage
 *  - `code_review_rate_limit` — code review, metered separately
 *  - `additional_rate_limits[]` — named extras that appear and disappear per plan
 *
 * Windows are classified by their declared `limit_window_seconds`, not by whether they sit in
 * the primary or secondary slot. Upstream puts a monthly window in the secondary slot for
 * team plans and a weekly one for individual plans, so trusting the position would label a
 * month as a week. Position is used only as a fallback for legacy payloads that omit the
 * duration entirely.
 */
object CodexUsageParser {

    /** Parses the full usage payload. [nowMs] resolves relative reset offsets. */
    fun parse(payload: JsonObject, nowMs: Long): List<UsageWindow> {
        val windows = mutableListOf<UsageWindow>()

        val rateLimit = JsonSupport.obj(payload, "rate_limit", "rateLimit")
        windows += windowsFor(rateLimit, idPrefix = "codex", labelPrefix = null, nowMs = nowMs)

        val codeReview = JsonSupport.obj(payload, "code_review_rate_limit", "codeReviewRateLimit")
        windows += windowsFor(
            codeReview,
            idPrefix = "code-review",
            labelPrefix = "Code review",
            nowMs = nowMs,
            group = "Code review",
        )

        JsonSupport.array(payload, "additional_rate_limits", "additionalRateLimits")
            .forEachIndexed { index, element ->
                val entry = runCatching { element as JsonObject }.getOrNull() ?: return@forEachIndexed

                // Each entry NESTS its windows under `rate_limit`; the primary/secondary pair
                // is one level deeper than on the top-level limits. Reading them off the entry
                // itself finds nothing, which would drop every extra metered feature silently
                // — and worse, leave the account's severity computed only from the windows
                // that did parse, so a spent extra limit could still read as healthy.
                // A flat entry is accepted as a fallback for older payloads.
                val info = JsonSupport.obj(entry, "rate_limit", "rateLimit") ?: entry

                val name = JsonSupport.string(
                    entry,
                    "name",
                    "limit_name",
                    "limitName",
                    "metered_feature",
                    "meteredFeature",
                ) ?: "Additional ${index + 1}"

                windows += windowsFor(
                    info,
                    idPrefix = "additional-${slug(name)}-$index",
                    labelPrefix = name,
                    nowMs = nowMs,
                    group = name,
                )
            }

        return windows
    }

    /**
     * Extracts the reset-credit summary that rides along inside the usage payload.
     *
     * The dedicated reset-credits endpoint is authoritative, but this copy lets the app show
     * a count even when that second call fails.
     */
    fun parseEmbeddedResetCredits(payload: JsonObject): List<ResetCredit> {
        val credits = JsonSupport.obj(payload, "rate_limit_reset_credits", "rateLimitResetCredits")
        return parseResetCredits(credits)
    }

    /**
     * Parses `/rate-limit-reset-credits`.
     *
     * Only credits that are both `status == "available"` and of type `codex_rate_limits` are
     * returned: the endpoint also lists spent credits and credits for unrelated resets, and
     * offering either as usable would let the user tap a button that cannot work.
     */
    fun parseResetCredits(payload: JsonObject?): List<ResetCredit> {
        if (payload == null) return emptyList()
        return JsonSupport.array(payload, "credits").mapNotNull { element ->
            val credit = runCatching { element as JsonObject }.getOrNull() ?: return@mapNotNull null

            val resetType = JsonSupport.string(credit, "reset_type", "resetType")
            if (resetType != null && resetType != CODEX_RESET_TYPE) return@mapNotNull null

            val status = JsonSupport.string(credit, "status") ?: return@mapNotNull null
            if (status != STATUS_AVAILABLE) return@mapNotNull null

            ResetCredit(
                id = JsonSupport.string(credit, "id") ?: return@mapNotNull null,
                grantedAt = Instants.parse(JsonSupport.string(credit, "granted_at", "grantedAt")),
                expiresAt = Instants.parse(JsonSupport.string(credit, "expires_at", "expiresAt")),
                status = status,
            )
        }
    }

    /**
     * The count the provider itself reports, preferred over `credits.size` because the list
     * may be truncated while the count is exact.
     */
    fun availableCreditCount(payload: JsonObject?): Int? =
        JsonSupport.double(payload, "available_count", "availableCount")?.toInt()

    /**
     * How many of those credits can be spent against the limit that is currently reached.
     *
     * Production payloads carry `available_count` and `applicable_available_count` side by
     * side and no `credits` array at all, so these are the only two numbers there are. They
     * are kept apart rather than collapsed because one sample cannot settle what a zero here
     * means: it may be "you hold credits but none apply to this limit", or simply "no limit is
     * currently reached". Under the first reading, spending the held count offers a button the
     * server will refuse; under the second, showing only the applicable count hides credits the
     * user really holds. Carrying both is correct under either.
     */
    fun applicableCreditCount(payload: JsonObject?): Int? =
        JsonSupport.double(
            payload,
            "applicable_available_count",
            "applicableAvailableCount",
        )?.toInt()

    /** Reads the subscription plan, which the usage endpoint reports alongside the windows. */
    fun parsePlan(payload: JsonObject): String? =
        planLabel(JsonSupport.string(payload, "plan_type", "planType"))

    private fun windowsFor(
        limitInfo: JsonObject?,
        idPrefix: String,
        labelPrefix: String?,
        nowMs: Long,
        group: String? = null,
    ): List<UsageWindow> {
        if (limitInfo == null) return emptyList()

        val primary = JsonSupport.obj(limitInfo, "primary_window", "primaryWindow")
        val secondary = JsonSupport.obj(limitInfo, "secondary_window", "secondaryWindow")

        // `allowed: false` and `limit_reached: true` both mean the family is spent. Upstream
        // sets them on the parent, not on the individual window.
        val limitReached = JsonSupport.boolean(limitInfo, "limit_reached", "limitReached") == true ||
            JsonSupport.boolean(limitInfo, "allowed") == false

        val classified = classify(primary, secondary)

        return listOfNotNull(
            classified.shortWindow?.let {
                toWindow(it, "$idPrefix-short", labelPrefix, limitReached, nowMs, group)
            },
            classified.longWindow?.let {
                toWindow(it, "$idPrefix-long", labelPrefix, limitReached, nowMs, group)
            },
        )
    }

    private data class Classified(val shortWindow: JsonObject?, val longWindow: JsonObject?)

    /**
     * Sorts the two reported windows into a short (5-hour) and a long (weekly/monthly) slot
     * by duration, falling back to payload order only when durations are absent.
     */
    private fun classify(primary: JsonObject?, secondary: JsonObject?): Classified {
        var shortWindow: JsonObject? = null
        var longWindow: JsonObject? = null

        for (window in listOfNotNull(primary, secondary)) {
            when (WindowCategory.fromPeriodSeconds(periodSeconds(window))) {
                WindowCategory.FIVE_HOUR -> if (shortWindow == null) shortWindow = window
                WindowCategory.WEEKLY, WindowCategory.MONTHLY ->
                    if (longWindow == null) longWindow = window
                WindowCategory.OTHER -> Unit
            }
        }

        // Position is meaningful ONLY for legacy payloads that omit the duration entirely.
        //
        // Without the absence check this fired for a window whose duration was stated and simply
        // unfamiliar — a daily limit, say — and forced it into the five-hour slot, where it is
        // labelled and read as the session limit. That is the same class of error as reading a
        // week as five hours, which a live payload caught once already; a present but unfamiliar
        // duration is not an omitted field, and the comment here has always said so.
        if (shortWindow == null && primary != null && primary !== longWindow &&
            periodSeconds(primary) == null
        ) {
            shortWindow = primary
        }
        if (longWindow == null && secondary != null && secondary !== shortWindow &&
            periodSeconds(secondary) == null
        ) {
            longWindow = secondary
        }

        return Classified(shortWindow, longWindow)
    }

    private fun toWindow(
        window: JsonObject,
        id: String,
        labelPrefix: String?,
        limitReached: Boolean,
        nowMs: Long,
        group: String?,
    ): UsageWindow {
        val periodSeconds = periodSeconds(window)
        val category = WindowCategory.fromPeriodSeconds(periodSeconds)

        // A spent family reports no percentage; treat it as fully used rather than unknown.
        val usedPercent = JsonSupport.double(window, "used_percent", "usedPercent")
            ?: if (limitReached) 100.0 else null

        val resetAt = Instants.parse(JsonSupport.string(window, "reset_at", "resetAt"))
            ?: Instants.fromOffsetSeconds(
                JsonSupport.long(window, "reset_after_seconds", "resetAfterSeconds"),
                nowMs,
            )

        return UsageWindow(
            id = id,
            label = label(category, labelPrefix),
            category = category,
            usedPercent = usedPercent,
            periodSeconds = periodSeconds,
            resetAt = resetAt,
            exhausted = (usedPercent != null && usedPercent >= 100.0) || (usedPercent == null && limitReached),
            group = group,
        )
    }

    private fun periodSeconds(window: JsonObject?): Long? =
        JsonSupport.long(window, "limit_window_seconds", "limitWindowSeconds")

    private fun label(category: WindowCategory, prefix: String?): String {
        val base = when (category) {
            WindowCategory.FIVE_HOUR -> "5h limit"
            WindowCategory.WEEKLY -> "Weekly"
            WindowCategory.MONTHLY -> "Monthly"
            WindowCategory.OTHER -> "Limit"
        }
        return if (prefix == null) base else "$prefix · $base"
    }

    private fun slug(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "limit" }

    private const val CODEX_RESET_TYPE = "codex_rate_limits"
    private const val STATUS_AVAILABLE = "available"
}
