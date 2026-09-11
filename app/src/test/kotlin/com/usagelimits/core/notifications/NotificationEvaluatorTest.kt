package com.usagelimits.core.notifications

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ResetCredit
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behaviour these pin is "said once", which no single snapshot can express.
 *
 * Every case here is a sequence: the evaluator is run repeatedly with the state it handed back,
 * exactly as the publisher does, because a rule that looks right on one reading is usually the
 * one that fires again fifteen minutes later.
 */
class NotificationEvaluatorTest {

    private val now = 1_757_000_000_000L
    private val settings = AppSettings()

    private fun account(id: String = "acct") = ProviderAccount(
        localId = id,
        provider = ProviderId.CODEX,
        externalAccountId = "ext-$id",
        email = "$id@example.com",
        // Given a display name, `label` uses it. Without one it falls back to a MASKED email,
        // which is the behaviour `a notification line never exposes a raw email` pins below.
        displayName = "Account $id",
        plan = "Plus",
        credentialReference = "ref-$id",
        createdAt = now,
        lastSuccessfulSync = now,
    )

    private fun usage(
        remaining: Double?,
        fetchedAt: Long = now,
        status: SnapshotStatus = SnapshotStatus.OK,
        exhausted: Boolean = false,
        resetAt: Long? = null,
        credits: List<ResetCredit> = emptyList(),
        id: String = "acct",
        label: String = "5h limit",
    ) = AccountUsage(
        account = account(id),
        snapshot = UsageSnapshot(
            accountId = id,
            fetchedAt = fetchedAt,
            status = status,
            windows = listOf(
                UsageWindow(
                    id = "w",
                    label = label,
                    category = WindowCategory.FIVE_HOUR,
                    usedPercent = remaining?.let { 100.0 - it },
                    periodSeconds = 18_000,
                    resetAt = resetAt,
                    exhausted = exhausted,
                ),
            ),
            resetCredits = credits,
        ),
    )

    /**
     * The publisher, reduced to the two things that decide what a user sees.
     *
     * The evaluator deliberately re-offers keys it has offered before — deduplication belongs
     * to the ledger, where an insert either wins or loses atomically. Testing the evaluator
     * without a ledger therefore reads every re-offer as a repeat notification, which is
     * exactly the false alarm this harness exists to avoid. [consumed] is that ledger.
     */
    private class Publisher {
        private val consumed = mutableSetOf<String>()
        private var states = emptyMap<String, NotificationEvaluator.AccountState>()

        /** Runs one sync and returns only the lines a real ledger would let through. */
        fun sync(
            usages: List<AccountUsage>,
            settings: AppSettings,
            nowMs: Long,
        ): List<String> {
            val outcome = NotificationEvaluator.evaluate(usages, settings, states, nowMs)
            states = outcome.states.associateBy { it.accountId }
            // Claim before posting, as the real publisher does.
            val fresh = outcome.events.filter { consumed.add(it.key) }
            return fresh.map { it.line }.filter { it.isNotBlank() } + outcome.standingFindings
        }

        fun stateOf(accountId: String) = states.getValue(accountId)
    }

    private fun publisher() = Publisher()

    /** Runs one sync in isolation, for the cases that inspect raw events rather than lines. */
    private fun run(
        usages: List<AccountUsage>,
        states: Map<String, NotificationEvaluator.AccountState>,
        settings: AppSettings = this.settings,
        nowMs: Long = now,
    ) = NotificationEvaluator.evaluate(usages, settings, states, nowMs)

    private fun NotificationEvaluator.Outcome.stateMap() = states.associateBy { it.accountId }

    private fun NotificationEvaluator.Outcome.lines() =
        events.map { it.line }.filter { it.isNotBlank() }

    private fun NotificationEvaluator.Outcome.keys() = events.map { it.key }.toSet()

    // region low-quota tiers

    /** An account with several windows, each with its own remaining percentage. */
    private fun multi(
        vararg windows: Pair<String, Double?>,
        fetchedAt: Long = now,
        id: String = "acct",
    ) = AccountUsage(
        account = account(id),
        snapshot = UsageSnapshot(
            accountId = id,
            fetchedAt = fetchedAt,
            status = SnapshotStatus.OK,
            windows = windows.mapIndexed { i, (label, remaining) ->
                UsageWindow(
                    id = "w$i",
                    label = label,
                    category = if (i == 0) WindowCategory.FIVE_HOUR else WindowCategory.WEEKLY,
                    usedPercent = remaining?.let { 100.0 - it },
                    periodSeconds = if (i == 0) 18_000 else 604_800,
                    resetAt = null,
                    exhausted = remaining != null && remaining <= 0.0,
                )
            },
        ),
    )

