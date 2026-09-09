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
    fun `an unknown remaining percentage is not treated as recovery`() {
        val first = run(listOf(usage(remaining = 8.0)), emptyMap())
        val unknown = run(listOf(usage(remaining = null, fetchedAt = now + 1)), first.stateMap())

        // Absence of evidence must not end the episode and rearm both tiers.
        assertTrue(unknown.stateMap().getValue("acct").lowQuotaActive)
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
    fun `reset approaching is off unless asked for`() {
        val outcome = run(listOf(usage(remaining = 80.0, resetAt = now + 60_000L)), emptyMap())

        assertTrue(outcome.events.isEmpty())
    }

    // endregion

    // region expiring reset credits

    @Test
    fun `a credit lapsing inside the lead window notifies once`() {
        val credit = ResetCredit("c1", now - 1000, now + 3_600_000, "available")
        val outcome = run(listOf(usage(remaining = 80.0, credits = listOf(credit))), emptyMap())

        assertTrue(outcome.lines().any { it.contains("1 reset credit expires within 24 hours") })
    }

    @Test
    fun `several credits lapsing together produce one line but one claim each`() {
        val credits = listOf(
            ResetCredit("c1", now - 1000, now + 3_600_000, "available"),
            ResetCredit("c2", now - 1000, now + 7_200_000, "available"),
        )
        val outcome = run(listOf(usage(remaining = 80.0, credits = credits)), emptyMap())

        assertEquals(1, outcome.lines().count { it.contains("expire") })
        assertTrue(outcome.lines().any { it.contains("2 reset credits expire") })
        assertEquals(2, outcome.events.count { it.key.endsWith("credit-expiring") })
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
}
