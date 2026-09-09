package com.usagelimits.core.notifications

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.settings.AppSettings
import kotlin.math.ceil

/**
 * Decides what a completed sync should say, without touching Android.
 *
 * Every notification here is an *edge*: something that just became true. Re-deriving that from
 * the current snapshot alone is impossible — a snapshot says an account is at 8 %, not whether
 * it was already at 8 % an hour ago — so the evaluator takes the prior state in and hands the
 * new state back. Keeping that pure is what makes the deduplication testable at all; the
 * Android side does nothing but persist the claims and post the lines.
 *
 * The contract callers must honour, and the reason it is shaped this way:
 *
 *  - Claims are consumed **before** the notification is posted. A Room transaction cannot
 *    commit atomically with `NotificationManager.notify`, so one of the two failure modes has
 *    to be chosen. Losing an alert to a crash in that gap is recoverable; repeating one every
 *    fifteen minutes is what makes a user turn notifications off.
 *  - State advances even when notifications are disabled or permission is missing. Otherwise
 *    granting permission would replay every threshold the account has ever crossed.
 */
object NotificationEvaluator {

    /** Below this much remaining, the account gets a warning. Strictly less than. */
    const val WARNING_PERCENT = 20.0

    /** Below this much remaining, the warning escalates once. Strictly less than. */
    const val CRITICAL_PERCENT = 10.0

    private const val MINUTE_MS = 60_000L

    /**
     * One account's carried-over state.
     *
     * [lowQuotaEpisode] increments each time the account falls below [WARNING_PERCENT] after
     * having recovered, which is what stops a single bad week from producing the same warning
     * on every sync: the episode number is part of the event key, so within one episode each
     * tier can be claimed exactly once.
     */
    data class AccountState(
        val accountId: String,
        val lowQuotaEpisode: Int = 0,
        val lowQuotaActive: Boolean = false,
        val lastProcessedFetchedAt: Long? = null,
    )

    /**
     * What the evaluator produced for one sync.
     *
     * [claims] are event keys the caller must attempt to consume; only the ones that were not
     * already present become [lines]. The caller matches them by index into [lineFor].
     */
    data class Outcome(
        val states: List<AccountState>,
        val events: List<Event>,
        val standingFindings: List<String>,
    )

    /**
     * A single edge-triggered alert, not yet known to be new.
     *
     * [accountId] is carried rather than parsed back out of [key]: the ledger row has a
     * foreign key onto the account, and recovering it from a delimited string would turn any
     * future change to the key format into a constraint violation at insert time.
     *
     * A blank [line] means "consume this key but say nothing" — how a superseded tier is
     * spent without producing a second notification.
     */
    data class Event(val accountId: String, val key: String, val line: String)

    fun evaluate(
        accounts: List<AccountUsage>,
        settings: AppSettings,
        states: Map<String, AccountState>,
        nowMs: Long,
    ): Outcome {
        val newStates = mutableListOf<AccountState>()
        val events = mutableListOf<Event>()
        val standing = mutableListOf<String>()

        for (usage in accounts) {
            val id = usage.account.localId
            val previous = states[id] ?: AccountState(accountId = id)
            val snapshot = usage.snapshot

            // A failed refresh proves nothing about quota. Advancing state on one would let a
            // network blip end a low-quota episode and re-arm the warning for the next sync.
            if (snapshot == null || snapshot.status == SnapshotStatus.FAILED) {
                newStates += previous
                standing += authFinding(usage, snapshot, settings)
                continue
            }

            // Reprocessing a snapshot already seen must produce nothing: WorkManager reruns
            // happen, and every event below would otherwise fire a second time.
            if (previous.lastProcessedFetchedAt != null &&
                snapshot.fetchedAt <= previous.lastProcessedFetchedAt
            ) {
                newStates += previous
                standing += standingFindings(usage, snapshot, settings)
                continue
            }

            val (state, accountEvents) = evaluateAccount(usage, snapshot, settings, previous, nowMs)
            newStates += state
            events += accountEvents
            standing += standingFindings(usage, snapshot, settings)
        }

        return Outcome(newStates, events, standing)
    }

