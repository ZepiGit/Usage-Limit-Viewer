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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A usage 401 must renew the token once and retry.
 *
 * `needsRefresh` returns false when `expiresAt` is null, which is not a corner case — it is
 * every account whose provider does not state an expiry. For those the proactive branch can
 * never fire, so before this the first 401 after the access token died was recorded as
 * "Sign-in expired — reconnect this account" and every later sync did the same, for ever,
 * while a perfectly good refresh token sat in the store unused. An expiry stored too far in
 * the future produced the same silence until that date arrived.
 *
 * iOS already renewed on a rejection and already compared the rejected token; these tests
 * pin the Android half to the same rule.
 */
class ReactiveRefreshTest {

    private val now = 1_757_000_000_000L

    private val usageBody = """
        {"rate_limits":{"primary":{"used_percent":40.0,"window_minutes":300,
        "resets_at":1757003600}}}
    """.trimIndent()

    /** Counts exchanges, and answers the usage endpoint 401 until a refresh has happened. */
    private class Endpoints(private val rejectUntilRefreshed: Boolean) {
        val exchanges = AtomicInteger(0)
        val usageCalls = AtomicInteger(0)
        var refreshed = false

        fun client(usageBody: String) = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val builder = Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)

                when {
                    url.contains("oauth/token") -> {
                        exchanges.incrementAndGet()
                        refreshed = true
                        builder.code(200).message("OK").body(
                            """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600}"""
                                .toResponseBody(HttpClient.JSON_MEDIA_TYPE),
                        ).build()
                    }
                    else -> {
                        // Only the usage endpoint is counted. A Codex fetch also reads the
                        // reset-credit endpoint, so counting every non-token request would
                        // measure how many calls a fetch makes rather than how many times
                        // this one was retried.
                        if (url.contains("wham/usage")) usageCalls.incrementAndGet()
                        val unauthorised = rejectUntilRefreshed && !refreshed
                        if (unauthorised) {
                            builder.code(401).message("Unauthorized")
                                .body("""{"error":"invalid_token"}""".toResponseBody(HttpClient.JSON_MEDIA_TYPE))
                                .build()
                        } else {
                            builder.code(200).message("OK")
                                .body(usageBody.toResponseBody(HttpClient.JSON_MEDIA_TYPE))
                                .build()
                        }
                    }
                }
            }
            .build()
    }

    private class SingleAccountStore(initial: OAuthCredentials) : CredentialStore {
        var stored: OAuthCredentials? = initial
        override suspend fun load(reference: String) = stored
        override suspend fun save(reference: String, credentials: OAuthCredentials) {
            stored = credentials
        }
        override suspend fun delete(reference: String) { stored = null }
        override suspend fun references(): Set<String> = setOf("codex_U1")
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

    private class RecordingSnapshotDao : UsageSnapshotDao {
        val written = mutableListOf<UsageSnapshotEntity>()
        override fun observeAll(): Flow<List<UsageSnapshotEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<UsageSnapshotEntity> = emptyList()
        override suspend fun getForAccount(accountId: String): UsageSnapshotEntity? = null
        override fun observeForAccount(accountId: String): Flow<UsageSnapshotEntity?> = flowOf(null)
        override suspend fun upsert(snapshot: UsageSnapshotEntity) { written += snapshot }
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

    /** The account this is all about: a live refresh token and NO known expiry. */
    private fun noExpiry(access: String) = OAuthCredentials(
        accessToken = access,
        refreshToken = "refresh-1",
        idToken = null,
        expiresAt = null,
    )

    private fun engine(store: CredentialStore, endpoints: Endpoints) = SyncEngine(
        repository = UsageRepository(EmptyAccountDao(), RecordingSnapshotDao()),
        credentialStore = store,
        registry = ProviderRegistry(HttpClient(endpoints.client(usageBody))),
        nowMs = { now },
    )

    @Test
    fun `a usage 401 on an account with no expiry renews once and succeeds`() = runBlocking {
        val store = SingleAccountStore(noExpiry("access-1"))
        val endpoints = Endpoints(rejectUntilRefreshed = true)

        val outcome = engine(store, endpoints).syncAccount(account)

        assertTrue("the retry after renewal must succeed", outcome.success)
        assertEquals("exactly one rotation may be spent", 1, endpoints.exchanges.get())
        assertEquals("one rejected usage call and one retry", 2, endpoints.usageCalls.get())
        assertEquals("access-2", store.stored?.accessToken)
        assertEquals("refresh-2", store.stored?.refreshToken)
    }

    @Test
    fun `a second 401 after renewal fails once rather than looping`() = runBlocking {
        // A genuinely revoked credential. The retry is once only: a second rejection is the
        // account's failure, not a reason to spend another rotation.
        val store = SingleAccountStore(noExpiry("access-1"))
        val endpoints = object {
            val inner = Endpoints(rejectUntilRefreshed = false)
        }.inner
        val alwaysRejecting = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                if (url.contains("oauth/token")) {
                    endpoints.exchanges.incrementAndGet()
                    builder.code(200).message("OK").body(
                        """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600}"""
                            .toResponseBody(HttpClient.JSON_MEDIA_TYPE),
                    ).build()
                } else {
                    if (url.contains("wham/usage")) endpoints.usageCalls.incrementAndGet()
                    builder.code(401).message("Unauthorized")
                        .body("""{"error":"invalid_token"}""".toResponseBody(HttpClient.JSON_MEDIA_TYPE))
                        .build()
                }
            }
            .build()

        val outcome = SyncEngine(
            repository = UsageRepository(EmptyAccountDao(), RecordingSnapshotDao()),
            credentialStore = store,
            registry = ProviderRegistry(HttpClient(alwaysRejecting)),
            nowMs = { now },
        ).syncAccount(account)

        assertFalse(outcome.success)
        assertEquals("no second rotation", 1, endpoints.exchanges.get())
        assertEquals("no retry loop: one rejection, one retry", 2, endpoints.usageCalls.get())
    }

    @Test
    fun `a token another pass already saved is tried before spending a rotation`() = runBlocking {
        // The reason the rejected token is passed by value rather than as a force flag. The
        // store holds access-2 while the caller was refused access-1; nothing has rejected
        // access-2, so it is offered unexchanged. Presenting an already-spent refresh token is
        // how a provider revokes the whole grant, so a needless rotation is not free.
        val store = SingleAccountStore(noExpiry("access-2"))
        val endpoints = Endpoints(rejectUntilRefreshed = false)

        val credentials = engine(store, endpoints)
            .validCredentials(account, rejectedAccessToken = "access-1")

        assertEquals("access-2", credentials.accessToken)
        assertEquals("no rotation was needed", 0, endpoints.exchanges.get())
    }
}
