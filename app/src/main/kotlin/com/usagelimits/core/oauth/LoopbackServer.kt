package com.usagelimits.core.oauth

import com.usagelimits.core.network.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder

/** Query parameters returned by an authorization server on the redirect. */
data class AuthorizationResponse(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
)

/**
 * Minimal loopback HTTP listener for OAuth redirects (RFC 8252 §7.3).
 *
 * Claude and Antigravity pin `http://localhost:<port>/...` in their client registration, so a
 * custom Android scheme cannot be substituted — the app has to receive the redirect on that
 * exact port. The socket binds to the loopback interface only, so nothing on the network can
 * reach it, and it serves exactly one request before closing.
 *
 * Using this rather than an embedded WebView is deliberate: the user authenticates in the
 * real browser, where they can see the address bar and the app never observes their password.
 */
class LoopbackServer(private val port: Int) : Closeable {

    private var serverSocket: ServerSocket? = null

    /** Binds the port up front so a conflict surfaces before the browser is launched. */
    fun start() {
        if (serverSocket != null) return
        serverSocket = try {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            throw ProviderException.Unexpected(
                "Cannot listen on port $port for the login redirect. " +
                    "Another app may be using it; close it and try again.",
                e,
            )
        }
    }

    /**
     * Waits for the redirect and returns its parameters.
     *
     * Answers the browser with a small page either way, so the user sees a result instead of
     * a connection error, then closes.
     */
    suspend fun awaitRedirect(timeoutMs: Long): AuthorizationResponse = withContext(Dispatchers.IO) {
        val socket = serverSocket ?: throw ProviderException.Unexpected("server not started")

        val response = withTimeoutOrNull(timeoutMs) {
            runInterruptible {
                socket.accept().use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val requestLine = reader.readLine().orEmpty()
                    val parsed = parseRequestLine(requestLine)
                    client.getOutputStream().write(httpResponse(parsed).toByteArray(Charsets.UTF_8))
                    client.getOutputStream().flush()
                    parsed
                }
            }
        } ?: throw ProviderException.LoginCancelled("Login timed out")

        response
    }

    /** Runs a blocking accept() in a way that cancellation can interrupt. */
    private suspend fun <T> runInterruptible(block: () -> T): T =
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) { block() }

    override fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun parseRequestLine(line: String): AuthorizationResponse {
        // "GET /callback?code=...&state=... HTTP/1.1"
        val path = line.split(' ').getOrNull(1).orEmpty()
        val query = path.substringAfter('?', "")
        val params = query.split('&')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = decode(pair.substring(0, idx))
                val value = decode(pair.substring(idx + 1))
                key to value
            }
            .toMap()

        return AuthorizationResponse(
            code = params["code"],
            state = params["state"],
            error = params["error"],
            errorDescription = params["error_description"],
        )
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun httpResponse(result: AuthorizationResponse): String {
        val ok = result.error == null && result.code != null
        val title = if (ok) "Signed in" else "Sign-in failed"
        val detail = if (ok) {
            "You can close this tab and return to Usage Limits."
        } else {
            result.errorDescription ?: result.error ?: "No authorization code was returned."
        }
        val html = """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>$title</title>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{background:#05070D;color:#E8ECF5;font-family:system-ui,-apple-system,sans-serif;
                   display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
              .card{background:#111726;border-radius:20px;padding:32px;max-width:360px;text-align:center}
              h1{font-size:20px;margin:0 0 8px}p{color:#93A0B8;margin:0;font-size:14px}
            </style></head>
            <body><div class="card"><h1>$title</h1><p>$detail</p></div></body></html>
        """.trimIndent()

        return buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\n")
            append("Connection: close\r\n\r\n")
            append(html)
        }
    }
}
