package com.usagelimits.core.oauth

import com.usagelimits.core.network.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The redirect listener has to be able to give up.
 *
 * `ServerSocket.accept()` ignores `Thread.interrupt()`, so the previous
 * `withTimeoutOrNull { runInterruptible { accept() } }` could never be unblocked: an abandoned
 * login parked an IO thread forever and left the provider's pinned port bound for the life of
 * the process, so every later login on that provider failed with "Cannot listen on port ...".
 * These pin both escape routes — the deadline and cancellation — and that the port is really
 * free afterwards. The JUnit timeouts are the point: a regression hangs rather than misbehaves.
 */
class LoopbackServerTest {

    /** A port nothing is listening on right now. Bound and released to learn its number. */
    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun portIsFree(port: Int): Boolean =
        runCatching { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close() }.isSuccess

    @Test(timeout = 30_000)
    fun `awaitRedirect gives up at the deadline instead of blocking forever`() {
        val port = freePort()
        val server = LoopbackServer(port)

        val error = try {
            assertThrows(ProviderException.LoginCancelled::class.java) {
                runBlocking {
                    server.start()
                    server.awaitRedirect(timeoutMs = 50)
                }
            }
        } finally {
            server.close()
        }

        assertEquals("Login timed out", error.message)
        assertTrue("port $port was still bound after the timeout", portIsFree(port))
    }

    @Test(timeout = 30_000)
    fun `cancelling the login releases the port`() = runBlocking {
        val port = freePort()
        val server = LoopbackServer(port)
        server.start()

        // Mirrors AddAccountViewModel: the whole login is one job, and Cancel cancels it.
        val loginJob = launch(Dispatchers.IO) {
            try {
                server.awaitRedirect(timeoutMs = 5 * 60 * 1000)
            } finally {
                server.close()
            }
        }

        // Let accept() actually start blocking before pulling the rug out.
        delay(400)
        loginJob.cancel()
        loginJob.join()

        assertTrue("port $port was still bound after cancellation", portIsFree(port))
    }

    @Test(timeout = 30_000)
    fun `a redirect still parses and is answered`() = runBlocking {
        val port = freePort()
        val server = LoopbackServer(port)
        server.start()

        val redirect = async(Dispatchers.IO) { server.awaitRedirect(timeoutMs = 20_000) }
        delay(200)

        val reply = withContext(Dispatchers.IO) {
            Socket(InetAddress.getByName("127.0.0.1"), port).use { client ->
                client.getOutputStream().write(
                    "GET /callback?code=abc123&state=xyz789 HTTP/1.1\r\nHost: localhost\r\n\r\n"
                        .toByteArray(Charsets.UTF_8),
                )
                client.getOutputStream().flush()
                client.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
        }

        val response = redirect.await()
        server.close()

        assertEquals("abc123", response.code)
        assertEquals("xyz789", response.state)
        assertNull(response.error)
        assertTrue("browser got: ${reply.take(40)}", reply.startsWith("HTTP/1.1 200 OK"))
    }
}