    private fun evaluateAccount(
        usage: AccountUsage,
        snapshot: UsageSnapshot,
        settings: AppSettings,
        previous: AccountState,
        nowMs: Long,
    ): Pair<AccountState, List<Event>> {
        val name = usage.account.label
        val events = mutableListOf<Event>()

        val (state, lowEvents) = evaluateLowQuota(name, snapshot, settings, previous)
        events += lowEvents
        events += evaluateResetApproaching(name, snapshot, settings, nowMs)
        events += evaluateExpiringCredits(usage, snapshot, settings, nowMs)

        return state.copy(lastProcessedFetchedAt = snapshot.fetchedAt) to events
    }

    /**
     * The two low-quota tiers, as one episode with two one-shot bits.
     *
     * A single adjustable threshold cannot express this: the point of the second tier is that
     * an account already warned at 18 % should still say something when it reaches 8 %, and
     * exactly once. Falling straight past both consumes both keys but emits only the stronger
     * line, so a fast burn does not produce two notifications a second apart.
     */
    private fun evaluateLowQuota(
        name: String,
        snapshot: UsageSnapshot,
        settings: AppSettings,
        previous: AccountState,
    ): Pair<AccountState, List<Event>> {
        val windows = snapshot.windows
        val worst = windows.filter { it.remainingPercent != null }
            .minByOrNull { it.remainingPercent!! }
        val remaining = worst?.remainingPercent
        val exhausted = windows.any { it.severity == Severity.EXHAUSTED }

        // Recovery: every window is known, healthy and unspent. Only then may the next dip
        // warn again. An unknown percentage is not recovery, it is an absence of evidence.
        val recovered = windows.isNotEmpty() &&
            !exhausted &&
            windows.all { (it.remainingPercent ?: -1.0) >= WARNING_PERCENT }

        if (recovered) {
            return previous.copy(lowQuotaActive = false) to emptyList()
        }

        val belowWarning = remaining != null && remaining > 0.0 && remaining < WARNING_PERCENT
        val belowCritical = remaining != null && remaining > 0.0 && remaining < CRITICAL_PERCENT

        if (!exhausted && !belowWarning) {
            return previous to emptyList()
        }

        // An episode is the unit of deduplication. Starting one here — rather than on the
        // first notification — means the keys stay stable even when the setting is off.
        val state = if (previous.lowQuotaActive) {
            previous
        } else {
            previous.copy(lowQuotaEpisode = previous.lowQuotaEpisode + 1, lowQuotaActive = true)
        }
        val episode = state.lowQuotaEpisode
        val account = snapshot.accountId

        // Exhaustion supersedes both tiers. Consuming their keys anyway is what stops a
        // recovery from 0 % to 15 % producing a "less than 20 % left" line for a limit the
        // user has already watched run out.
        if (exhausted) {
            val label = windows.first { it.severity == Severity.EXHAUSTED }.label
            return state to listOf(
                Event(account, key(account, episode, "exhausted"), "$name · $label exhausted")
                    .takeIf { settings.notifyOnExhausted },
                Event(account, key(account, episode, "warning"), ""),
                Event(account, key(account, episode, "critical"), ""),
            ).filterNotNull()
        }

        val label = worst?.label ?: return state to emptyList()
        val claims = mutableListOf<Event>()

        // The stronger tier is emitted and the weaker one is consumed silently, so a drop
        // straight from 40 % to 8 % says "less than 10 %" once rather than both lines at once.
        if (belowCritical) {
            claims += Event(account, key(account, episode, "warning"), "")
            claims += Event(
                account,
                key(account, episode, "critical"),
                if (settings.notifyBelow10Percent) {
                    "$name · $label: less than 10% remaining"
                } else {
                    ""
                },
            )
        } else {
            claims += Event(
                account,
                key(account, episode, "warning"),
                if (settings.notifyBelow20Percent) {
                    "$name · $label: less than 20% remaining"
                } else {
                    ""
                },
            )
        }

        return state to claims
    }

