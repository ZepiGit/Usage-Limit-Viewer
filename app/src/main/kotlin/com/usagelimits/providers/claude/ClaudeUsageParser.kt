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
 * The payload carries the same quotas twice. Each window sits at the top level under its own
 * key — `five_hour`, `seven_day`, and a rotating cast of codenames — and the same figures also
 * appear in a `limits` array that is Anthropic's own presentation model, carrying `kind`,
 * `group`, `percent`, `resets_at` and, for per-model quotas, `scope.model.display_name`.
 *
 * `limits` is preferred whenever it is present. It is labelled, already deduplicated, and it
 * is the only place some quotas appear at all: on a live account the top-level `iguana_necktie`
 * key was null while `limits` carried the Fable figure it is supposed to hold. Reading both
 * would double-count, so the flat keys are the fallback for a payload with no `limits` array
 * rather than a second source.
 *
 * A third pass then sweeps up window-shaped keys neither list knows about, because a quota the
 * provider reports and the app hides is a wrong number with nothing visibly broken. That sweep
 * is deliberately narrow — see [discoverUnknownWindows].
 */
object ClaudeUsageParser {

    private const val FIVE_HOUR_SECONDS = 18_000L
    private const val SEVEN_DAY_SECONDS = 604_800L

    private const val FIVE_HOUR_ID = "five-hour"
    private const val SEVEN_DAY_ID = "seven-day"

