package com.usagelimits.providers.xai

import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.time.Instants
import kotlinx.serialization.json.JsonObject

/**
 * Turns the two xAI billing payloads into normalised windows.
 *
 * xAI is the odd provider out: it does not report quota as a percentage of a rate limit, it
 * reports spend. Two views exist and they describe different periods, so the app reads both:
 *
 *  - `/v1/billing?format=credits` — the weekly credit view, the one endpoint that does hand
 *    back a ready-made percentage.
 *  - `/v1/billing` — the monthly view, where **every amount is an integer number of cents**.
 *
 * Nothing here converts or displays currency. Percentages are ratios of two cent amounts, so
 * the unit cancels out; the app never shows the user a money value it might get wrong.
 *
 * The two views are parsed separately and joined by [merge] so that losing one endpoint costs
 * its rows rather than the whole account.
 */
object XaiBillingParser {

    const val CREDITS_WINDOW_ID = "xai-credits"
    const val MONTHLY_WINDOW_ID = "xai-monthly"
    const val ON_DEMAND_WINDOW_ID = "xai-on-demand"

    /**
     * The billing endpoint reports period *stamps*, not a period length, and a real calendar
     * month is 28-31 days. 30 days is the nominal billing month and sits inside the monthly
     * band [WindowCategory.fromPeriodSeconds] recognises, so the window is categorised as
     * monthly whatever month it happens to be.
     */
    private const val BILLING_PERIOD_SECONDS = 2_592_000L

    /**
     * Unwraps the `config` envelope both billing endpoints wrap their payload in.
     *
     * Upstream reads `payload.config` (Management Center
     * src/features/quota/providers/xai/data.ts:97). Older or partial responses have been seen
     * flat, so the root is accepted as a fallback rather than requiring the envelope.
     */
    private fun config(payload: JsonObject): JsonObject =
        JsonSupport.obj(payload, "config") ?: payload

    /**
     * Reads a money field, which xAI reports either as a bare number or wrapped as
     * `{"val": 10000}`.
     *
     * Management Center normalises the same two shapes in normalizeXaiCentValue
     * (src/utils/quota/builders.ts:374). Reading only the bare form yields null for a wrapped
     * value, which would silently drop the whole billing view.
     */
    private fun cents(source: JsonObject?, vararg names: String): Double? {
        JsonSupport.double(source, *names)?.let { return it }
        val wrapped = JsonSupport.obj(source, *names) ?: return null
        return JsonSupport.double(wrapped, "val")
    }

