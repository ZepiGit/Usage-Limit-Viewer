package com.usagelimits.core.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Every process on the device can reach 127.0.0.1, so the listener has to survive strangers.
 *
 * It used to treat the first connection as the answer. Since the caller throws "state mismatch"
 * on a response carrying no state, a single `curl http://127.0.0.1:PORT/` from any app on the
 * phone ended a sign-in in progress — and no malice was required, because a browser opens more
 * than one connection to a page and a favicon fetch does the same thing by accident.
 *
 * The iOS twin was written this way from the start; this is the Android side catching up.
 */
class LoopbackServerNoiseTest {

    /** A free port, released immediately so the server under test can claim it. */
    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun send(port: Int, requestLine: String) {
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.getOutputStream().write("$requestLine\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            // Read the reply so the server's write completes before the socket closes.
            runCatching { socket.getInputStream().readBytes() }
        }
    }

    @Test
    fun `a stray connection does not end the wait, and the real redirect still lands`() =
        runBlocking {
            val port = freePort()
            val server = LoopbackServer(port)
            server.start()

            val waiting = async(Dispatchers.IO) { server.awaitRedirect(timeoutMs = 15_000) }

            withContext(Dispatchers.IO) {
                // What any other app on the device — or the browser fetching a favicon — sends.
                send(port, "GET / HTTP/1.1")
                send(port, "GET /favicon.ico HTTP/1.1")
                // Then the redirect the sign-in is actually waiting for.
                send(port, "GET /callback?code=the-code&state=the-state HTTP/1.1")
            }

            val response = waiting.await()
            server.close()

            assertEquals("the-code", response.code)
            assertEquals("the-state", response.state)
        }

    @Test
    fun `an error redirect still ends the wait, because declining is an answer`() = runBlocking {
        val port = freePort()
        val server = LoopbackServer(port)
        server.start()

        val waiting = async(Dispatchers.IO) { server.awaitRedirect(timeoutMs = 15_000) }
        withContext(Dispatchers.IO) {
            send(port, "GET /callback?error=access_denied&error_description=No HTTP/1.1")
        }

        val response = waiting.await()
        server.close()

        assertEquals("access_denied", response.error)
    }

    @Test
    fun `a request line that never ends is bounded rather than unbounded`() = runBlocking {
        val port = freePort()
        val server = LoopbackServer(port)
        server.start()

        val waiting = async(Dispatchers.IO) { server.awaitRedirect(timeoutMs = 20_000) }

        withContext(Dispatchers.IO) {
            // No newline, ever. Unbounded, this grows until the app dies; bounded, the server
            // stops reading at the cap, answers, ignores it and keeps waiting.
            Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                val out = socket.getOutputStream()
                repeat(40) { out.write(ByteArray(1024) { 'A'.code.toByte() }) }
                out.flush()
                runCatching { socket.getInputStream().readBytes() }
            }
            send(port, "GET /callback?code=survived&state=the-state HTTP/1.1")
        }

        val response = waiting.await()
        server.close()

        assertTrue("the sign-in survived the flood", response.code == "survived")
    }
}
