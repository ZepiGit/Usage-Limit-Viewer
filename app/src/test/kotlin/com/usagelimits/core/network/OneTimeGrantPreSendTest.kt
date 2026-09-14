package com.usagelimits.core.network

import java.net.InetAddress
import java.net.UnknownHostException
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import javax.net.SocketFactory
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.providers.codex.CodexProvider
import com.usagelimits.providers.claude.ClaudeProvider
import com.usagelimits.providers.antigravity.AntigravityProvider

class OneTimeGrantPreSendTest {
    private fun resolver(lookup: () -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = lookup()
    }

    private fun failingSockets(beforeConnect: () -> Unit) = object : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                beforeConnect()
                super.connect(endpoint, timeout)
            }
        }
        override fun createSocket(host: String, port: Int): Socket = error("unused overload")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("unused overload")
        override fun createSocket(host: InetAddress, port: Int): Socket = error("unused overload")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("unused overload")
    }

    @Test fun `a temporary DNS outage recovers without presenting the grant twice`() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("ok"))
            var lookups = 0
            val http = HttpClient(OkHttpClient.Builder().dns(resolver {
                if (++lookups < 3) throw UnknownHostException("temporary resolver failure")
                listOf(InetAddress.getByName("127.0.0.1"))
            }).build())

            val response = http.request(
                "http://localhost:${server.port}/token", method = "POST",
                body = "code=C1".toRequestBody(HttpClient.FORM_MEDIA_TYPE), oneTimeGrant = true,
            )

            assertEquals("ok", response.body)
            assertEquals(3, lookups)
            assertEquals("only the successful connection sends the code", 1, server.requestCount)
            assertEquals("code=C1", server.takeRequest().body.readUtf8())
        }
    }

    @Test fun `an unavailable resolver stops after the bounded retry budget`() = runTest {
        var lookups = 0
        val http = HttpClient(OkHttpClient.Builder().dns(resolver {
            lookups++
            throw UnknownHostException("private details must not reach the UI")
        }).build())
        val failure = runCatching {
            http.request("https://auth.example.invalid/token", method = "POST",
                body = "code=C1".toRequestBody(HttpClient.FORM_MEDIA_TYPE), oneTimeGrant = true)
        }.exceptionOrNull()

        assertTrue(failure is ProviderException.Offline)
        assertEquals(3, lookups)
        assertTrue(failure!!.message!!.contains("auth.example.invalid"))
        assertTrue(!failure.message!!.contains("private details"))
    }

    @Test fun `a fresh attempt can connect after all routes were unavailable`() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("ok"))
            var lookups = 0
            val http = HttpClient(OkHttpClient.Builder().dns(resolver {
                listOf(InetAddress.getByName(if (++lookups == 1) "::1" else "127.0.0.1"))
            }).build())
            assertEquals("ok", http.request("http://localhost:${server.port}/token", method = "POST",
                body = "code=C1".toRequestBody(HttpClient.FORM_MEDIA_TYPE), oneTimeGrant = true).body)
            assertEquals(2, lookups)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `all three login providers recover before sending their code`() = runTest {
        val exchanges = listOf<Pair<String, suspend (HttpClient) -> OAuthCredentials>>(
            "Codex" to { CodexProvider(it).exchangeCode("C1", "verifier") },
            "Claude Code" to { ClaudeProvider(it).exchangeCode("C1", "verifier", "state") },
            "Antigravity" to { AntigravityProvider(it).exchangeCode("C1", "verifier") },
        )
        for ((name, exchange) in exchanges) {
            MockWebServer().use { server ->
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                server.enqueue(MockResponse().setBody(
                    """{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600}"""))
                var lookups = 0
                val http = HttpClient(OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder()
                            .url("http://localhost:${server.port}/token").build())
                    }
                    .dns(resolver {
                        if (++lookups == 1) throw UnknownHostException("temporary failure")
                        listOf(InetAddress.getByName("127.0.0.1"))
                    }).build())
                val credentials = exchange(http)
                assertEquals(name, "fixture-access", credentials.accessToken)
                assertEquals(name, "fixture-refresh", credentials.refreshToken)
                assertEquals(name, 2, lookups)
                assertEquals(name, 1, server.requestCount)
                assertTrue(server.takeRequest().body.readUtf8().contains("C1"))
            }
        }
    }

    @Test fun `a connection interrupted before the send can recover`() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("ok"))
            var attempts = 0
            val http = HttpClient(OkHttpClient.Builder().socketFactory(failingSockets {
                if (++attempts == 1) throw SocketException("connection reset")
            }).build())
            assertEquals("ok", http.request("http://127.0.0.1:${server.port}/token", method = "POST",
                body = "code=C1".toRequestBody(HttpClient.FORM_MEDIA_TYPE), oneTimeGrant = true).body)
            assertEquals(2, attempts)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `a certificate failure is not treated as a temporary connection failure`() = runTest {
        var attempts = 0
        val http = HttpClient(OkHttpClient.Builder().socketFactory(failingSockets {
            attempts++
            throw SSLPeerUnverifiedException("certificate rejected")
        }).build())
        val failure = runCatching {
            http.request("https://127.0.0.1/token", method = "POST",
                body = "code=C1".toRequestBody(HttpClient.FORM_MEDIA_TYPE), oneTimeGrant = true)
        }.exceptionOrNull()
        assertTrue(failure is ProviderException.Offline)
        assertEquals(1, attempts)
        assertTrue(!(failure as ProviderException.Offline).requestNotSent)
    }
}