    fun parse(payload: JsonObject, nowMs: Long): List<UsageWindow> {
        val primary = parseLimits(payload).ifEmpty { parseFlatWindows(payload) }
        return primary + discoverUnknownWindows(payload, primary)
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
     * Maps the `limits` array one entry to one window.
     *
     * An entry whose `kind` is unrecognised is kept rather than dropped: `group` still says
     * whether it is a session or a weekly quota, and a window with no known duration is more
     * useful than a missing one. Entries are deduplicated by id, first occurrence winning.
     */
    private fun parseLimits(payload: JsonObject): List<UsageWindow> {
        val seen = mutableSetOf<String>()

        return JsonSupport.array(payload, "limits").mapIndexedNotNull { index, element ->
            val limit = element as? JsonObject ?: return@mapIndexedNotNull null
            val percent = JsonSupport.double(limit, "percent") ?: return@mapIndexedNotNull null

            val kind = JsonSupport.string(limit, "kind")
            val group = JsonSupport.string(limit, "group")
            val model = JsonSupport.string(
                JsonSupport.obj(JsonSupport.obj(limit, "scope"), "model"),
                "display_name",
                "displayName",
            )

            val id = when (kind) {
                "session" -> FIVE_HOUR_ID
                "weekly_all" -> SEVEN_DAY_ID
                "weekly_scoped" -> "$SEVEN_DAY_ID-${slug(model ?: index.toString())}"
                else -> slug(kind ?: "limit-$index")
            }
            if (!seen.add(id)) return@mapIndexedNotNull null

            val periodSeconds = when {
                kind == "session" || group == "session" -> FIVE_HOUR_SECONDS
                kind?.startsWith("weekly") == true || group == "weekly" -> SEVEN_DAY_SECONDS
                else -> null
            }

            val label = when (kind) {
                "session" -> "5h limit"
                "weekly_all" -> "Weekly"
                "weekly_scoped" -> model?.let { "Weekly ($it)" } ?: "Weekly (scoped)"
                else -> humanize(kind ?: "Limit ${index + 1}")
            }

            window(
                id = id,
                label = label,
                usedPercent = percent,
                periodSeconds = periodSeconds,
                resetsAt = JsonSupport.string(limit, "resets_at", "resetsAt"),
            )
        }
    }

    /**
     * Reads the flat top-level window keys, for a payload that carries no `limits` array.
     *
     * A key whose value is JSON null, or whose `utilization` is missing, is dropped rather
     * than emitted with a null usedPercent: null resolves to Severity.ERROR and snapshot
     * severity is a MAX over the windows, so one unused bucket would mark the whole account as
     * failed while that same row sorts last in mostCritical — red with nothing visibly wrong.
     */
    private fun parseFlatWindows(payload: JsonObject): List<UsageWindow> =
        Claude.USAGE_WINDOW_KEYS.mapNotNull { (key, label) ->
            val entry = JsonSupport.obj(payload, key) ?: return@mapNotNull null
            val used = JsonSupport.double(entry, "utilization") ?: return@mapNotNull null

            window(
                id = key.replace('_', '-'),
                label = label,
                usedPercent = used,
                periodSeconds = periodSecondsForKey(key),
                resetsAt = JsonSupport.string(entry, "resets_at", "resetsAt"),
            )
        }

    /**
     * Sweeps up live quotas under keys nothing else knows about.
     *
     * Anthropic ships new windows under rotating codenames — a live account carried eight the
     * registry had never seen — so a fixed key list silently loses a quota the moment one is
     * introduced, and the number on screen goes quietly wrong.
     *
     * The sweep is narrow on purpose, because the obvious rule is wrong. "Any object with a
     * numeric `utilization`" also matches `extra_usage`, which is a credit balance rather than
     * a rate-limit window; folding it into a MAX-over-windows severity would mis-grade the
     * account. A window declares `resets_at` even when that value is null, and a balance never
     * does, so that key is the discriminator.
     *
     * Only windows with something actually consumed are promoted. The unused codename slots
     * are placeholders, and a screen meant to be read in three seconds does not need a row
     * reading "Nimbus Quill — 0 % used".
     *
     * Overlap is tested by id, which cannot catch a codename key that `limits` also reports
     * under a different `kind` — that would render one quota twice. The trade is deliberate:
     * a duplicated row is visible and obviously wrong, while a hidden quota is invisible and
     * makes the number on screen wrong with nothing to notice.
     */
    private fun discoverUnknownWindows(
        payload: JsonObject,
        known: List<UsageWindow>,
    ): List<UsageWindow> {
        val knownKeys = Claude.USAGE_WINDOW_KEYS.map { it.first }.toSet()
        val knownIds = known.map { it.id }.toSet()

        return payload.entries.mapNotNull { (key, value) ->
            if (key in knownKeys) return@mapNotNull null

            val entry = value as? JsonObject ?: return@mapNotNull null
            if (!entry.containsKey("resets_at") && !entry.containsKey("resetsAt")) {
                return@mapNotNull null
            }

            val used = JsonSupport.double(entry, "utilization") ?: return@mapNotNull null
            if (used <= 0.0) return@mapNotNull null

            val id = key.replace('_', '-')
            if (id in knownIds) return@mapNotNull null

            window(
                id = id,
                label = humanize(key),
                usedPercent = used,
                // The duration of a window nobody has documented is unknown, and OTHER says so.
                // Guessing seven days would put a wrong countdown on the Resets screen.
                periodSeconds = null,
                resetsAt = JsonSupport.string(entry, "resets_at", "resetsAt"),
            )
        }.sortedBy { it.id }
    }

    /**
     * Only `five_hour` is a rolling five-hour window; every other key in the registry is a
     * seven-day window. The payload does not state either, so it comes from the key.
     */
    private fun periodSecondsForKey(key: String): Long =
        if (key == "five_hour") FIVE_HOUR_SECONDS else SEVEN_DAY_SECONDS

    private fun window(
        id: String,
        label: String,
        usedPercent: Double,
        periodSeconds: Long?,
        resetsAt: String?,
    ) = UsageWindow(
        id = id,
        label = label,
        category = WindowCategory.fromPeriodSeconds(periodSeconds),
        usedPercent = usedPercent,
        periodSeconds = periodSeconds,
        resetAt = Instants.parse(resetsAt),
        exhausted = usedPercent >= 100.0,
    )

    /** `nimbus_quill` reads as "Nimbus Quill" — the only label an undocumented key can have. */
    private fun humanize(key: String): String =
        key.split('_', '-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    private fun slug(value: String): String =
        value.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifEmpty { "window" }
}
