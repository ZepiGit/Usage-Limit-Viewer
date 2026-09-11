package com.usagelimits.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

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
        while (attempt <= retries) {
            try {
                return executeOnce(url, method, headers, body, badRequestMeansExpired, oneTimeGrant)
            } catch (e: ProviderException.RateLimited) {
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
                // Delivery is unknown here: the request reached something, and whether the
                // grant was consumed before the error is not observable from this side.
                if (oneTimeGrant || attempt == retries) throw e
                delay(backoffMs(attempt))
            } catch (e: ProviderException.Offline) {
                if (oneTimeGrant || attempt == retries) throw e
                delay(backoffMs(attempt))
            }
            attempt++
        }
        // Unreachable: every catch above throws on the final attempt, so the loop cannot
        // exit normally. Kept because the compiler needs the function to end in a value or a
        // throw; the variable that used to be carried here was only ever written.
        throw ProviderException.Unexpected("request failed")
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

        // OkHttp recovers from connection trouble on its own, below the loop above, and that
        // recovery has two halves which must be told apart for a grant the server accepts
        // once. Route fallback happens BEFORE any request byte leaves the device: the host
        // resolved to several addresses and the first would not connect, or a pooled socket
        // refused to open a stream. Switching it off along with the dangerous half — as the
        // first version of this did — turned every token exchange on a phone whose first,
        // usually IPv6, route is dead into "No network connection": the exchange that used to
        // move on to the next address now died on the first. The dangerous half is the resend
        // AFTER the request was written and its reply lost; delivery is unknown then, and a
        // second presentation of a spent grant can cost the account. OkHttp draws exactly that
        // line for a body that declares itself one-shot: route failures still fall through to
        // the next route, and a failure after the send is final.
        builder.method(method, if (oneTimeGrant && body != null) OneShotBody(body) else body)

        // The grant also gets a connection of its own rather than one from the pool. A pooled
        // socket the far end has silently dropped — a NAT mapping expiring while the user was
        // in the browser — fails only after the request is written, which is the one case
        // that can no longer be retried. A fresh connection cannot be stale, and the pool it
        // comes from evicts it as soon as the reply is read. `newBuilder` keeps the dispatcher.
        val call = if (oneTimeGrant) {
            client.newBuilder()
                .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .build()
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
        } catch (e: IOException) {
            throw ProviderException.Offline(describeTransportFailure(url, e), e)
        }
    }

    /**
     * Says WHERE a request died and HOW, without any of the exception's own text: OkHttp's
     * messages carry the URL, and a token URL belongs on neither a screen nor a log.
     *
     * "No network connection" used to be the one answer for every transport failure, which
     * sent a user whose connection was fine off to check their Wi-Fi while the real cause —
     * a resolver that blocks the host, a handshake the edge refused, a reply that never came
     * — stayed invisible. The host is safe to name; nothing else from the wire is.
     */
    private fun describeTransportFailure(url: String, e: IOException): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "the provider"
        return when (e) {
            is UnknownHostException ->
                "Could not look up $host. Check the connection, or a DNS filter that may block it."
            is ConnectException, is NoRouteToHostException -> "Could not connect to $host."
            is SocketTimeoutException -> "$host did not answer in time."
            is SSLHandshakeException, is SSLPeerUnverifiedException ->
                "A secure connection to $host could not be established."
            else -> "The connection to $host was interrupted."
        }
    }

    /**
     * A body OkHttp may write at most once.
     *
     * Declaring a body one-shot is how OkHttp is told "do not resend this after it was written",
     * while leaving every recovery that happens before the write — trying the next address of a
     * multi-homed host, replacing a pooled connection that would not open a stream — in place.
     */
    private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        override fun isOneShot(): Boolean = true
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