    /**
     * Parses the weekly credit view.
     *
     * [nowMs] is part of the parser contract the four providers share; xAI states the period
     * end absolutely, so nothing here currently needs it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun parseCredits(payload: JsonObject, nowMs: Long): List<UsageWindow> {
        // No percentage means no credit window at all — better one missing row than a bar
        // drawn from an assumed zero.
        val config = config(payload)
        val usedPercent = JsonSupport.double(config, "creditUsagePercent", "credit_usage_percent")
            ?.takeIf { it.isFinite() }
            ?: return emptyList()

        val period = JsonSupport.obj(config, "currentPeriod", "current_period")
        val startMs = Instants.parse(JsonSupport.string(period, "start"))
        val endMs = Instants.parse(JsonSupport.string(period, "end"))
        val periodType = JsonSupport.string(period, "type")

        // The measured span takes precedence so a change upstream reclassifies itself instead
        // of mislabelling a fortnight as a week. Without a usable span, the reported type can
        // still identify the period; without either, OTHER avoids claiming a known length.
        val periodSeconds = if (startMs != null && endMs != null && endMs > startMs) {
            (endMs - startMs) / 1000
        } else {
            when {
                periodType?.contains("week", ignoreCase = true) == true -> 604_800L
                periodType?.contains("month", ignoreCase = true) == true -> BILLING_PERIOD_SECONDS
                else -> null
            }
        }
        val category = WindowCategory.fromPeriodSeconds(periodSeconds)

        return listOf(
            UsageWindow(
                id = CREDITS_WINDOW_ID,
                label = when (category) {
                    WindowCategory.WEEKLY -> "Weekly credits"
                    WindowCategory.MONTHLY -> "Monthly credits"
                    else -> "Credits"
                },
                category = category,
                usedPercent = usedPercent,
                periodSeconds = periodSeconds,
                resetAt = endMs,
                exhausted = isExhausted(usedPercent),
            ),
        )
    }

    /**
     * Parses the monthly spend view into an included-allowance window and, where the account
     * has one, a separate on-demand window.
     *
     * They are two bars, not one, because they run out independently: an account can have
     * burned its whole monthly allowance and still be able to spend on demand.
     *
     * [nowMs] is unused for the same reason as in [parseCredits].
     */
    @Suppress("UNUSED_PARAMETER")
    fun parseBilling(payload: JsonObject, nowMs: Long): List<UsageWindow> {
        val config = config(payload)
        val monthlyLimit = cents(config, "monthlyLimit", "monthly_limit")
        // Every name the production shape carries for spend. Reading `used` alone meant a
        // payload whose spend arrived as `includedUsed` — the pinned production shape — read
        // as ZERO spend: "0 % used / 100 % remaining", healthy green, for an account 90 %
        // through its allowance.
        val used = cents(config, "used")
            ?: cents(config, "includedUsed", "included_used")
            ?: cents(config, "totalUsed", "total_used")
        // Neither figure present means this is not a billing payload we understand.
        if (monthlyLimit == null && used == null) return emptyList()

        val resetAt = Instants.parse(
            JsonSupport.string(config, "billingPeriodEnd", "billing_period_end"),
        )

        val windows = mutableListOf<UsageWindow>()

        // Spend past the allowance is on-demand spend, so the included bar stops at 100 %.
        // And spend that is ABSENT stays unknown: a limit with no spend figure is a window
        // whose percentage the app does not know, never one it has decided is untouched.
        val includedPercent = monthlyLimit?.let { limit ->
            used?.let { percentOf(minOf(it, limit), limit) }
        }
        windows += UsageWindow(
            id = MONTHLY_WINDOW_ID,
            label = "Monthly included",
            category = WindowCategory.MONTHLY,
            usedPercent = includedPercent,
            periodSeconds = BILLING_PERIOD_SECONDS,
            resetAt = resetAt,
            exhausted = isExhausted(includedPercent),
        )

        // A zero or absent cap means the account cannot spend on demand at all; showing an
        // empty bar for a facility that does not exist would just be noise.
        val onDemandCap = cents(config, "onDemandCap", "on_demand_cap") ?: 0.0
        if (onDemandCap > 0.0) {
            // Deriving overage needs BOTH a known allowance and known spend; with either
            // missing the row is shown with an unknown percentage rather than omitted or
            // invented as zero.
            val onDemandUsed = cents(config, "onDemandUsed", "on_demand_used")
                ?: monthlyLimit?.let { limit -> used?.let { maxOf(0.0, it - limit) } }
            val onDemandPercent = onDemandUsed?.let { percentOf(it, onDemandCap) }
            windows += UsageWindow(
                id = ON_DEMAND_WINDOW_ID,
                label = "On-demand",
                category = WindowCategory.MONTHLY,
                usedPercent = onDemandPercent,
                periodSeconds = BILLING_PERIOD_SECONDS,
                resetAt = resetAt,
                exhausted = isExhausted(onDemandPercent),
            )
        }

        return windows
    }

    /**
     * Joins the two views, credits first.
     *
     * The parsers' ids are currently disjoint by construction, so de-duplication guards
     * against a future id collision rather than removing any rows today. First occurrence
     * wins, so the credit view — the only one that reports a percentage directly — takes
     * precedence if a collision is introduced.
     */
    fun merge(credits: List<UsageWindow>, billing: List<UsageWindow>): List<UsageWindow> {
        val seen = mutableSetOf<String>()
        return (credits + billing).filter { seen.add(it.id) }
    }

    /**
     * The single division point in this file.
     *
     * Every ratio goes through it so a zero or missing denominator can only ever produce a
     * null percentage — never NaN or Infinity, which would render as a nonsense bar and would
     * compare as "healthy" against the severity thresholds.
     */
    private fun percentOf(amount: Double, total: Double): Double? =
        if (total > 0.0) (amount / total * 100.0).takeIf { it.isFinite() } else null

    private fun isExhausted(usedPercent: Double?): Boolean =
        usedPercent != null && usedPercent >= 100.0
}
