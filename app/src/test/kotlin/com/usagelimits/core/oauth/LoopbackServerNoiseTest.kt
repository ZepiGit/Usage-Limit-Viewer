package com.usagelimits.core.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
    fun `a peer that connects and says nothing does not stall the sign-in`() = runBlocking {
        // The worst of the local-process attacks, and the one the byte cap does NOT stop: it
        // bounds how much a peer may say, not how long it may stay silent. Without a read
        // deadline on the accepted socket this blocks for ever — the wait's own deadline is
        // never re-checked, and a blocking read cannot be interrupted by cancellation, so the
        // sign-in can never finish and never time out either.
        val port = freePort()
        val server = LoopbackServer(port)
        server.start()

        val waiting = async(Dispatchers.IO) { server.awaitRedirect(timeoutMs = 30_000) }

        withContext(Dispatchers.IO) {
            val silent = Socket(InetAddress.getByName("127.0.0.1"), port)
            // Held open, deliberately never written to, and still open while the real
            // redirect arrives.
            send(port, "GET /callback?code=survived&state=the-state HTTP/1.1")
            runCatching { silent.close() }
        }

        val response = waiting.await()
        server.close()

        assertEquals("survived", response.code)
    }

    @Test
    fun `an empty code parameter is not an answer`() = runBlocking {
        // `?code=` parses to an empty string, which is non-null. Treating that as the redirect
        // would end the wait on a request carrying nothing, and the caller would then fail it
        // for having no state — a sign-in killed by six characters from any app on the phone.
        val port = freePort()
        val server = LoopbackServer(port)
        server.start()

        val waiting = async(Dispatchers.IO) { server.awaitRedirect(timeoutMs = 20_000) }
        withContext(Dispatchers.IO) {
            send(port, "GET /callback?code= HTTP/1.1")
            send(port, "GET /callback?error= HTTP/1.1")
            send(port, "GET /callback?code=real&state=the-state HTTP/1.1")
        }

        val response = waiting.await()
        server.close()

        assertEquals("real", response.code)
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
    @Test
    fun `a peer that trickles one byte at a time cannot hold the sign-in open`() = runBlocking {
        // Each carriage return arrives inside the per-read timeout and adds nothing to the
        // line, so neither the read timeout nor the byte cap ever tripped: such a connection
        // held the accept loop for as long as it liked, past the sign-in's own deadline, and the
        // genuine redirect queued behind it was never read. A TOTAL deadline on the request line
        // drops it after READ_TIMEOUT_MS and the real callback lands.
        val port = freePort()
        val server = LoopbackServer(port)
        server.start()

        val waiting = async(Dispatchers.IO) { server.awaitRedirect(timeoutMs = 30_000) }
        val keepTrickling = java.util.concurrent.atomic.AtomicBoolean(true)
        val trickler = launch(Dispatchers.IO) {
            runCatching {
                Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                    val out = socket.getOutputStream()
                    while (keepTrickling.get()) {
                        out.write('\r'.code)
                        out.flush()
                        Thread.sleep(400)
                    }
                }
            }
        }

        val started = System.nanoTime()
        withContext(Dispatchers.IO) {
            Thread.sleep(500)
            send(port, "GET /callback?code=survived&state=the-state HTTP/1.1")
        }
        val response = waiting.await()
        keepTrickling.set(false)
        trickler.cancel()
        server.close()

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("the sign-in survived the trickle", response.code == "survived")
        assertTrue("answered in ${elapsedMs}ms, not at the 30s deadline", elapsedMs < 15_000)
    }

}
