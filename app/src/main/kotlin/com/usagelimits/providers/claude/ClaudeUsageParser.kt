package com.usagelimits.providers.claude

import com.usagelimits.core.model.planLabel

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

    /**
     * Top-level objects that look like windows but are not.
     *
     * The discovery gate asks whether a key declares `resets_at`, and `extra_usage` is one
     * field away from passing it — it already carries a `utilization`. It is a CREDIT BALANCE:
     * folding it in would grade an account by money spent, and spending credits would trip an
     * exhaustion alert. Named explicitly rather than inferred, because the shape that
     * distinguishes them today is one upstream field away from being ambiguous.
     */
    private val NON_WINDOW_KEYS = setOf(
        "extra_usage",
        "spend",
        "seven_day_breakdown",
        "limits",
        "organization",
        "account",
    )

    /** A label is a row on a phone, and a server-supplied key has no length limit. */
    private const val MAX_LABEL_LENGTH = 48

    /**
     * Truncates without splitting a character in half.
     *
     * `take` counts UTF-16 code units, so cutting a label whose 48th and 49th units are the two
     * halves of one emoji leaves a lone surrogate — a malformed string that renders as a
     * replacement box and compares unequal to anything sensible. Swift's `prefix` cuts on
     * grapheme boundaries and never had this; the two must agree, and Swift is right.
     */
    private fun String.takeLabel(): String {
        // Counted in grapheme clusters, which is what Swift's `prefix` counts. The previous
        // version counted UTF-16 units and then dropped a trailing lone surrogate, which kept
        // the string well-formed but cut it one character short of the Swift twin whenever an
        // emoji sat at the boundary — its own comment said Swift was right and still disagreed.
        val boundaries = java.text.BreakIterator.getCharacterInstance().also { it.setText(this) }
        var end = 0
        var count = 0
        var next = boundaries.next()
        while (next != java.text.BreakIterator.DONE && count < MAX_LABEL_LENGTH) {
            end = next
            count++
            next = boundaries.next()
        }
        return substring(0, end)
    }

    /**
     * A `utilization` of 21.0 means 21 % consumed, not 21 % of one.
     *
     * Worth stating because the field is named `utilization` rather than `percent`, and a
     * fractional reading would put every fallback number out by a factor of a hundred while
     * still looking like a number. A live account settles it: `five_hour.utilization` was 21.0
     * and the `limits` entry for the same quota reported `percent: 21`. The test suite pins
     * that equivalence, so if the scale ever changes it fails loudly rather than quietly.
     */
    fun parse(payload: JsonObject, nowMs: Long): List<UsageWindow> {
        val primary = parseLimits(payload)

        // Everything `limits` did not already account for. `limits` is upstream's own view and
        // has been complete on every payload seen, but "has been complete" is not a guarantee:
        // a partial one would silently hide live windows, and treating it as all-or-nothing
        // made that unrecoverable. Supplementing costs nothing when it is complete.
        val discovered = discoverUnknownWindows(payload)
        val supplemental = (parseFlatWindows(payload) + discovered)
            .filterNot { candidate ->
                // Same id is the same quota by construction.
                primary.any { it.id == candidate.id } ||
                    // And so is the same reset instant: a codename key and a `limits` entry
                    // describing one quota roll over together. Only a stated instant counts —
                    // two windows that both report no reset are not thereby the same window.
                    (
                        candidate.resetAt != null &&
                            primary.any { it.resetAt == candidate.resetAt }
                        )
            }

        val windows = primary + supplemental.filter { (it.usedPercent ?: 0.0) > 0.0 }
        if (windows.isNotEmpty()) return windows

        // Untouched codename slots are noise on a screen meant to be read in three seconds —
        // unless they are all an account has. Dropping them unconditionally turned a healthy,
        // completely unused account into zero windows, which resolves to ERROR: the app
        // reporting a fault where the real answer was "nothing used yet".
        return supplemental
    }

    /**
     * Reads the plan label the profile endpoint reports, for the account subtitle.
     *
     * `organization.rate_limit_tier` is preferred over the `has_claude_max` boolean because the
     * boolean cannot tell a Max 5× subscription from a Max 20× one — a fivefold difference in
     * the very quantity this app exists to show. A live profile reports
     * `default_claude_max_5x`, so the multiplier is right there; the booleans are the fallback
     * for a payload that omits the tier.
     */
    fun parsePlan(profile: JsonObject): String? {
        val organization = JsonSupport.obj(profile, "organization")
        val tier = JsonSupport.string(organization, "rate_limit_tier", "rateLimitTier")
        planFromTier(tier)?.let { return it }

        val account = JsonSupport.obj(profile, "account") ?: return null
        return when {
            JsonSupport.boolean(account, "has_claude_max", "hasClaudeMax") == true -> "Max"
            JsonSupport.boolean(account, "has_claude_pro", "hasClaudePro") == true -> "Pro"
            else -> null
        }
    }

    /**
     * Turns `default_claude_max_5x` into `Max 5×`.
     *
     * Read structurally rather than from a table of known tiers: Anthropic adds tiers, and a
     * table would render a new one as no plan at all. An unrecognised tier still yields
     * something readable, which beats a blank subtitle.
     */
    private fun planFromTier(tier: String?): String? = planLabel(
        // The vendor prefixes are Anthropic's alone, so they are stripped here rather than in
        // the shared formatter.
        tier?.trim()?.lowercase()?.removePrefix("default_")?.removePrefix("claude_"),
    )

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

            // Identity prefers the model's own id: two display names that differ only in
            // punctuation ("Sonnet 4.5" and "sonnet-4.5") slug identically, and the loser was
            // silently dropped — the user then read one model's figure as the other's.
            val modelKey = JsonSupport.string(
                JsonSupport.obj(JsonSupport.obj(limit, "scope"), "model"),
                "id",
            ) ?: model

            val baseId = when (kind) {
                "session" -> FIVE_HOUR_ID
                "weekly_all" -> SEVEN_DAY_ID
                "weekly_scoped" -> "$SEVEN_DAY_ID-${slug(modelKey ?: "scoped")}"
                else -> slug(kind ?: "limit")
            }
            // A collision suffixes rather than drops. Array position is deliberately not part of
            // identity: reordering `limits` would then rename every window, detaching anything
            // keyed by it — a notification's dedupe record, for one.
            var id = baseId
            var suffix = 2
            while (!seen.add(id)) {
                id = "$baseId-$suffix"
                suffix++
            }

            val periodSeconds = when {
                kind == "session" || group == "session" -> FIVE_HOUR_SECONDS
                kind?.startsWith("weekly") == true || group == "weekly" -> SEVEN_DAY_SECONDS
                else -> null
            }

            val label = when (kind) {
                "session" -> "5h limit"
                "weekly_all" -> "Weekly"
                "weekly_scoped" -> model?.let { "Weekly ($it)" } ?: "Weekly (scoped)"
                else -> humanize(kind ?: "Limit ${index + 1}").takeLabel()
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
    private fun discoverUnknownWindows(payload: JsonObject): List<UsageWindow> {
        val knownKeys = Claude.USAGE_WINDOW_KEYS.map { it.first }.toSet()

        val candidates: List<UsageWindow> = payload.entries.mapNotNull { (key, value) ->
            if (key in knownKeys || key in NON_WINDOW_KEYS) return@mapNotNull null

            val entry = value as? JsonObject ?: return@mapNotNull null
            if (!entry.containsKey("resets_at") && !entry.containsKey("resetsAt")) {
                return@mapNotNull null
            }

            val used = JsonSupport.double(entry, "utilization") ?: return@mapNotNull null

            val id = key.replace('_', '-').trim('-')
            if (id.isBlank()) return@mapNotNull null

            window(
                id = id,
                label = humanize(key).takeLabel(),
                usedPercent = used,
                // The duration of a window nobody has documented is unknown, and OTHER says so.
                // It cannot be inferred from the time left before its reset either: a weekly
                // window observed two hours before it rolls over would infer two hours, which
                // is a confidently wrong category rather than an honest unknown.
                periodSeconds = null,
                resetsAt = JsonSupport.string(entry, "resets_at", "resetsAt"),
            )
        }.sortedBy { it.id }

        // Zero-consumption slots are filtered by the caller, which is the only place that can
        // see whether they are all the account has.
        return candidates
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
            // The full Unicode mapping, as above and as Swift does.
            .joinToString(" ") { part -> part.first().uppercase() + part.drop(1) }

    private fun slug(value: String): String =
        value.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifEmpty { "window" }
}