    @Test
    fun `one window running out does not silence another window's crossings`() {
        // The bug: the episode was per ACCOUNT. The five-hour window running out claimed every
        // tier for the account, and the weekly window then crossing 20 %, 10 % and 0 % found
        // nothing left to claim — the user was never told about the limit that matters most.
        val publisher = publisher()

        assertEquals(
            listOf("Account acct · 5h limit exhausted"),
            publisher.sync(listOf(multi("5h limit" to 0.0, "Weekly" to 60.0)), settings, now),
        )
        assertEquals(
            listOf("Account acct · Weekly: less than 20% remaining"),
            publisher.sync(listOf(multi("5h limit" to 0.0, "Weekly" to 18.0, fetchedAt = now + 1)), settings, now),
        )
        assertEquals(
            listOf("Account acct · Weekly: less than 10% remaining"),
            publisher.sync(listOf(multi("5h limit" to 0.0, "Weekly" to 8.0, fetchedAt = now + 2)), settings, now),
        )
        assertEquals(
            listOf("Account acct · Weekly exhausted"),
            publisher.sync(listOf(multi("5h limit" to 0.0, "Weekly" to 0.0, fetchedAt = now + 3)), settings, now),
        )
        // And a window that recovers re-arms on its own, without waiting for the other.
        assertTrue(publisher.sync(listOf(multi("5h limit" to 90.0, "Weekly" to 0.0, fetchedAt = now + 4)), settings, now).isEmpty())
        assertEquals(
            listOf("Account acct · 5h limit: less than 20% remaining"),
            publisher.sync(listOf(multi("5h limit" to 15.0, "Weekly" to 0.0, fetchedAt = now + 5)), settings, now),
        )
    }

    @Test
    fun `two windows crossing in one sync are both spoken`() {
        val lines = run(listOf(multi("5h limit" to 8.0, "Weekly" to 18.0)), emptyMap()).lines()
        assertEquals(
            listOf(
                "Account acct · 5h limit: less than 10% remaining",
                "Account acct · Weekly: less than 20% remaining",
            ),
            lines,
        )
    }

    @Test
    fun `keys carry the window identity`() {
        val keys = run(listOf(multi("5h limit" to 18.0, "Weekly" to 18.0)), emptyMap()).keys()
        assertEquals(setOf("acct|FIVE_HOUR:5h limit|1|warning", "acct|WEEKLY:Weekly|1|warning"), keys)
    }


    @Test
    fun `crossing below twenty percent warns once and then stays quiet`() {
        val publisher = publisher()

        assertEquals(
            listOf("Account acct · 5h limit: less than 20% remaining"),
            publisher.sync(listOf(usage(remaining = 18.0)), settings, now),
        )
        // Still below, same episode: nothing more to say for the next five hours.
        assertTrue(
            publisher.sync(listOf(usage(remaining = 17.0, fetchedAt = now + 1)), settings, now)
                .isEmpty(),
        )
        assertTrue(
            publisher.sync(listOf(usage(remaining = 16.0, fetchedAt = now + 2)), settings, now)
                .isEmpty(),
        )
    }

    @Test
    fun `dropping below ten percent escalates once within the same episode`() {
        val publisher = publisher()
        publisher.sync(listOf(usage(remaining = 18.0)), settings, now)

        assertEquals(
            listOf("Account acct · 5h limit: less than 10% remaining"),
            publisher.sync(listOf(usage(remaining = 8.0, fetchedAt = now + 1)), settings, now),
        )
        // And once only — the escalation is not repeated every sync while it stays tight.
        assertTrue(
            publisher.sync(listOf(usage(remaining = 5.0, fetchedAt = now + 2)), settings, now)
                .isEmpty(),
        )
    }

    @Test
    fun `falling straight past both tiers says the stronger thing only`() {
        // A fast burn from 40 % to 8 % between two syncs must not produce two notifications a
        // second apart.
        val outcome = run(listOf(usage(remaining = 8.0)), emptyMap())

        assertEquals(
            listOf("Account acct · 5h limit: less than 10% remaining"),
            outcome.lines(),
        )
        // Both keys are consumed, so a later recovery to 15 % cannot produce a delayed warning.
        assertEquals(2, outcome.events.size)
        assertTrue(outcome.events.any { it.key.endsWith("warning") })
    }

    @Test
    fun `recovering from eight to fifteen percent produces no delayed warning`() {
        val publisher = publisher()
        publisher.sync(listOf(usage(remaining = 8.0)), settings, now)

        // Climbing back to 15 % is still inside the episode. Announcing "less than 20 %" now
        // would be a warning about a limit that just got better.
        assertTrue(
            publisher.sync(listOf(usage(remaining = 15.0, fetchedAt = now + 1)), settings, now)
                .isEmpty(),
        )
    }

    @Test
    fun `a full recovery rearms the warning for the next dip`() {
        val publisher = publisher()
        publisher.sync(listOf(usage(remaining = 8.0)), settings, now)
        assertTrue(
            publisher.sync(listOf(usage(remaining = 90.0, fetchedAt = now + 1)), settings, now)
                .isEmpty(),
        )

        // Next week's quota running low is news again, so the episode number must have moved
        // on rather than the old keys being reused.
        assertEquals(
            listOf("Account acct · 5h limit: less than 20% remaining"),
            publisher.sync(listOf(usage(remaining = 18.0, fetchedAt = now + 2)), settings, now),
        )
    }

