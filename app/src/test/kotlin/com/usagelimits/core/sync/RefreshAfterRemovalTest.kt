package com.usagelimits.core.sync

import com.usagelimits.core.auth.CredentialStore
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.database.AccountDao
import com.usagelimits.core.database.AccountEntity
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.database.UsageSnapshotDao
import com.usagelimits.core.database.UsageSnapshotEntity
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.HttpClient
import com.usagelimits.providers.ProviderRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A token refresh that lands after the account was removed must not write the token back.
 *
 * `validCredentials` reloads inside its mutex as `load(reference) ?: current`, so an entry the
 * user just deleted does not stop the refresh — and the unconditional `save` that followed
 * put a live, freshly rotated refresh token back into the store under a reference no account
 * row names any more. Nothing reads it and nothing collects it: `references()` has no callers,
 * and a later re-login mints a new reference, so the orphan outlived the account for good.
 */
class RefreshAfterRemovalTest {

    private val now = 1_757_000_000_000L

    /** Returns the same token payload for any request; no socket is opened. */
    private fun tokenEndpointClient() = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"access_token":"rotated-access","refresh_token":"rotated-refresh","expires_in":3600}"""
                        .toResponseBody(HttpClient.JSON_MEDIA_TYPE),
                )
                .build()
        }
        .build()

    /**
     * Hands back the stored credential for the first [loadsBeforeRemoval] reads and nothing
     * afterwards — the shape of `removeAccount` landing while the refresh is in flight.
     */
    private class ScriptedStore(
        private val stored: OAuthCredentials,
        private val loadsBeforeRemoval: Int,
    ) : CredentialStore {
        var loads = 0
        val saves = mutableListOf<String>()

        override suspend fun load(reference: String): OAuthCredentials? {
            loads++
            return if (loads > loadsBeforeRemoval) null else stored
        }

        override suspend fun save(reference: String, credentials: OAuthCredentials) {
            saves += reference
        }

        override suspend fun delete(reference: String) = Unit
        override suspend fun references(): Set<String> = emptySet()
    }

    private class EmptyAccountDao : AccountDao {
        override fun observeAll(): Flow<List<AccountEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<AccountEntity> = emptyList()
        override suspend fun getById(localId: String): AccountEntity? = null
        override suspend fun getByExternalId(provider: String, externalAccountId: String) = null
        override suspend fun insert(account: AccountEntity) = Unit
        override suspend fun upsert(account: AccountEntity) = Unit
        override suspend fun delete(account: AccountEntity) = Unit
        override suspend fun deleteById(localId: String) = Unit
        override suspend fun markSynced(localId: String, timestamp: Long) = Unit
    }

    private class EmptySnapshotDao : UsageSnapshotDao {
        override fun observeAll(): Flow<List<UsageSnapshotEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<UsageSnapshotEntity> = emptyList()
        override suspend fun getForAccount(accountId: String): UsageSnapshotEntity? = null
        override fun observeForAccount(accountId: String): Flow<UsageSnapshotEntity?> = flowOf(null)
        override suspend fun upsert(snapshot: UsageSnapshotEntity) = Unit
        override suspend fun deleteForAccount(accountId: String) = Unit
    }

    private val account = ProviderAccount(
        localId = "U1",
        provider = ProviderId.CODEX,
        externalAccountId = "ext-U1",
        email = null,
        displayName = "Codex",
        plan = "Plus",
        credentialReference = "codex_U1",
        createdAt = now,
        lastSuccessfulSync = null,
    )

    private val expiring = OAuthCredentials(
        accessToken = "stale-access",
        refreshToken = "stale-refresh",
        idToken = null,
        // Already past the refresh lead, so validCredentials takes the refresh path.
        expiresAt = now - 1_000,
    )

    private fun engine(store: CredentialStore) = SyncEngine(
        repository = UsageRepository(EmptyAccountDao(), EmptySnapshotDao()),
        credentialStore = store,
        registry = ProviderRegistry(HttpClient(tokenEndpointClient())),
        nowMs = { now },
    )

    @Test
    fun `a refresh that finishes after the account is removed is not written back`() = runBlocking {
        // Load 1 (pre-lock) and load 2 (in-lock) still see the entry; by load 3 — the
        // existence check after the network round trip — the user has removed the account.
        val store = ScriptedStore(expiring, loadsBeforeRemoval = 2)

        val refreshed = engine(store).validCredentials(account)

        assertEquals("rotated-access", refreshed.accessToken)
        assertTrue(
            "a removed account's credential must not be resurrected",
            store.saves.isEmpty(),
        )
        assertEquals(3, store.loads)
    }

    @Test
    fun `a refresh for a live account is still persisted`() = runBlocking {
        val store = ScriptedStore(expiring, loadsBeforeRemoval = Int.MAX_VALUE)

        val refreshed = engine(store).validCredentials(account)

        assertEquals("rotated-access", refreshed.accessToken)
        assertEquals(listOf("codex_U1"), store.saves)
    }
}
