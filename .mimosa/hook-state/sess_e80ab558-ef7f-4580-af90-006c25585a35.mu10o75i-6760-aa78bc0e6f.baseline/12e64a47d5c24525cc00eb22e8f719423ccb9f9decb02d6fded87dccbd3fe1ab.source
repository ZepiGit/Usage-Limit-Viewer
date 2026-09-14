package com.usagelimits.core.oauth

import com.usagelimits.core.network.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
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
            // A backlog with room for the browser's speculative connections. Chrome opens
            // sockets it may never speak on ahead of a navigation it predicts, and with a
            // backlog of one the connection carrying the real redirect could be refused while
            // two idle ones sat in the queue — the browser then showed "can't reach this page"
            // for a listener that was running.
            ServerSocket(port, ACCEPT_BACKLOG, InetAddress.getByName("127.0.0.1"))
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
     *
     * The wait polls a short SO_TIMEOUT rather than blocking in one open-ended `accept()`.
     * That is not a style choice: `ServerSocket.accept()` ignores `Thread.interrupt()`, so
     * neither `withTimeout` nor job cancellation could ever unblock it — only closing the
     * socket can, and the close lives in the caller's `finally`, downstream of this call. An
     * abandoned login therefore parked an IO thread forever and left the pinned port bound
     * for the life of the process, so every later login on that provider failed to bind.
     */
    suspend fun awaitRedirect(
        timeoutMs: Long,
        isExpected: (AuthorizationResponse) -> Boolean = { true },
    ): AuthorizationResponse = withContext(Dispatchers.IO) {
        val socket = serverSocket ?: throw ProviderException.Unexpected("server not started")
        socket.soTimeout = ACCEPT_POLL_MS

        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MS
        var response: AuthorizationResponse? = null
        while (response == null) {
            ensureActive()
            if (System.nanoTime() - deadline >= 0) {
                throw ProviderException.LoginCancelled("Login timed out")
            }
            response = acceptOnce(socket, isExpected)
        }
        response
    }

    /** One bounded accept. Null when the poll window elapsed with nothing connecting. */
    private fun acceptOnce(socket: ServerSocket, isExpected: (AuthorizationResponse) -> Boolean): AuthorizationResponse? {
        val client = try {
            socket.accept()
        } catch (e: SocketTimeoutException) {
            return null
        } catch (e: IOException) {
            // close() from another thread is the normal way this ends early.
            throw ProviderException.LoginCancelled("Login was interrupted")
        }
        return client.use { connection ->
            // A read deadline on the ACCEPTED socket, which `socket.soTimeout` above does not
            // provide: that one bounds `accept()` on the listening socket and says nothing
            // about how long a peer may take to speak once connected. Without this, a local
            // process that connects and then sends NOTHING blocks the read for ever — the
            // deadline below is never re-checked, cancellation cannot interrupt a blocking
            // read, and no genuine redirect can be served while that connection is held. The
            // 8 KB cap does not help: it bounds how much a peer may say, not how long it may
            // stay silent.
            connection.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(connection.getInputStream()))
            val requestLine = try {
                readBoundedLine(reader, connection)
            } catch (e: SocketTimeoutException) {
                // Said nothing in time. Not an answer, and not a reason to end the sign-in.
                return@use null
            }
            val parsed = parseRequestLine(requestLine)
            if (!isExpected(parsed)) {
                connection.getOutputStream().write(
                    "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                return@use null
            }
            connection.getOutputStream().write(httpResponse(parsed).toByteArray(Charsets.UTF_8))
            connection.getOutputStream().flush()

            // A connection that is not the redirect does NOT end the wait.
            //
            // Every process on the device shares 127.0.0.1, so treating the first connection
            // as the answer let anything at all break a sign-in in progress: the caller throws
            // "state mismatch" on a response with no state, so one `curl http://127.0.0.1:PORT/`
            // from any app killed the login. The browser alone opens more than one connection —
            // a favicon fetch would do it without malice.
            //
            // A real redirect always carries one or the other: `code` on success, `error` when
            // the user declines. Anything else is answered politely and ignored.
            // Blank counts as absent. `?code=` parses to an empty string, which is non-null
            // and would otherwise pass as an answer — then fail at the caller for having no
            // state, ending the sign-in on a request that carried nothing at all.
            val hasAnswer = !parsed.code.isNullOrBlank() || !parsed.error.isNullOrBlank()
            if (hasAnswer) parsed else null
        }
    }

    override fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /**
     * Reads one request line, and no more than one request line's worth of bytes.
     *
     * `readLine()` reads until a newline arrives, however long that takes and however much it
     * accumulates. A local process that opens the port and streams bytes without ever sending
     * one grows this buffer until the app dies — no credential needed, just the address every
     * app on the device can reach. A redirect line is a few hundred bytes; this cap is far
     * above anything legitimate and far below anything dangerous.
     */
    private fun readBoundedLine(reader: BufferedReader, connection: java.net.Socket): String {
        // A TOTAL deadline for the line, not merely a per-read one. `soTimeout` bounds each
        // individual read, so a peer that sends one byte every few seconds never trips it —
        // and one that sends carriage returns adds nothing to the builder, so the byte cap
        // never trips either. Such a connection held the accept loop for as long as it liked,
        // past the sign-in's own deadline, and no genuine redirect could be served meanwhile.
        // The remaining time is re-applied before every read so the existing handler for a
        // silent peer covers a slow one too.
        val builder = StringBuilder()
        val deadline = System.nanoTime() + READ_TIMEOUT_MS.toLong() * NANOS_PER_MS
        while (builder.length < MAX_REQUEST_LINE_BYTES) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) throw SocketTimeoutException("request line took too long")
            connection.soTimeout = ((remainingNanos + NANOS_PER_MS - 1) / NANOS_PER_MS).toInt()
            val ch = reader.read()
            if (ch == -1 || ch == '\n'.code) break
            if (ch != '\r'.code) builder.append(ch.toChar())
        }
        return builder.toString()
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

    private fun escapeHtml(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private fun httpResponse(result: AuthorizationResponse): String {
        val ok = result.error == null && result.code != null
        val title = if (ok) "Signed in" else "Sign-in failed"
        // error/error_description are attacker-influenced: any local app can open
        // http://127.0.0.1:PORT/?error=<img src=x onerror=...> while the listener is up.
        // Interpolating them raw would execute script at this origin, so they are escaped.
        val detail = if (ok) {
            "You can close this tab and return to Usage Limits."
        } else {
            escapeHtml(result.errorDescription ?: result.error ?: "No authorization code was returned.")
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

    private companion object {
        /** How long one accept() blocks before cancellation and the deadline are re-checked. */
        const val ACCEPT_POLL_MS = 200

        /** Pending connections the kernel may hold while one is being read. */
        const val ACCEPT_BACKLOG = 8

        /** Far above any real redirect line, far below anything that could exhaust memory. */
        const val MAX_REQUEST_LINE_BYTES = 8 * 1024

        /** How long a connected peer may stay silent before it is dropped and ignored. */
        const val READ_TIMEOUT_MS = 5_000
        const val NANOS_PER_MS = 1_000_000L
    }
}