    @Test
    fun `exhaustion supersedes the low tiers and consumes them`() {
        val publisher = publisher()

        assertEquals(
            listOf("Account acct · 5h limit exhausted"),
            publisher.sync(listOf(usage(remaining = 0.0, exhausted = true)), settings, now),
        )
        // Partial recovery to 15 % must not now warn about 20 %: the user has already watched
        // this limit run out, and being told it is "low" afterwards is noise.
        assertTrue(
            publisher.sync(listOf(usage(remaining = 15.0, fetchedAt = now + 1)), settings, now)
                .isEmpty(),
        )
    }

    @Test
    fun `a disabled tier still consumes its key so enabling it does not replay`() {
        val muted = settings.copy(notifyBelow20Percent = false, notifyBelow10Percent = false)
        val publisher = publisher()

        assertTrue(publisher.sync(listOf(usage(remaining = 18.0)), muted, now).isEmpty())

        // Turning notifications on must not deliver a backlog of thresholds already passed.
        assertTrue(
            publisher.sync(listOf(usage(remaining = 17.0, fetchedAt = now + 1)), settings, now)
                .isEmpty(),
        )
    }

    @Test
    fun `an unknown percentage does not block recovery for ever`() {
        // Treating unknown as "still low" looked cautious and was the opposite. One window
        // whose figure the provider stopped reporting vetoed recovery permanently, so the
        // episode never ended and the account never alerted again — silence, which is the
        // worst failure a quota alert can have. The account already reads as ERROR on screen;
        // there is nothing to gain by muting it as well.
        val first = run(listOf(usage(remaining = 8.0)), emptyMap())
        val unknown = run(listOf(usage(remaining = null, fetchedAt = now + 1)), first.stateMap())

        assertFalse(unknown.stateMap().getValue("acct").lowQuotaActive)

        // And the next readable dip is heard, rather than swallowed by the old episode.
        val publisher = publisher()
        publisher.sync(listOf(usage(remaining = 8.0)), settings, now)
        publisher.sync(listOf(usage(remaining = null, fetchedAt = now + 1)), settings, now)
        assertEquals(
            listOf("Account acct · 5h limit: less than 10% remaining"),
            publisher.sync(listOf(usage(remaining = 5.0, fetchedAt = now + 2)), settings, now),
        )
    }

    @Test
    fun `an account missing from one sync keeps its episode`() {
        // A partial provider response dropped the account's state, restarting its episode at 1
        // and colliding with keys episode 1 had already claimed — after which that account was
        // permanently silent.
        val publisher = publisher()
        publisher.sync(listOf(usage(remaining = 8.0)), settings, now)

        // A sync that simply does not mention it.
        publisher.sync(emptyList(), settings, now)

        // Recover, then dip again: this must be audible, which it cannot be if the episode
        // number was reset and the old keys were reused.
        publisher.sync(listOf(usage(remaining = 90.0, fetchedAt = now + 2)), settings, now)
        assertEquals(
            listOf("Account acct · 5h limit: less than 20% remaining"),
            publisher.sync(listOf(usage(remaining = 18.0, fetchedAt = now + 3)), settings, now),
        )
    }

    @Test
    fun `a crash past ten percent is heard by someone who enabled only the twenty tier`() {
        // The urgent case was the silent one: the warning line was hard-blanked whenever the
        // critical tier was reached, and the critical line was withheld because its own
        // setting was off, so a drop from 40 % to 8 % produced nothing at all.
        val onlyWarning = settings.copy(notifyBelow10Percent = false)
        val publisher = publisher()

        assertEquals(
            listOf("Account acct · 5h limit: less than 10% remaining"),
            publisher.sync(listOf(usage(remaining = 8.0)), onlyWarning, now),
        )
    }

    @Test
    fun `an exhausted limit is heard by someone who disabled only the exhausted alert`() {
        val noExhausted = settings.copy(notifyOnExhausted = false)
        val publisher = publisher()

        // The message still describes what actually happened rather than understating it as a
        // low-quota warning.
        assertEquals(
            listOf("Account acct · 5h limit exhausted"),
            publisher.sync(
                listOf(usage(remaining = 0.0, exhausted = true)), noExhausted, now,
            ),
        )
    }

    @Test
    fun `an exhausted limit with every alert off is silent and stays silent`() {
        val allOff = settings.copy(
            notifyOnExhausted = false,
            notifyBelow20Percent = false,
            notifyBelow10Percent = false,
        )
        val publisher = publisher()

        assertTrue(
            publisher.sync(listOf(usage(remaining = 0.0, exhausted = true)), allOff, now).isEmpty(),
        )
        // Turning the alerts on must not then deliver the exhaustion that happened while they
        // were off. The claim was dropped rather than consumed, so it used to.
        assertTrue(
            publisher.sync(
                listOf(usage(remaining = 0.0, exhausted = true, fetchedAt = now + 1)),
                settings,
                now,
            ).isEmpty(),
        )
    }

