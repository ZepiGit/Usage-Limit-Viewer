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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Signing out must beat a refresh that is already in flight.
 *
 * `validCredentials` checks that the reference still exists and then writes the rotated pair
 * back, both inside its per-reference lock. Sign-out did not take that lock, so it could land
 * between those two steps — and the write put the credential the user had just removed
 * straight back. The account was gone from the list while a live token stayed in the keystore
 * with no row naming it, so nothing would ever read or collect it.
 *
 * The existing RefreshAfterRemovalTest cannot see this: it makes the credential absent at the
 * existence check. The defect is in the window AFTER that check passes.
 */
class DeleteDuringRefreshTest {

    private val now = 1_757_000_000_000L

    /**
     * Blocks the token response until released, and announces that it has started.
     *
     * The announcement is what makes this test deterministic rather than a coin flip. The
     * interceptor only runs once the refresh already holds the credential lock, so waiting for
     * it before signing out guarantees the interleaving the test is about. Without it the two
     * coroutines raced: sign-out sometimes took the lock first, deleted, and the refresh then
     * found nothing to save — a pass that proved nothing, and one I got on a full-suite run
     * after the targeted run had gone green.
     */
    private fun heldTokenClient(
        entered: CompletableDeferred<Unit>,
        released: CompletableDeferred<Unit>,
    ) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            entered.complete(Unit)
            runBlocking { released.await() }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"access_token":"rotated","refresh_token":"rotated-refresh","expires_in":3600}"""
                        .toResponseBody(HttpClient.JSON_MEDIA_TYPE),
                )
                .build()
        }
        .build()

    private class Store(initial: OAuthCredentials?) : CredentialStore {
        var stored: OAuthCredentials? = initial
        /** The order the two writers actually reached the store. */
        val order = mutableListOf<String>()
        override suspend fun load(reference: String) = stored
        override suspend fun save(reference: String, credentials: OAuthCredentials) {
            order += "save"
            stored = credentials
        }
        override suspend fun delete(reference: String) {
            order += "delete"
            stored = null
        }
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
        accessToken = "stale",
        refreshToken = "stale-refresh",
        idToken = null,
        expiresAt = now - 1_000,
    )

    @Test
    fun `a sign-out during a refresh is not undone by the refresh's write`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val store = Store(expiring)
        val engine = SyncEngine(
            repository = UsageRepository(EmptyAccountDao(), EmptySnapshotDao()),
            credentialStore = store,
            registry = ProviderRegistry(HttpClient(heldTokenClient(entered, released))),
            nowMs = { now },
        )

        // The refresh gets past its existence check and blocks on the token response.
        val refresh = async(Dispatchers.IO) { engine.validCredentials(account) }

        // Only once the refresh is demonstrably inside the lock and parked on the network.
        entered.await()

        // Sign-out, taking the same lock. It must therefore wait for the refresh to finish
        // rather than interleave with it — which is exactly the guarantee being tested.
        val signOut = async(Dispatchers.IO) {
            engine.withCredentialLock(account.credentialReference) {
                store.delete(account.credentialReference)
            }
        }

        withContext(Dispatchers.IO) { released.complete(Unit) }
        refresh.await()
        signOut.await()

        assertNull(
            "the credential the user removed must not survive the refresh's write",
            store.stored,
        )
        // The mechanism, not just the outcome. Sharing the lock means the delete cannot
        // interleave with the refresh at all: it waits for the save and then removes what the
        // save wrote. Without the shared lock the delete runs first, while the refresh is
        // still blocked on the token response, and the save afterwards resurrects it.
        assertEquals(listOf("save", "delete"), store.order)
    }
}
