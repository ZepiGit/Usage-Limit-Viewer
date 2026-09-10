package com.usagelimits.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** A response body plus the bits of the envelope callers actually need. */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>,
)

/**
 * The app's only outbound HTTP path.
 *
 * Enforces HTTPS for everything except the loopback OAuth callbacks (which are, by
 * definition, local and cannot be TLS), maps transport and status failures onto
 * [ProviderException], and applies bounded retries with exponential backoff.
 *
 * No logging interceptor is installed in release builds and request bodies are never logged,
 * because every request here carries a bearer token.
 */
class HttpClient(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        retries: Int = DEFAULT_RETRIES,
        /**
         * Whether a 400 means "this credential is dead" rather than "this request was wrong".
         *
         * True only for token endpoints. RFC 6749 §5.2 gives `invalid_grant` — a spent, revoked
         * or malformed refresh token — the status 400, and providers differ in practice: probed
         * with a deliberately invalid refresh token, OpenAI answers 401 while Google's token
         * endpoint answers 400. Both codes occur, so the refresh path has to accept both.
         *
         * It stays false everywhere else, because on a quota endpoint a 400 really is a bad
         * request and reading it as an expired sign-in would tell the user to reconnect a
         * perfectly good account.
         */
        badRequestMeansExpired: Boolean = false,
        /**
         * Whether this request spends something the server can only accept once.
         *
         * A rotating refresh grant and an authorization code are consumed by the act of
         * ARRIVING, not by the response getting back. If the server rotates R1 to R2 and the
         * reply is then lost — a dropped connection, a 502 from something in front of it —
         * resending the same body presents R1 a second time. A provider that treats reuse as
         * theft can revoke the entire token family, so the retry that was meant to paper over
         * a blip signs the account out instead. The failure it prevents costs one refresh
         * cycle; the failure it causes costs the account.
         *
         * So a one-time grant is retried only where the server has SAID it did not act: 429 is
         * an explicit refusal, and nothing was spent. Transport failures and 5xx are exactly
         * the cases where delivery is unknown, and those are not retried. Plain reads keep the
         * full policy — a usage GET spends nothing and is safe to repeat.
         */
        oneTimeGrant: Boolean = false,
    ): HttpResponse {
        requireSecure(url)
        var attempt = 0
        var lastError: ProviderException? = null
        while (attempt <= retries) {
            try {
                return executeOnce(url, method, headers, body, badRequestMeansExpired, oneTimeGrant)
            } catch (e: ProviderException.RateLimited) {
                lastError = e
                // Honour Retry-After when the provider sent one, otherwise back off — but
                // clamped. Providers routinely set Retry-After to the whole remaining
                // rate-limit window (an hour is common), and this delay holds the calling
                // coroutine: one throttled account would otherwise stall the entire sync
                // pass, suppressing the widgets and notifications of every healthy account.
                // A longer wait is the next scheduled pass's job, not this call's.
                val wait = (e.retryAfterMs ?: backoffMs(attempt)).coerceIn(0L, MAX_RETRY_AFTER_MS)
                if (attempt == retries) throw e
                delay(wait)
            } catch (e: ProviderException.ServerError) {
                lastError = e
                // Delivery is unknown here: the request reached something, and whether the
                // grant was consumed before the error is not observable from this side.
                if (oneTimeGrant || attempt == retries) throw e
                delay(backoffMs(attempt))
            } catch (e: ProviderException.Offline) {
                lastError = e
                if (oneTimeGrant || attempt == retries) throw e
                delay(backoffMs(attempt))
            }
            attempt++
        }
        throw lastError ?: ProviderException.Unexpected("request failed")
    }

    private suspend fun executeOnce(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: RequestBody?,
        badRequestMeansExpired: Boolean = false,
        oneTimeGrant: Boolean = false,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, body)

        // OkHttp retries connection failures itself, below the loop above and invisibly to it,
        // so leaving it on would resend a spent grant no matter what that loop decided. The
        // derived client shares this one's connection pool and dispatcher — `newBuilder` keeps
        // them — so this costs a wrapper object and no sockets.
        val call = if (oneTimeGrant) {
            client.newBuilder().retryOnConnectionFailure(false).build()
        } else {
            client
        }

        try {
            call.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val headerMap = response.headers.names().associateWith { response.headers[it].orEmpty() }
                when {
                    response.isSuccessful -> HttpResponse(response.code, text, headerMap)
                    response.code == 400 && badRequestMeansExpired ->
                        throw ProviderException.Unauthorized()
                    response.code == 401 -> throw ProviderException.Unauthorized()
                    response.code == 403 -> throw ProviderException.Forbidden()
                    response.code == 429 -> throw ProviderException.RateLimited(
                        // Bound the parse before the multiply: a negative header would turn
                        // delay() into a no-op hot retry, and a huge one overflows Long and
                        // can come out negative too.
                        retryAfterMs = response.header("Retry-After")
                            ?.toLongOrNull()
                            ?.coerceIn(0L, MAX_RETRY_AFTER_SECONDS)
                            ?.times(1000),
                    )
                    response.code >= 500 -> throw ProviderException.ServerError(
                        response.code,
                        "Provider returned ${response.code}",
                    )
                    else -> throw ProviderException.Unexpected("HTTP ${response.code}")
                }
            }
        } catch (e: UnknownHostException) {
            throw ProviderException.Offline(cause = e)
        } catch (e: IOException) {
            throw ProviderException.Offline(cause = e)
        }
    }

    /**
     * Rejects plaintext HTTP for anything that is not a loopback OAuth callback. Tokens must
     * never traverse an unencrypted connection.
     */
    private fun requireSecure(url: String) {
        if (url.startsWith("https://", ignoreCase = true)) return

        // A prefix match would have accepted http://localhost.attacker.example/ and
        // http://127.0.0.1.evil.com/ — both are ordinary remote hosts. Compare the parsed
        // host instead.
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (!isLoopback) throw ProviderException.Unexpected("refusing plaintext request")
    }

    private fun backoffMs(attempt: Int): Long =
        (BASE_BACKOFF_MS * (1L shl attempt)).coerceAtMost(MAX_BACKOFF_MS)

    companion object {
        const val DEFAULT_RETRIES = 2

        /** Longest this client will sleep between attempts, Retry-After included. */
        const val MAX_RETRY_AFTER_MS = 30_000L

        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 8_000L
        private const val MAX_RETRY_AFTER_SECONDS = 86_400L

        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

        fun jsonBody(payload: String): RequestBody = payload.toRequestBody(JSON_MEDIA_TYPE)

        fun formBody(fields: Map<String, String>): RequestBody =
            fields.entries.joinToString("&") { (k, v) ->
                "${urlEncode(k)}=${urlEncode(v)}"
            }.toRequestBody(FORM_MEDIA_TYPE)

        private fun urlEncode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