    @Test
    fun `a failed snapshot changes nothing`() {
        val first = run(listOf(usage(remaining = 8.0)), emptyMap())
        val failed = run(
            listOf(usage(remaining = 95.0, fetchedAt = now + 1, status = SnapshotStatus.FAILED)),
            first.stateMap(),
        )

        // A network blip reporting stale numbers must not end the episode.
        assertEquals(first.stateMap().getValue("acct"), failed.stateMap().getValue("acct"))
    }

    @Test
    fun `reprocessing the same snapshot produces nothing new`() {
        val publisher = publisher()
        publisher.sync(listOf(usage(remaining = 18.0)), settings, now)

        // WorkManager reruns happen; the same fetch must not alert twice.
        assertTrue(publisher.sync(listOf(usage(remaining = 18.0)), settings, now).isEmpty())
    }

    @Test
    fun `each account keeps its own episode`() {
        val outcome = run(
            listOf(usage(remaining = 18.0, id = "a"), usage(remaining = 8.0, id = "b")),
            emptyMap(),
        )

        assertEquals(setOf("a", "b"), outcome.events.map { it.accountId }.toSet())
        assertEquals(2, outcome.lines().size)
    }

    // endregion

    // region reset approaching

    @Test
    fun `a reset inside the lead interval notifies once per scheduled time`() {
        val enabled = settings.copy(notifyOnResetApproaching = true)
        val resetAt = now + 10 * 60_000L

        val publisher = publisher()

        assertEquals(
            listOf("Account acct · 5h limit resets in about 10 minutes"),
            publisher.sync(listOf(usage(remaining = 80.0, resetAt = resetAt)), enabled, now),
        )
        // Keyed on the scheduled time, so a second sync inside the same interval says nothing.
        assertTrue(
            publisher.sync(
                listOf(usage(remaining = 80.0, resetAt = resetAt, fetchedAt = now + 1)),
                enabled,
                now + 60_000L,
            ).isEmpty(),
        )
    }

    @Test
    fun `a reset already in the past never notifies`() {
        val enabled = settings.copy(notifyOnResetApproaching = true)
        val outcome = run(listOf(usage(remaining = 80.0, resetAt = now - 1)), emptyMap(), enabled)

        assertTrue(outcome.events.none { it.key.contains("reset-approaching") })
    }

    @Test
    fun `a reset beyond the lead interval waits`() {
        val enabled = settings.copy(notifyOnResetApproaching = true, resetApproachingMinutes = 30)
        val outcome = run(
            listOf(usage(remaining = 80.0, resetAt = now + 31 * 60_000L)),
            emptyMap(),
            enabled,
        )

        assertTrue(outcome.events.none { it.key.contains("reset-approaching") })
    }

    @Test
    fun `a newly scheduled reset earns its own key`() {
        val enabled = settings.copy(notifyOnResetApproaching = true)
        val publisher = publisher()
        publisher.sync(listOf(usage(remaining = 80.0, resetAt = now + 10 * 60_000L)), enabled, now)

        // The next cycle is a different reset, so it gets its own warning rather than going
        // silent for the life of the account.
        assertEquals(
            listOf("Account acct · 5h limit resets in about 10 minutes"),
            publisher.sync(
                listOf(usage(remaining = 80.0, resetAt = now + 70 * 60_000L, fetchedAt = now + 1)),
                enabled,
                now + 60 * 60_000L,
            ),
        )
    }

    @Test
    fun `reset approaching says nothing unless asked for, but still consumes the key`() {
        val publisher = publisher()
        val resetAt = now + 10 * 60_000L

        assertTrue(
            publisher.sync(listOf(usage(remaining = 80.0, resetAt = resetAt)), settings, now)
                .isEmpty(),
        )
        // Switching it on delivers what happens next, not a heads-up for a reset that has been
        // approaching since the last sync.
        val enabled = settings.copy(notifyOnResetApproaching = true)
        assertTrue(
            publisher.sync(
                listOf(usage(remaining = 80.0, resetAt = resetAt, fetchedAt = now + 1)),
                enabled,
                now,
            ).isEmpty(),
        )
    }

    // endregion

    // region expiring reset credits

    @Test
    fun `a credit lapsing inside the lead window notifies once, with its own deadline`() {
        val credit = ResetCredit("c1", now - 1000, now + 3_600_000, "available")
        val publisher = publisher()

        // The deadline is an event and fires once; the inventory line is a standing fact and
        // repeats, because "you hold one credit" stays true until it is spent.
        assertEquals(
            listOf(
                "Account acct · a reset credit expires in 1h",
                "Account acct · 1 reset credit available",
            ),
            publisher.sync(listOf(usage(remaining = 80.0, credits = listOf(credit))), settings, now),
        )
        assertEquals(
            listOf("Account acct · 1 reset credit available"),
            publisher.sync(
                listOf(usage(remaining = 80.0, credits = listOf(credit), fetchedAt = now + 1)),
                settings,
                now,
            ),
        )
    }

