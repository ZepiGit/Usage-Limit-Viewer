package com.usagelimits.core.notifications

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.time.Countdown
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
        // Seeded with every state we were given, not only the accounts in this sync. A provider
        // that returns a partial response would otherwise drop an account's state, restart its
        // episode numbering at 1, and collide with keys episode 1 already claimed — leaving
        // that account permanently silent. Evicting state is the caller's job, on deletion.
        val newStates = LinkedHashMap(states)
        val events = mutableListOf<Event>()
        val standing = mutableListOf<String>()

        for (usage in accounts) {
            val id = usage.account.localId
            val previous = states[id] ?: AccountState(accountId = id)
            val snapshot = usage.snapshot

            // A failed refresh proves nothing about quota. Advancing state on one would let a
            // network blip end a low-quota episode and re-arm the warning for the next sync.
            if (snapshot == null || snapshot.status == SnapshotStatus.FAILED) {
                newStates[id] = previous
                standing += authFinding(usage, snapshot, settings)
                continue
            }

            // Reprocessing a snapshot already seen must produce nothing: WorkManager reruns
            // happen, and every event below would otherwise fire a second time.
            if (previous.lastProcessedFetchedAt != null &&
                snapshot.fetchedAt <= previous.lastProcessedFetchedAt
            ) {
                newStates[id] = previous
                standing += standingFindings(usage, snapshot, settings)
                continue
            }

            val (state, accountEvents) = evaluateAccount(usage, snapshot, settings, previous, nowMs)
            newStates[id] = state
            events += accountEvents
            standing += standingFindings(usage, snapshot, settings)
        }

        return Outcome(newStates.values.toList(), events, standing)
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
     * The three low-quota tiers.
     *
     * A tier is a deduplication key, not a message. Whether the user hears anything depends on
     * their settings; *what* they hear describes the condition that was actually reached, so
     * an exhausted limit is never announced as "less than 20 % remaining".
     */
    private enum class Tier(val key: String) {
        WARNING("warning"),
        CRITICAL("critical"),
        EXHAUSTED("exhausted"),
        ;

        fun enabled(settings: AppSettings): Boolean = when (this) {
            WARNING -> settings.notifyBelow20Percent
            CRITICAL -> settings.notifyBelow10Percent
            EXHAUSTED -> settings.notifyOnExhausted
        }

        fun message(name: String, label: String): String = when (this) {
            WARNING -> "$name · $label: less than 20% remaining"
            CRITICAL -> "$name · $label: less than 10% remaining"
            EXHAUSTED -> "$name · $label exhausted"
        }
    }

    /**
     * The low-quota tiers, as one episode with one-shot keys.
     *
     * A single adjustable threshold cannot express this: the point of the second tier is that
     * an account already warned at 18 % should still say something at 8 %, and exactly once.
     * Reaching a tier consumes every weaker one too, so a fast burn produces one line rather
     * than a stack of them, and a later partial recovery cannot warn about a limit the user
     * has already watched run out.
     *
     * Two rules here exist because getting them wrong produced silence, which is the worst
     * failure a quota alert can have:
     *
     * The message describes the strongest tier REACHED, and is spoken if any reached tier is
     * enabled. Routing the message to its own tier's setting instead meant a user with only
     * the 20 % alert on heard nothing when quota crashed straight past 10 % — the urgent case
     * was the silent one — and a user with the exhausted alert off heard nothing at all when a
     * limit ran out.
     *
     * Every reached tier is always claimed, even when nothing is spoken. Dropping the claim
     * instead would deliver a stale alert the moment the setting was turned on, which is the
     * backlog this design exists to prevent.
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
        val exhaustedWindow = windows.firstOrNull { it.severity == Severity.EXHAUSTED }

        // An unknown percentage does not block recovery. Treating it as low did: one window
        // whose figure the provider stopped reporting vetoed recovery for good, so the episode
        // never ended and the account never alerted again. Unknown is an absence of evidence,
        // and the account already reads as ERROR on screen — adding permanent silence on top
        // of that helps nobody. `all` over an empty list is true, which is the same judgement:
        // an account reporting nothing is not an account known to be low.
        val recovered = exhaustedWindow == null &&
            windows.all { (it.remainingPercent ?: WARNING_PERCENT) >= WARNING_PERCENT }

        if (recovered) {
            return previous.copy(lowQuotaActive = false) to emptyList()
        }

        val belowWarning = remaining != null && remaining < WARNING_PERCENT
        val belowCritical = remaining != null && remaining < CRITICAL_PERCENT

        val reached = when {
            exhaustedWindow != null -> listOf(Tier.WARNING, Tier.CRITICAL, Tier.EXHAUSTED)
            belowCritical -> listOf(Tier.WARNING, Tier.CRITICAL)
            belowWarning -> listOf(Tier.WARNING)
            else -> emptyList()
        }
        if (reached.isEmpty()) return previous to emptyList()

        // An episode is the unit of deduplication. Starting one here — rather than at the first
        // notification — keeps the keys stable even while every setting is off.
        val state = if (previous.lowQuotaActive) {
            previous
        } else {
            previous.copy(lowQuotaEpisode = previous.lowQuotaEpisode + 1, lowQuotaActive = true)
        }
        val episode = state.lowQuotaEpisode
        val account = snapshot.accountId

        val condition = reached.last()
        val label = (exhaustedWindow ?: worst)?.label ?: return state to emptyList()
        val line = if (reached.any { it.enabled(settings) }) {
            condition.message(name, label)
        } else {
            ""
        }

        return state to reached.map { tier ->
            Event(
                account,
                key(account, episode, tier.key),
                // Only the strongest reached tier carries the text: the weaker ones exist to be
                // consumed so a later dip cannot re-announce a threshold already passed.
                if (tier == condition) line else "",
            )
        }
    }

    /**
     * "Your limit resets in about half an hour."
     *
     * Keyed on the reset timestamp itself, because that is the only cycle identifier the
     * providers give. A provider correcting the timestamp therefore looks like a new cycle and
     * can notify twice — which is the honest limit of what these payloads support, and better
     * than keying on the window alone, which would go silent for every later reset.
     *
     * The lead interval is measured against [nowMs], deliberately, not against the snapshot's
     * own `fetchedAt`. When evaluation runs late — a queued retry, a deferred worker — those
     * two diverge, and reading from `fetchedAt` would announce "resets in about 20 minutes"
     * for a reset that happened an hour ago. A heads-up that arrives late and wrong is worse
     * than one that does not arrive: this notification is a claim about the future, so it is
     * measured from the present.
     */
    private fun evaluateResetApproaching(
        name: String,
        snapshot: UsageSnapshot,
        settings: AppSettings,
        nowMs: Long,
    ): List<Event> {
        // Claimed even while the setting is off, so switching it on delivers what happens next
        // rather than a heads-up for a reset that has been approaching since yesterday. Only
        // the text is withheld.
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
                if (settings.notifyOnResetApproaching) {
                    "$name · ${window.label} resets in about $minutes minutes"
                } else {
                    ""
                },
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
        // Same reasoning as the reset heads-up: claimed while off, text withheld.
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

        val name = usage.account.label

        // One line per credit, each naming its own deadline, rather than one counted summary.
        //
        // A summary cannot be made exactly-once by a pure evaluator. Hanging it on the first
        // credit meant a second credit entering the window later found that key claimed, so
        // the only line carrying text was discarded and the new credit lapsed in silence.
        // Keying it by the whole set instead re-announces the moment the set shrinks — spend
        // one of two credits and the remaining one is announced again as though it were new.
        //
        // A credit's own id is the only key that means exactly "this credit, once". The cost is
        // two lines when two credits lapse together, and they are not redundant: they carry
        // different deadlines, which is the fact the user needs in order to act.
        return expiring.sortedBy { it.expiresAt }.map { credit ->
            val remainingMs = (credit.expiresAt ?: nowMs) - nowMs
            Event(
                snapshot.accountId,
                key(snapshot.accountId, credit.id, "credit-expiring"),
                if (settings.notifyOnResetCreditExpiring) {
                    "$name · a reset credit expires in ${Countdown.format(remainingMs)}"
                } else {
                    ""
                },
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
