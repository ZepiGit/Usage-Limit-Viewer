package com.usagelimits.providers.claude

import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Claude
import com.usagelimits.core.time.Instants
import kotlinx.serialization.json.JsonObject

/**
 * Turns the Anthropic `/api/oauth/usage` payload into normalised windows.
 *
 * The payload is flat: each window sits at the top level under its own key, there is no
 * wrapper object. Every window reports `utilization` (percent consumed) and `resets_at`.
 *
 * Windows are read in the fixed order declared by [Claude.USAGE_WINDOW_KEYS] and unknown
 * top-level keys are ignored, so Anthropic adding a window costs nothing and removing one
 * costs a single row.
 */
object ClaudeUsageParser {

    private const val FIVE_HOUR_KEY = "five_hour"
    private const val FIVE_HOUR_SECONDS = 18_000L
    private const val SEVEN_DAY_SECONDS = 604_800L

    /** Upstream's current key for the Fable weekly window — a codename, not a typo. */
    private const val FABLE_WINDOW_KEY = "iguana_necktie"

    fun parse(payload: JsonObject, nowMs: Long): List<UsageWindow> {
        // The `limits` array carries per-model weekly windows. When it describes Fable, it is
        // the better source than the opaque `iguana_necktie` key, so it replaces it rather
        // than rendering the same quota twice.
        val fable = findFableLimit(payload)

        val windows = Claude.USAGE_WINDOW_KEYS.mapNotNull { (key, label) ->
            if (key == FABLE_WINDOW_KEY && fable != null) return@mapNotNull null
            val window = JsonSupport.obj(payload, key) ?: return@mapNotNull null
            toWindow(
                id = key.replace('_', '-'),
                label = label,
                key = key,
                usedPercent = JsonSupport.double(window, "utilization"),
                resetsAt = JsonSupport.string(window, "resets_at", "resetsAt"),
            )
        }

        val fableWindow = fable?.let { limit ->
            toWindow(
                id = "seven-day-fable",
                label = "Weekly (Fable)",
                key = FABLE_WINDOW_KEY,
                usedPercent = JsonSupport.double(limit, "percent"),
                resetsAt = JsonSupport.string(limit, "resets_at", "resetsAt"),
            )
        }

        return if (fableWindow == null) windows else windows + fableWindow
    }

    /** Reads the plan label the profile endpoint reports, for the account subtitle. */
    fun parsePlan(profile: JsonObject): String? {
        val account = JsonSupport.obj(profile, "account") ?: return null
        return when {
            JsonSupport.boolean(account, "has_claude_max", "hasClaudeMax") == true -> "Max"
            JsonSupport.boolean(account, "has_claude_pro", "hasClaudePro") == true -> "Pro"
            else -> null
        }
    }

    /**
     * Finds the active weekly Fable entry in `limits`, if there is one.
     *
     * Prefers the entry marked active; several can be present when a plan is mid-transition.
     */
    private fun findFableLimit(payload: JsonObject): JsonObject? {
        val candidates = JsonSupport.array(payload, "limits").mapNotNull { element ->
            val limit = runCatching { element as JsonObject }.getOrNull() ?: return@mapNotNull null

            val kind = JsonSupport.string(limit, "kind")?.lowercase()
            if (kind != "weekly_scoped") return@mapNotNull null

            val model = JsonSupport.obj(JsonSupport.obj(limit, "scope"), "model")
            val name = JsonSupport.string(model, "display_name", "displayName")?.lowercase()
            if (name != "fable" && name != "fable 5") return@mapNotNull null

            if (JsonSupport.double(limit, "percent") == null) return@mapNotNull null
            limit
        }

        return candidates.firstOrNull { JsonSupport.boolean(it, "is_active", "isActive") == true }
            ?: candidates.firstOrNull()
    }

    private fun toWindow(
        id: String,
        label: String,
        key: String,
        usedPercent: Double?,
        resetsAt: String?,
    ): UsageWindow {
        // Only `five_hour` is a rolling five-hour window; every other key Anthropic exposes is
        // a seven-day window, so the period is derived from the key rather than the payload,
        // which does not state it.
        val periodSeconds = if (key == FIVE_HOUR_KEY) FIVE_HOUR_SECONDS else SEVEN_DAY_SECONDS

        return UsageWindow(
            id = id,
            label = label,
            category = WindowCategory.fromPeriodSeconds(periodSeconds),
            usedPercent = usedPercent,
            periodSeconds = periodSeconds,
            resetAt = Instants.parse(resetsAt),
            exhausted = usedPercent != null && usedPercent >= 100.0,
        )
    }
}
