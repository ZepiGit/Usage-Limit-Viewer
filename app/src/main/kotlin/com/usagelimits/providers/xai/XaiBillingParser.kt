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
     * Parses the weekly credit view.
     *
     * [nowMs] is part of the parser contract the four providers share; xAI states the period
     * end absolutely, so nothing here currently needs it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun parseCredits(payload: JsonObject, nowMs: Long): List<UsageWindow> {
        // No percentage means no credit window at all — better one missing row than a bar
        // drawn from an assumed zero.
        val usedPercent = JsonSupport.double(payload, "creditUsagePercent", "credit_usage_percent")
            ?: return emptyList()

        val period = JsonSupport.obj(payload, "currentPeriod", "current_period")
        val startMs = Instants.parse(JsonSupport.string(period, "start"))
        val endMs = Instants.parse(JsonSupport.string(period, "end"))

        // The span is measured rather than assumed. xAI calls this window "weekly" today, but
        // deriving the length means a change upstream reclassifies itself instead of
        // mislabelling a fortnight as a week. An unusable or inverted pair leaves the length
        // unknown, which classifies as OTHER — still rendered, just not claimed to be weekly.
        val periodSeconds = if (startMs != null && endMs != null && endMs > startMs) {
            (endMs - startMs) / 1000
        } else {
            null
        }

        return listOf(
            UsageWindow(
                id = CREDITS_WINDOW_ID,
                label = "Weekly credits",
                category = WindowCategory.fromPeriodSeconds(periodSeconds),
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
        val monthlyLimit = JsonSupport.double(payload, "monthlyLimit", "monthly_limit")
        val used = JsonSupport.double(payload, "used")
        // Neither figure present means this is not a billing payload we understand.
        if (monthlyLimit == null && used == null) return emptyList()

        val limitCents = monthlyLimit ?: 0.0
        val usedCents = used ?: 0.0
        val resetAt = Instants.parse(
            JsonSupport.string(payload, "billingPeriodEnd", "billing_period_end"),
        )

        val windows = mutableListOf<UsageWindow>()

        // Spend past the allowance is on-demand spend, so the included bar stops at 100 %.
        // Without the clamp an overspending account reads "140 % used", which is both wrong
        // for this window and hides the overage from the row that actually meters it.
        val includedUsed = minOf(usedCents, limitCents)
        val includedPercent = percentOf(includedUsed, limitCents)
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
        val onDemandCap = JsonSupport.double(payload, "onDemandCap", "on_demand_cap") ?: 0.0
        if (onDemandCap > 0.0) {
            // Older payloads omit the on-demand figure and only report total spend, in which
            // case everything above the allowance is by definition on demand.
            val onDemandUsed = JsonSupport.double(payload, "onDemandUsed", "on_demand_used")
                ?: maxOf(0.0, usedCents - limitCents)
            val onDemandPercent = percentOf(onDemandUsed, onDemandCap)
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
     * Ids are de-duplicated because the two views are projections of one resource: if the
     * credit view ever starts reporting a monthly figure as well, the account gets one bar
     * rather than two contradictory ones. First occurrence wins, so the credit view — the
     * only one that reports a percentage directly — stays authoritative.
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
        if (total > 0.0) amount / total * 100.0 else null

    private fun isExhausted(usedPercent: Double?): Boolean =
        usedPercent != null && usedPercent >= 100.0
}
