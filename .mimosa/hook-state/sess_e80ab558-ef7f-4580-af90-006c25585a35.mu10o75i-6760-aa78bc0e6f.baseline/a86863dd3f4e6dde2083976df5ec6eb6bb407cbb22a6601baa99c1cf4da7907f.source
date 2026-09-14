package com.usagelimits.core.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retry-After is provider-controlled input, so the wait it asks for has to be bounded.
 *
 * Anthropic and OpenAI both send a Retry-After equal to the remaining rate-limit window, an
 * hour being routine. Honouring that verbatim made one throttled account hold the calling
 * coroutine for two hours across the default two retries, which in `SyncEngine.syncAll`'s
 * `awaitAll` stalls the entire pass: no widget refresh and no low-quota notification for the
 * healthy accounts either.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpClientRetryTest {

    private var calls = 0

    private fun clientAlwaysRateLimited(retryAfter: String?): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .apply { retryAfter?.let { header("Retry-After", it) } }
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()

    @Test
    fun `an hour-long Retry-After is clamped to the client's ceiling`() = runTest {
        val http = HttpClient(clientAlwaysRateLimited("3600"))

        val start = testScheduler.currentTime
        try {
            http.request("https://example.invalid/usage")
            error("expected a RateLimited exception")
        } catch (e: ProviderException.RateLimited) {
            // expected after the retries are exhausted
        }
        val slept = testScheduler.currentTime - start

        assertEquals("one initial attempt plus DEFAULT_RETRIES", 3, calls)
        assertTrue(
            "slept ${slept}ms; two honoured Retry-After waits must not exceed the ceiling",
            slept <= 2 * HttpClient.MAX_RETRY_AFTER_MS,
        )
        assertTrue("the client must still back off at all", slept > 0)
    }

    @Test
    fun `a negative Retry-After does not turn the retries into a hot loop`() = runTest {
        val http = HttpClient(clientAlwaysRateLimited("-120"))

        val start = testScheduler.currentTime
        try {
            http.request("https://example.invalid/usage")
            error("expected a RateLimited exception")
        } catch (e: ProviderException.RateLimited) {
            // expected
        }

        assertEquals(3, calls)
        // Clamped to zero rather than left negative, where delay() would be a no-op.
        assertEquals(0L, testScheduler.currentTime - start)
    }

    @Test
    fun `a Retry-After large enough to overflow the millisecond conversion stays positive`() =
        runTest {
            val http = HttpClient(clientAlwaysRateLimited("9223372036854775807"))

            val start = testScheduler.currentTime
            try {
                http.request("https://example.invalid/usage")
                error("expected a RateLimited exception")
            } catch (e: ProviderException.RateLimited) {
                // expected
            }
            val slept = testScheduler.currentTime - start

            assertEquals(3, calls)
            assertTrue("slept ${slept}ms", slept in 1..(2 * HttpClient.MAX_RETRY_AFTER_MS))
        }
}
