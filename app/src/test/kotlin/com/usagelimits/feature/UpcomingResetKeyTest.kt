package com.usagelimits.feature

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Resets list keys its rows by identity, not by display text.
 *
 * Window labels are provider strings and repeat freely — Antigravity names one bucket
 * "Weekly" per quota group, so a single account routinely reports two of them. When two such
 * windows also share a reset instant (a plan-anchored weekly boundary shared across model
 * families), the old `accountId-label-resetAt` key collided and Compose's
 * SaveableStateHolder threw "Key ... was used multiple times", crashing the whole tab on open
 * and on every reopen until the payload changed. `UsageWindow.id` is unique per account, so
 * carrying it through is what makes the key safe.
 */
class UpcomingResetKeyTest {

    private val now = 1_757_000_000_000L

    private fun window(id: String, label: String, resetAt: Long) = UsageWindow(
        id = id,
        label = label,
        category = WindowCategory.WEEKLY,
        usedPercent = 55.0,
        periodSeconds = 604_800,
        resetAt = resetAt,
        exhausted = false,
    )

    private fun state(vararg windows: UsageWindow) = UsageUiState(
        accounts = listOf(
            AccountUsage(
                account = ProviderAccount(
                    localId = "acct-1",
                    provider = ProviderId.ANTIGRAVITY,
                    externalAccountId = "ext-1",
                    email = null,
                    displayName = "Antigravity",
                    plan = "Ultra",
                    credentialReference = "antigravity_acct-1",
                    createdAt = now,
                    lastSuccessfulSync = now,
                ),
                snapshot = UsageSnapshot(
                    accountId = "acct-1",
                    fetchedAt = now,
                    status = SnapshotStatus.OK,
                    windows = windows.toList(),
                ),
            ),
        ),
    )

    @Test
    fun `two windows sharing a label and a reset instant still get distinct list keys`() {
        val resetAt = now + 3_600_000
        val resets = state(
            window("gemini-pro:weekly", "Weekly", resetAt),
            window("gemini-ultra:weekly", "Weekly", resetAt),
        ).upcomingResets(now)

        assertEquals(2, resets.size)

        // Exactly the key ResetsScreen builds.
        val keys = resets.map { "${it.accountId}-${it.windowId}" }
        assertEquals("duplicate LazyColumn keys crash the Resets tab", 2, keys.toSet().size)
    }

    @Test
    fun `the window id is carried through, not the label`() {
        val resets = state(window("gemini-pro:weekly", "Weekly", now + 3_600_000))
            .upcomingResets(now)

        assertEquals("gemini-pro:weekly", resets.single().windowId)
        assertEquals("Weekly", resets.single().windowLabel)
    }

    @Test
    fun `resets already in the past are still dropped`() {
        val resets = state(
            window("past", "Weekly", now - 1),
            window("future", "Weekly", now + 1),
        ).upcomingResets(now)

        assertEquals(listOf("future"), resets.map { it.windowId })
    }
}
