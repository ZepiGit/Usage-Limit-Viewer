package com.usagelimits.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A grant the server can only accept once must not be resent when delivery is unknown.
 *
 * A rotating refresh token is consumed by ARRIVING, not by the reply getting back. If the
 * server rotates R1 to R2 and the response is then lost, resending the same body presents R1
 * a second time — and a provider that treats reuse as theft revokes the whole token family.
 * The retry meant to paper over a blip signs the account out instead: it trades one refresh
 * cycle for the account.
 */
class OneTimeGrantRetryTest {

    private val json = """{"access_token":"a","refresh_token":"r","expires_in":3600}"""

    private fun countingClient(calls: AtomicInteger, fail: () -> Nothing) =
        OkHttpClient.Builder()
            .addInterceptor { _ ->
                calls.incrementAndGet()
                fail()
            }
            .build()

    private fun okAfter(calls: AtomicInteger, failures: Int) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val n = calls.incrementAndGet()
            if (n <= failures) throw IOException("connection reset")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody(HttpClient.JSON_MEDIA_TYPE))
                .build()
        }
        .build()

    @Test
    fun `a lost connection does not resend a one-time grant`() = runBlocking {
        val calls = AtomicInteger(0)
        val client = HttpClient(countingClient(calls) { throw IOException("connection reset") })

        runCatching {
            client.request(
                url = "https://auth.example.com/oauth/token",
                method = "POST",
                // A real body: OkHttp rejects a POST without one before the call is ever made,
                // which would leave the interceptor uncalled and the assertion meaningless.
                body = "grant_type=refresh_token&refresh_token=R1"
                    .toRequestBody(HttpClient.FORM_MEDIA_TYPE),
                oneTimeGrant = true,
            )
        }

        assertEquals("the grant may reach the server exactly once", 1, calls.get())
    }

    @Test
    fun `a 5xx does not resend a one-time grant`() = runBlocking {
        // The dangerous case, not an obviously fatal one: something in front of the token
        // endpoint answers 502 AFTER the endpoint rotated the grant. Whether it was consumed
        // is not observable from here, so the only safe move is not to send it again.
        val calls = AtomicInteger(0)
        val client = HttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    calls.incrementAndGet()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(502)
                        .message("Bad Gateway")
                        .body("".toResponseBody(HttpClient.JSON_MEDIA_TYPE))
                        .build()
                }
                .build(),
        )

        runCatching {
            client.request(
                url = "https://auth.example.com/oauth/token",
                method = "POST",
                // A real body: OkHttp rejects a POST without one before the call is ever made,
                // which would leave the interceptor uncalled and the assertion meaningless.
                body = "grant_type=refresh_token&refresh_token=R1"
                    .toRequestBody(HttpClient.FORM_MEDIA_TYPE),
                oneTimeGrant = true,
            )
        }

        assertEquals("a 502 may have followed a successful rotation", 1, calls.get())
    }

    @Test
    fun `an ordinary read still retries a lost connection`() = runBlocking {
        // The policy narrows for grants only. A usage GET spends nothing, and dropping its
        // retries would turn every blip into a stale card.
        val calls = AtomicInteger(0)
        val client = HttpClient(okAfter(calls, failures = 1))

        val response = client.request(url = "https://api.example.com/usage")

        assertTrue(response.body.contains("access_token"))
        assertEquals("one failure then one success", 2, calls.get())
    }
    /**
     * The authorization-code exchange is the same kind of request as a refresh: the code is
     * spent by ARRIVING. All three exchanges omitted the flag, so a 502 from something in front
     * of the token endpoint re-presented a code the endpoint had already consumed.
     */
    @Test
    fun `an authorization-code exchange is presented once, whatever answers`() = runBlocking {
        for ((name, exchange) in listOf<Pair<String, suspend (HttpClient) -> Unit>>(
            "codex" to { c -> com.usagelimits.providers.codex.CodexProvider(c).exchangeCode("C1", "v"); Unit },
            "claude" to { c -> com.usagelimits.providers.claude.ClaudeProvider(c).exchangeCode("C1", "v", "s"); Unit },
            "antigravity" to { c -> com.usagelimits.providers.antigravity.AntigravityProvider(c).exchangeCode("C1", "v"); Unit },
        )) {
            val calls = AtomicInteger(0)
            val client = HttpClient(
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        calls.incrementAndGet()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(502)
                            .message("Bad Gateway")
                            .body("".toResponseBody(HttpClient.JSON_MEDIA_TYPE))
                            .build()
                    }
                    .build(),
            )

            runCatching { exchange(client) }

            assertEquals("$name re-presented a consumed code", 1, calls.get())
        }
    }

}
