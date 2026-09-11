package com.usagelimits.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * A one-time grant may be retried BEFORE it is sent and never after.
 *
 * The first attempt at "present a grant at most once" switched off OkHttp's connection
 * recovery wholesale, and with it the half that happens before any byte is written: trying
 * the host's next address when the first will not connect. On a phone that resolves a token
 * endpoint to an IPv6 address it cannot reach — routine on mobile networks — every token
 * exchange then died on that first address and reported "No network connection", for Codex,
 * Claude and Antigravity alike, on a connection that was working. These two tests pin the
 * line where the retry has to stop: at the send, not before it.
 */
class OneTimeGrantRouteFallbackTest {

    private val json = """{"access_token":"a","refresh_token":"r","expires_in":3600}"""
    private val grant = "grant_type=refresh_token&refresh_token=R1"
        .toRequestBody(HttpClient.FORM_MEDIA_TYPE)

    /** Resolves `localhost` to the addresses given, in order — a multi-homed host in miniature. */
    private class Routes(private vararg val addresses: InetAddress) : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses.toList()
    }

    private val live: InetAddress = InetAddress.getByName("127.0.0.1")

    /** A route nothing listens on: the server below binds IPv4 loopback only. */
    private val dead: InetAddress = InetAddress.getByName("::1")

    private fun server(): MockWebServer = MockWebServer().also {
        // IPv4 loopback only, so the IPv6 loopback route above is refused rather than served.
        it.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @Test
    fun `a one-time grant still reaches the server when its first route is dead`() = runBlocking {
        val server = server()
        server.enqueue(MockResponse().setBody(json))
        try {
            val client = HttpClient(OkHttpClient.Builder().dns(Routes(dead, live)).build())

            val response = client.request(
                url = "http://localhost:${server.port}/oauth/token",
                method = "POST",
                body = grant,
                oneTimeGrant = true,
            )

            assertTrue(response.body.contains("access_token"))
            assertEquals("delivered exactly once, on the second route", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a one-time grant is not resent when the reply is lost after the send`() = runBlocking {
        val server = server()
        // The server reads the whole request and then hangs up: the grant has ARRIVED, and
        // whether it was acted on is unknowable from this side. The host resolves to a second
        // route, as a real token endpoint does, and without the one-shot marker OkHttp moves
        // the POST to that route as a matter of course — where the second attempt would be
        // answered from the queue below. The count is what tells the two apart.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody(json))
        try {
            val client = HttpClient(OkHttpClient.Builder().dns(Routes(live, live)).build())

            val failure = runCatching {
                client.request(
                    url = "http://localhost:${server.port}/oauth/token",
                    method = "POST",
                    body = grant,
                    oneTimeGrant = true,
                )
            }.exceptionOrNull()

            assertTrue("expected Offline, got $failure", failure is ProviderException.Offline)
            assertEquals("the grant reached the server exactly once", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a transport failure names the host and the kind of failure`() = runBlocking {
        val server = server()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try {
            val client = HttpClient(OkHttpClient.Builder().build())

            val failure = runCatching {
                client.request(
                    url = "http://localhost:${server.port}/oauth/token",
                    method = "POST",
                    body = grant,
                    oneTimeGrant = true,
                )
            }.exceptionOrNull() as ProviderException.Offline

            val message = failure.message.orEmpty()
            assertTrue("names the host: $message", message.contains("localhost"))
            assertTrue("no URL or port leaks: $message", !message.contains("${server.port}"))
            assertTrue("not the generic line: $message", message != "No network connection")
        } finally {
            server.shutdown()
        }
    }
}
