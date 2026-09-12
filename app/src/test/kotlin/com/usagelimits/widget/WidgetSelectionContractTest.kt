package com.usagelimits.widget

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.*
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.accounts.AccountFilter
import org.junit.Assert.*
import org.junit.Test

class WidgetSelectionContractTest {
    private val now = 1_800_000_000_000L
    private fun account(id: String, used: Double, reset: Long?, connection: ConnectionStatus = ConnectionStatus.CONNECTED,
        provider: ProviderId = ProviderId.CODEX): AccountUsage = AccountUsage(
        ProviderAccount(id, provider, id, "$id@example.com", id, "Plus", "test-$id", now, now),
        UsageSnapshot(id, now, SnapshotStatus.OK,
            listOf(UsageWindow("short", "5h", WindowCategory.FIVE_HOUR, used, 18_000, reset, exhausted = used >= 100)),
            connectionStatus = connection),
    )

    @Test fun `exhausted and low quota remain connected and are excluded from attention`() {
        val accounts = listOf(account("empty", 100.0, now + 100), account("low", 97.0, now + 200),
            account("revoked", 10.0, now + 300, ConnectionStatus.RECONNECT_REQUIRED))
        assertEquals(2, UsageUiState(accounts).healthyCountAt(now))
        assertEquals(listOf("revoked"), accounts.filter { AccountFilter(onlyProblems = true).matches(it) }.map { it.account.localId })
    }

    @Test fun `missing data never asserts a valid connection`() {
        val unknown = account("unknown", 0.0, null).copy(snapshot = null)
        assertEquals(0, UsageUiState(listOf(unknown)).healthyCountAt(now))
        assertFalse(AccountFilter(onlyProblems = true).matches(unknown))
    }

    @Test fun `closest resets sorts future timestamps independent of remaining quota`() {
        val accounts = listOf(account("empty-later", 100.0, now + 9000), account("full-soon", 0.0, now + 1000),
            account("past", 90.0, now - 1), account("unknown", 10.0, null))
        val result = WidgetDataBuilder.build(accounts, now, WidgetScope.CLOSEST_RESETS, null, null)
        assertEquals(listOf("full-soon", "empty-later", "past", "unknown"), result.accounts.map { it.accountId })
        assertEquals(now + 1000, result.nextResetAt)
        assertEquals(WidgetScope.CLOSEST_RESETS, WidgetScope.fromName("MOST_CRITICAL"))
    }

    @Test fun `all accounts matches overview and custom is an exact ordered subset`() {
        val accounts = listOf(account("c", 0.0, null), account("a", 100.0, null), account("b", 20.0, null))
        assertEquals(listOf("c", "a", "b"), UsageUiState(accounts).orderedAccounts(now).map { it.account.localId })
        assertEquals(listOf("c", "a", "b"), WidgetDataBuilder.build(accounts, now, WidgetScope.ALL_ACCOUNTS, null, null).accounts.map { it.accountId })
        assertEquals(listOf("b", "c"), WidgetDataBuilder.build(accounts, now, WidgetScope.CUSTOM, null, null,
            customAccountIds = listOf("b", "deleted", "b", "c")).accounts.map { it.accountId })
        assertTrue(WidgetDataBuilder.build(accounts, now, WidgetScope.CUSTOM, null, null).accounts.isEmpty())
    }

    @Test fun `one account remains exact even when a different account is exhausted`() {
        val accounts = listOf(account("empty", 100.0, now + 100), account("selected", 30.0, now + 200))
        val snapshot = WidgetDataBuilder.build(accounts, now, WidgetScope.ACCOUNT, "selected", null)
        assertEquals(1, snapshot.accountCount)
        assertEquals("selected", snapshot.accounts.single().accountId)
        assertEquals(70.0, snapshot.headlineShort!!.remainingPercent!!, 0.01)
        assertTrue(WidgetDataBuilder.build(accounts, now, WidgetScope.ACCOUNT, "deleted", null).accounts.isEmpty())
    }

    @Test fun `ring layouts change columns and preserve the requested compact capacities`() {
        assertEquals(1, WidgetLayout.accountColumns(250f, 140f))
        assertEquals(2, WidgetLayout.accountColumns(250f, 280f))
        assertEquals(2, WidgetLayout.miniCapacity(64f, 64f))
        assertEquals(4, WidgetLayout.miniCapacity(64f, 128f))
        assertEquals(8, WidgetLayout.miniCapacity(128f, 128f))
    }

    @Test fun `legacy auth errors are recognized narrowly`() {
        assertEquals(ConnectionStatus.RECONNECT_REQUIRED, ConnectionStatus.fromStored(null, SnapshotStatus.FAILED,
            "Sign-in expired — reconnect this account"))
        assertEquals(ConnectionStatus.UNKNOWN, ConnectionStatus.fromStored(null, SnapshotStatus.FAILED, "Request deadline expired"))
        assertEquals(ConnectionStatus.CONNECTED, ConnectionStatus.fromStored("CONNECTED", SnapshotStatus.FAILED, "No network"))
    }
}

