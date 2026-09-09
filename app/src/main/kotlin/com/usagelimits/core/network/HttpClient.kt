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
    ): HttpResponse {
        requireSecure(url)
        var attempt = 0
        var lastError: ProviderException? = null
        while (attempt <= retries) {
            try {
                return executeOnce(url, method, headers, body)
            } catch (e: ProviderException.RateLimited) {
                lastError = e
                // Honour Retry-After when the provider sent one, otherwise back off.
                val wait = e.retryAfterMs ?: backoffMs(attempt)
                if (attempt == retries) throw e
                delay(wait)
            } catch (e: ProviderException.ServerError) {
                lastError = e
                if (attempt == retries) throw e
                delay(backoffMs(attempt))
            } catch (e: ProviderException.Offline) {
                lastError = e
                if (attempt == retries) throw e
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
    ): HttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, body)

        try {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val headerMap = response.headers.names().associateWith { response.headers[it].orEmpty() }
                when {
                    response.isSuccessful -> HttpResponse(response.code, text, headerMap)
                    response.code == 401 -> throw ProviderException.Unauthorized()
                    response.code == 403 -> throw ProviderException.Forbidden()
                    response.code == 429 -> throw ProviderException.RateLimited(
                        retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.times(1000),
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
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 8_000L

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