    /**
     * "Your limit resets in about half an hour."
     *
     * Keyed on the reset timestamp itself, because that is the only cycle identifier the
     * providers give. A provider correcting the timestamp therefore looks like a new cycle and
     * can notify twice — which is the honest limit of what these payloads support, and better
     * than keying on the window alone, which would go silent for every later reset.
     */
    private fun evaluateResetApproaching(
        name: String,
        snapshot: UsageSnapshot,
        settings: AppSettings,
        nowMs: Long,
    ): List<Event> {
        if (!settings.notifyOnResetApproaching) return emptyList()
        val leadMs = settings.resetApproachingMinutes.toLong() * MINUTE_MS

        return snapshot.windows.mapNotNull { window ->
            val resetAt = window.resetAt ?: return@mapNotNull null
            val remainingMs = resetAt - nowMs
            // Strictly ahead of us: a reset that has already passed is news to nobody.
            if (remainingMs <= 0L || remainingMs > leadMs) return@mapNotNull null

            val minutes = ceil(remainingMs.toDouble() / MINUTE_MS).toLong()
            Event(
                snapshot.accountId,
                key(snapshot.accountId, windowKey(window), "reset-approaching", resetAt.toString()),
                "$name · ${window.label} resets in about $minutes minutes",
            )
        }
    }

    /**
     * Reset credits about to lapse.
     *
     * Only credits with a real expiry and an available status qualify. A count without rows
     * cannot support this at all: a number says nothing about when anything expires, and
     * guessing would put a deadline on screen the provider never stated.
     */
    private fun evaluateExpiringCredits(
        usage: AccountUsage,
        snapshot: UsageSnapshot,
        settings: AppSettings,
        nowMs: Long,
    ): List<Event> {
        if (!settings.notifyOnResetCreditExpiring) return emptyList()
        val leadMs = settings.resetCreditExpiryLeadMinutes.toLong() * MINUTE_MS

        val expiring = snapshot.resetCredits.filter { credit ->
            val expiresAt = credit.expiresAt ?: return@filter false
            val remainingMs = expiresAt - nowMs
            remainingMs > 0L &&
                remainingMs <= leadMs &&
                credit.status.equals("available", ignoreCase = true) &&
                (credit.grantedAt == null || credit.grantedAt <= nowMs)
        }
        if (expiring.isEmpty()) return emptyList()

        // One line per account, but one claim per credit: two credits lapsing on different days
        // should notify twice, while two lapsing together should not produce two lines.
        val name = usage.account.label
        val hours = settings.resetCreditExpiryLeadMinutes / 60
        val within = if (hours >= 1) "$hours hours" else "${settings.resetCreditExpiryLeadMinutes} minutes"
        val noun = if (expiring.size == 1) "credit expires" else "credits expire"
        val line = "$name · ${expiring.size} reset $noun within $within"

        return expiring.mapIndexed { index, credit ->
            Event(
                snapshot.accountId,
                key(snapshot.accountId, credit.id, "credit-expiring"),
                // Only the first claim carries the text, so the group renders as one line even
                // when several credits are claimed at once.
                if (index == 0) line else "",
            )
        }
    }

    /**
     * Findings that describe a standing condition rather than an edge.
     *
     * These repeat on every sync by design — the notification is replaced, not stacked, so a
     * still-true fact stays visible without alerting again.
     */
    private fun standingFindings(
        usage: AccountUsage,
        snapshot: UsageSnapshot,
        settings: AppSettings,
    ): List<String> {
        if (!settings.notifyOnResetCreditAvailable) return emptyList()
        val count = snapshot.spendableResetCredits
        if (count <= 0) return emptyList()

        val noun = if (count == 1) "reset credit" else "reset credits"
        return listOf("${usage.account.label} · $count $noun available")
    }

    private fun authFinding(
        usage: AccountUsage,
        snapshot: UsageSnapshot?,
        settings: AppSettings,
    ): List<String> {
        if (!settings.notifyOnAuthExpired) return emptyList()
        val expired = snapshot?.errorMessage?.contains("expired", ignoreCase = true) == true
        return if (expired) listOf("${usage.account.label} needs to be reconnected") else emptyList()
    }

    /**
     * A window's identity within its account.
     *
     * Category and label together, because ids are provider-assigned and a provider that
     * renumbers them would re-arm every reset alert it has already sent.
     */
    private fun windowKey(window: UsageWindow): String = "${window.category.name}:${window.label}"

    private fun key(vararg parts: Any): String = parts.joinToString("|")
}