    @Test
    fun `a credit entering the window later is announced rather than swallowed`() {
        // The defect: a counted summary line was hung on the first expiring credit, so a second
        // credit arriving later found that key already claimed. The only line carrying text was
        // discarded and the new credit lapsed in silence.
        val first = ResetCredit("c1", now - 1000, now + 3_600_000, "available")
        val second = ResetCredit("c2", now - 1000, now + 7_200_000, "available")
        val publisher = publisher()

        publisher.sync(listOf(usage(remaining = 80.0, credits = listOf(first))), settings, now)

        assertEquals(
            listOf(
                "Account acct · a reset credit expires in 2h",
                "Account acct · 2 reset credits available",
            ),
            publisher.sync(
                listOf(
                    usage(remaining = 80.0, credits = listOf(first, second), fetchedAt = now + 1),
                ),
                settings,
                now,
            ),
        )
    }

    @Test
    fun `two credits lapsing together each carry their own deadline`() {
        // Two lines rather than one counted summary, and they are not redundant: the deadlines
        // differ, which is the fact the user needs in order to act.
        val credits = listOf(
            ResetCredit("c1", now - 1000, now + 3_600_000, "available"),
            ResetCredit("c2", now - 1000, now + 7_200_000, "available"),
        )
        val publisher = publisher()

        assertEquals(
            listOf(
                "Account acct · a reset credit expires in 1h",
                "Account acct · a reset credit expires in 2h",
                "Account acct · 2 reset credits available",
            ),
            publisher.sync(listOf(usage(remaining = 80.0, credits = credits)), settings, now),
        )
    }

    @Test
    fun `a spent credit never claims an expiry`() {
        val credit = ResetCredit("c1", now - 1000, now + 3_600_000, "consumed")
        val outcome = run(listOf(usage(remaining = 80.0, credits = listOf(credit))), emptyMap())

        assertTrue(outcome.events.none { it.key.contains("credit-expiring") })
    }

    @Test
    fun `a credit with no expiry cannot be said to be expiring`() {
        // A count without a date proves nothing; guessing one would put a deadline on screen
        // the provider never stated.
        val credit = ResetCredit("c1", now - 1000, expiresAt = null, status = "available")
        val outcome = run(listOf(usage(remaining = 80.0, credits = listOf(credit))), emptyMap())

        assertTrue(outcome.events.none { it.key.contains("credit-expiring") })
    }

    @Test
    fun `an already expired credit does not notify`() {
        val credit = ResetCredit("c1", now - 1000, now - 1, "available")
        val outcome = run(listOf(usage(remaining = 80.0, credits = listOf(credit))), emptyMap())

        assertTrue(outcome.events.none { it.key.contains("credit-expiring") })
    }

    // endregion

    // region standing findings

    @Test
    fun `available credits are a standing fact, not an event`() {
        // It stays true across syncs, so it must not alert again each time — it belongs in the
        // replaced notification body, not in the claim ledger.
        val credit = ResetCredit("c1", now - 1000, expiresAt = null, status = "available")
        val outcome = run(listOf(usage(remaining = 80.0, credits = listOf(credit))), emptyMap())

        assertEquals(
            listOf("Account acct · 1 reset credit available"),
            outcome.standingFindings,
        )
        assertTrue(outcome.events.isEmpty())
    }

    @Test
    fun `an expired sign-in is reported even though the snapshot failed`() {
        val failed = AccountUsage(
            account = account(),
            snapshot = UsageSnapshot(
                accountId = "acct",
                fetchedAt = now,
                status = SnapshotStatus.FAILED,
                windows = emptyList(),
                errorMessage = "refresh token expired",
            ),
        )

        val outcome = run(listOf(failed), emptyMap())

        assertEquals(listOf("Account acct needs to be reconnected"), outcome.standingFindings)
    }

