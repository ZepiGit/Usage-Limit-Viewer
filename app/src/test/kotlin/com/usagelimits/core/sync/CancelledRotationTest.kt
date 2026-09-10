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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A rotation that has already happened at the provider must reach the store, cancelled or not.
 *
 * A rotating provider invalidates the old pair the moment it answers. If the worker is
 * cancelled while that response is in flight — a lost constraint, doze, the system reclaiming
 * it — the result can be dropped on the way out of the IO dispatcher, or the coroutine can
 * stop between receiving it and writing it. Either way the provider has moved on and the store
 * still holds a pair that is now dead. The account is signed out with nothing recording why.
 *
 * Rethrowing cancellation correctly, which this engine already did, prevents a bogus failure
 * snapshot. It does not save the token.
 */
class CancelledRotationTest {

    private val now = 1_757_000_000_000L

    private class Store(initial: OAuthCredentials) : CredentialStore {
        var stored: OAuthCredentials? = initial
        override suspend fun load(reference: String) = stored
        override suspend fun save(reference: String, credentials: OAuthCredentials) {
            stored = credentials
        }
        override suspend fun delete(reference: String) { stored = null }
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
        accessToken = "old-access",
        refreshToken = "old-refresh",
        idToken = null,
        expiresAt = now - 1_000,
    )

    @Test
    fun `a rotated pair is persisted even when the caller is cancelled mid-exchange`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val store = Store(expiring)

        // Announces that the provider has been reached, then holds the response until the
        // test has cancelled the caller. That is the exact window the defect lives in.
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                entered.complete(Unit)
                runBlocking { released.await() }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""
                            .toResponseBody(HttpClient.JSON_MEDIA_TYPE),
                    )
                    .build()
            }
            .build()

        val engine = SyncEngine(
            repository = UsageRepository(EmptyAccountDao(), EmptySnapshotDao()),
            credentialStore = store,
            registry = ProviderRegistry(HttpClient(client)),
            nowMs = { now },
        )

        val refresh = async(Dispatchers.IO) { engine.validCredentials(account) }
        entered.await()

        // The worker goes away while the provider is answering. The old refresh token is
        // already spent at this point, whatever this process does next.
        refresh.cancel()
        released.complete(Unit)
        runCatching { refresh.await() }

        assertNotNull("the rotated pair must not be lost to cancellation", store.stored)
        assertEquals("new-access", store.stored?.accessToken)
        assertEquals("new-refresh", store.stored?.refreshToken)
    }
}