    @Test
    fun `a notification line never exposes a raw email address`() {
        // Notifications render on a lock screen. An account identified only by its email must
        // appear masked there, the same way it does in the app.
        val anonymous = AccountUsage(
            account = account().copy(displayName = null),
            snapshot = usage(remaining = 8.0).snapshot,
        )

        val lines = run(listOf(anonymous), emptyMap()).lines()

        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { it.contains("acct@example.com") })
        assertTrue(lines.any { it.contains("@example.com") })
    }

    @Test
    fun `no findings at all when everything is healthy`() {
        val outcome = run(listOf(usage(remaining = 80.0)), emptyMap())

        assertTrue(outcome.events.isEmpty())
        assertTrue(outcome.standingFindings.isEmpty())
        assertFalse(outcome.stateMap().getValue("acct").lowQuotaActive)
    }

    // endregion

    // region windows that share a category and a label

    private fun twoAliasingWindows(
        firstRemaining: Double,
        secondRemaining: Double,
        fetchedAt: Long = now,
    ) = AccountUsage(
        account = account("acct"),
        snapshot = UsageSnapshot(
            accountId = "acct",
            fetchedAt = fetchedAt,
            status = SnapshotStatus.OK,
            windows = listOf(
                UsageWindow(
                    id = "w1",
                    label = "Usage",
                    category = WindowCategory.OTHER,
                    usedPercent = 100.0 - firstRemaining,
                    periodSeconds = null,
                    resetAt = null,
                    exhausted = firstRemaining <= 0.0,
                ),
                UsageWindow(
                    id = "w2",
                    label = "Usage",
                    category = WindowCategory.OTHER,
                    usedPercent = 100.0 - secondRemaining,
                    periodSeconds = null,
                    resetAt = null,
                    exhausted = secondRemaining <= 0.0,
                ),
            ),
        ),
    )

    @Test
    fun `two windows sharing a category and label do not silence each other`() {
        // A window's identity is category and label, deliberately: ids are provider-assigned
        // and renumbering them would re-arm every reset alert already sent. But nothing
        // upstream promises that pair is unique, and when two windows collapse to one
        // identity they share an episode and its tier keys — the exact defect per-window
        // episodes exist to fix, reappearing inside the aliasing group.
        //
        // The first window is exhausted, so it claims warning, critical and exhausted for
        // that identity, and only the strongest carries text. The second is merely low. If
        // they share an identity its warning finds the key already spent and says nothing.
        val publisher = publisher()

        val lines = publisher.sync(
            listOf(twoAliasingWindows(firstRemaining = 0.0, secondRemaining = 15.0)),
            settings,
            now,
        )

        assertTrue(
            "the second window's warning must not be swallowed by the first's claim: $lines",
            lines.any { it.contains("15%") || it.contains("low") || it.contains("20%") },
        )
    }

    @Test
    fun `aliasing windows keep separate episodes across syncs`() {
        // The consequence over time, not just in one pass: each window has to be able to
        // cross its own thresholds later without finding the other's keys in the way.
        val publisher = publisher()
        publisher.sync(
            listOf(twoAliasingWindows(firstRemaining = 0.0, secondRemaining = 60.0)),
            settings,
            now,
        )

        // The second window now runs out on its own. It must be able to say so.
        val lines = publisher.sync(
            // A NEW fetch, not the same snapshot re-delivered: quota edges are deliberately
            // evaluated once per fetch, so reusing the timestamp would test the dedup rather
            // than the identity.
            listOf(
                twoAliasingWindows(
                    firstRemaining = 0.0,
                    secondRemaining = 0.0,
                    fetchedAt = now + 1,
                ),
            ),
            settings,
            now + 1,
        )

        assertTrue(
            "the second window running out must be announced: $lines",
            lines.isNotEmpty(),
        )
    }

    // endregion

    @Test
    fun `state and events are keyed in the same namespace`() {
        // State was looked up by the account's own id while every event was keyed by the
        // snapshot's. The two agree by construction today — which is exactly what makes the
        // disagreement invisible if it ever stops being true: the ledger would remember
        // episodes under one id while the keys claimed them under another, and dedup would
        // stop working with no symptom but notifications that repeat or never arrive.
        //
        // A snapshot carrying a different id than the account it is attached to is the shape
        // of that mistake, so it is what is fed in here.
        val mismatched = AccountUsage(
            account = account("acct"),
            snapshot = UsageSnapshot(
                accountId = "some-other-id",
                fetchedAt = now,
                status = SnapshotStatus.OK,
                windows = listOf(
                    UsageWindow(
                        id = "w",
                        label = "5h limit",
                        category = WindowCategory.FIVE_HOUR,
                        usedPercent = 95.0,
                        periodSeconds = 18_000,
                        resetAt = now + 3_600_000,
                        exhausted = false,
                    ),
                ),
            ),
        )

        val outcome = run(listOf(mismatched), emptyMap())

        assertEquals(listOf("acct"), outcome.states.map { it.accountId })
        assertTrue(
            "every key must sit in the account's namespace, not the snapshot's: ${outcome.keys()}",
            outcome.keys().isNotEmpty() && outcome.keys().all { it.startsWith("acct|") },
        )
    }

    @Test
    fun `aliasing windows do not manufacture a fresh episode on every sync`() {
        // The sharper consequence of two windows sharing an identity, and the reason the
        // identity fix matters beyond one swallowed line.
        //
        // With a shared WindowState the loop mutates it per window and reads it back for the
        // next: a healthy window DEACTIVATES the episode its low neighbour just started, so
        // the neighbour opens a brand-new episode on the following sync. Every key is then
        // unclaimed, and the ledger cannot suppress what it has never seen. Nothing about the
        // account changes but `fetchedAt`, and the user is warned again, and again, for ever.
        //
        // Separate identities make it structurally impossible; this pins that.
        val publisher = publisher()
        val lines = (0..3).map { sync ->
            publisher.sync(
                listOf(
                    twoAliasingWindows(
                        firstRemaining = 50.0,
                        secondRemaining = 15.0,
                        // A NEW fetch each time, which is what gets past the freshness guard
                        // and into the quota path at all.
                        fetchedAt = now + sync,
                    ),
                ),
                settings,
                now + sync,
            )
        }

        val spoken = lines.count { it.isNotEmpty() }
        assertEquals(
            "the same unchanged account must be warned once, not once per sync: $lines",
            1,
            spoken,
        )
    }

    // region parity with iOS

    @Test
    fun `a second entry for one account sees what the first decided`() {
        // Nothing enforces that one evaluation sees an account only once, and reading the
        // ORIGINAL state map for each entry meant a second entry ignored what the first had
        // just decided. A recovery at t1 followed by a fresh dip at t2, in one pass, therefore
        // re-emitted the episode the recovery had ended — which the ledger suppresses, so the
        // user hears nothing about the dip. iOS carried the state forward and disagreed.
        // Both entries must be NEWER than the state carried in, or the freshness guard skips
        // them as already processed and the recovery never happens — which is what my first
        // version of this test did, and it failed for that reason rather than the real one.
        val first = run(listOf(usage(remaining = 15.0)), emptyMap())
        val carriedIn = first.stateMap()

        val recovered = usage(remaining = 50.0, fetchedAt = now + 1)
        val dipped = usage(remaining = 15.0, fetchedAt = now + 2)

        val outcome = run(listOf(recovered, dipped), carriedIn, nowMs = now + 2)

        val episodes = outcome.keys().mapNotNull { it.split("|").getOrNull(2)?.toIntOrNull() }
        assertTrue(
            "the dip must open a NEW episode, not reuse the one the recovery ended: ${outcome.keys()}",
            episodes.any { it > carriedIn.values.first().lowQuotaEpisode },
        )
    }

    @Test
    fun `a status that is not available in any case is not available`() {
        // Kotlin asked `equals(ignoreCase = true)`, which folds a dotless i onto i; Swift's
        // `lowercased()` does not. A provider sending "avaılable" was therefore spendable on
        // Android and not on iOS. Both take the stricter reading now, and the same one.
        val dotless = ResetCredit("c1", now - 1000, now + 3_600_000, "ava\u0131lable")
        val snapshot = UsageSnapshot(
            accountId = "acct",
            fetchedAt = now,
            status = SnapshotStatus.OK,
            windows = emptyList(),
            resetCredits = listOf(dotless),
        )

        assertEquals(0, snapshot.spendableResetCreditsAt(now))
        assertEquals(0, snapshot.heldResetCredits)
    }

    @Test
    fun `two spellings of one label are one window`() {
        // Swift compares strings by canonical equivalence and Kotlin does not, so "Café"
        // written as e-acute and as e-plus-combining-accent was one identity on iOS and two on
        // Android — and the platforms then disagreed about episodes for the same account.
        val precomposed = "Caf\u00e9"
        val decomposed = "Cafe\u0301"
        val windows = listOf(
            UsageWindow("w1", precomposed, WindowCategory.OTHER, 40.0, null, null, false),
            UsageWindow("w2", decomposed, WindowCategory.OTHER, 95.0, null, null, false),
        )
        val outcome = run(
            listOf(
                AccountUsage(
                    account = account("acct"),
                    snapshot = UsageSnapshot("acct", now, SnapshotStatus.OK, windows),
                ),
            ),
            emptyMap(),
        )

        val identities = outcome.keys().map { it.split("|").getOrNull(1) }.toSet()
        assertEquals(
            "the two spellings must be recognised as one label, and so disambiguated by id: $identities",
            identities.size,
            identities.filterNotNull().count { it.contains("#") },
        )
    }

    // endregion

    @Test
    fun `state for an account missing from this sync is carried through, not dropped`() {
        // The outcome is built from a map that must be SEEDED with everything handed in, not
        // started empty. Starting empty reads more naturally — the outcome is
        // `newStates.values`, and seeding means a deleted account's state lingers until the
        // caller evicts it — which is exactly why this needs a test rather than a comment.
        //
        // If it were emptied, an account absent from one partial sync would lose its state:
        // `lastProcessedFetchedAt` back to nothing so every snapshot re-evaluates, and episode
        // numbering back to the start, minting keys the ledger has already swallowed. The
        // account would then go permanently silent — no threshold, no recovery, ever again.
        val seeded = run(listOf(usage(remaining = 15.0)), emptyMap()).stateMap()
        assertTrue("precondition: the first pass must produce state", seeded.isNotEmpty())

        // A second sync that does not mention that account at all — a provider returning a
        // partial response, or one account removed from the batch.
        val outcome = run(
            listOf(usage(remaining = 80.0, id = "other")),
            seeded,
            nowMs = now + 1,
        )

        assertTrue(
            "the absent account's state must survive: ${outcome.states.map { it.accountId }}",
            outcome.states.any { it.accountId == "acct" },
        )
    }

    // region silencing one account

    /**
     * A silenced account says nothing — and its state keeps moving anyway.
     *
     * The second half is the one worth pinning. Suppressing the events but freezing the state
     * would mean un-silencing an account replays whatever episode it was in when it went
     * quiet: the evaluator would see the snapshot as new, decide the account had just dropped
     * below its threshold, and notify about a dip from hours ago. Silence is "do not tell me",
     * not "pretend this did not happen".
     */
    @Test
    fun `a muted account claims its keys silently and says nothing`() {
        val settings = AppSettings(mutedAccountIds = setOf("acct"))
        val outcome = NotificationEvaluator.evaluate(
            accounts = listOf(usage(remaining = 5.0)),
            settings = settings,
            states = emptyMap(),
            nowMs = now,
        )

        // Both halves matter. The events must still exist so the ledger can claim their keys
        // — that is what stops the threshold being announced the day the mute is lifted — and
        // every one of them must be blank so the shade stays quiet now. "No events" would pass
        // an implementation that drops them, which is the replay-on-unmute bug.
        assertTrue("the keys are still offered for claiming", outcome.events.isNotEmpty())
        assertTrue(
            "but none carries text: " + outcome.events.filter { it.line.isNotBlank() }.map { it.key },
            outcome.events.all { it.line.isBlank() },
        )
        assertTrue("and contributes no standing line either", outcome.standingFindings.isEmpty())
    }

    @Test
    fun `muting one account does not silence the others`() {
        val settings = AppSettings(mutedAccountIds = setOf("quiet"))
        val outcome = NotificationEvaluator.evaluate(
            accounts = listOf(
                usage(remaining = 5.0, id = "quiet"),
                usage(remaining = 5.0, id = "loud"),
            ),
            settings = settings,
            states = emptyMap(),
            nowMs = now,
        )

        assertEquals(
            "only the account that was not silenced speaks",
            listOf("loud"),
            outcome.events.filter { it.line.isNotBlank() }.map { it.accountId }.distinct(),
        )
        assertTrue(
            "the silenced one still has its keys claimed",
            outcome.events.any { it.accountId == "quiet" },
        )
    }

    @Test
    fun `un-muting does not announce a threshold crossed while muted`() {
        // The scenario the "drop the events" implementation got wrong. Muted at 15 %: the
        // warning key must be CLAIMED, blank. Unmuted a minute later, still at 15 %, with a
        // newer snapshot: the same episode, the same key, already spent — nothing to say. The
        // dropping implementation left the key unclaimed, found it now, and announced a dip
        // the user had explicitly asked not to hear about.
        val publisher = Publisher()
        val muted = AppSettings(mutedAccountIds = setOf("acct"))

        assertTrue(publisher.sync(listOf(usage(remaining = 15.0)), muted, now).isEmpty())

        val afterUnmuting = publisher.sync(
            listOf(usage(remaining = 15.0, fetchedAt = now + 60_000)),
            AppSettings(),
            now + 60_000,
        )

        assertTrue("got $afterUnmuting", afterUnmuting.isEmpty())

        // And a NEW edge after unmuting is still heard — silence is not permanent.
        val newDip = publisher.sync(
            listOf(usage(remaining = 5.0, fetchedAt = now + 120_000)),
            AppSettings(),
            now + 120_000,
        )
        assertEquals(listOf("Account acct · 5h limit: less than 10% remaining"), newDip)
    }

    @Test
    fun `a muted account still advances its state, so un-muting says nothing about the past`() {
        val muted = AppSettings(mutedAccountIds = setOf("acct"))
        val low = usage(remaining = 5.0)

        val whileMuted = NotificationEvaluator.evaluate(
            accounts = listOf(low),
            settings = muted,
            states = emptyMap(),
            nowMs = now,
        )
        assertTrue(whileMuted.events.all { it.line.isBlank() })

        // Un-silenced, and handed the SAME snapshot again. The state carried forward says it
        // has already been processed, so there is nothing new to announce.
        val afterUnmuting = NotificationEvaluator.evaluate(
            accounts = listOf(low),
            settings = AppSettings(),
            states = whileMuted.states.associateBy { it.accountId },
            nowMs = now,
        )

        // Nothing at all, not "nothing matching a substring". The first version of this
        // assertion looked for "low" in the key; the keys spell the tier "below-20", so it was
        // vacuous and passed against the naive implementation that skips a muted account
        // outright and freezes its state.
        assertTrue(
            "un-silencing must not replay the episode that ran while it was quiet, but got " +
                afterUnmuting.events.map { it.key },
            afterUnmuting.events.isEmpty(),
        )
    }

    // endregion
}
