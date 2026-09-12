package com.usagelimits.providers.codex

import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.ProviderEndpoints.Codex
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.LoginChallenge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/**
 * Codex signs in through the browser, on the CLI's registered redirect, with the device flow
 * held in reserve for a taken port.
 *
 * Robolectric only because PKCE encodes with `android.util.Base64`; nothing here renders.
 * All fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class CodexBrowserLoginTest {

    private val tokenJson = """{"access_token":"a","refresh_token":"r","id_token":"i","expires_in":3600}"""
    private val deviceJson = """{"user_code":"WDJB-MJHT","device_auth_id":"dev-1","interval":5}"""

    /** Answers every request with [json] and records the bodies it was sent. */
    private fun answering(json: String, bodies: MutableList<String> = mutableListOf()) = HttpClient(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                request.body?.let { body -> bodies += Buffer().also(body::writeTo).readUtf8() }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody(HttpClient.JSON_MEDIA_TYPE))
                    .build()
            }
            .build(),
    )

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { pair ->
            pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
        }

    private fun send(port: Int, requestLine: String) {
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.getOutputStream().write("$requestLine\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            runCatching { socket.getInputStream().readBytes() }
        }
    }

    private fun portIsFree(): Boolean = runCatching {
        ServerSocket(Codex.REDIRECT_PORT, 1, InetAddress.getByName("127.0.0.1")).close()
    }.isSuccess

    @Test
    fun `the browser flow uses the CLI's registration and exchanges the loopback redirect`() =
        runBlocking {
            val bodies = mutableListOf<String>()
            val provider = CodexProvider(answering(tokenJson, bodies))

            val challenge = provider.beginLogin()
            assertTrue("browser flow, not a code to carry", challenge is LoginChallenge.Redirect)
            challenge as LoginChallenge.Redirect
            assertEquals(Codex.REDIRECT_URI, challenge.redirectUri)
            assertTrue(challenge.authorizationUrl.startsWith("${Codex.AUTHORIZE_URL}?"))

            // Exactly what the Codex CLI sends, because the registration is its own.
            val params = query(challenge.authorizationUrl)
            assertEquals("code", params["response_type"])
            assertEquals(Codex.CLIENT_ID, params["client_id"])
            assertEquals(Codex.REDIRECT_URI, params["redirect_uri"])
            assertEquals(Codex.AUTHORIZE_SCOPE, params["scope"])
            assertEquals("S256", params["code_challenge_method"])
            assertEquals("true", params["id_token_add_organizations"])
            assertEquals("true", params["codex_cli_simplified_flow"])
            assertTrue(params.getValue("state").length >= 32)
            assertTrue(params.getValue("code_challenge").isNotBlank())

            val completing = async(Dispatchers.IO) { provider.completeLogin(challenge, null) }
            withContext(Dispatchers.IO) {
                send(Codex.REDIRECT_PORT, "GET /auth/callback?code=C1&state=${params["state"]} HTTP/1.1")
            }
            val credentials = completing.await()

            assertEquals("a", credentials.accessToken)
            val body = bodies.single()
            val form = body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            assertEquals("authorization_code", form["grant_type"])
            assertEquals("C1", form["code"])
            assertEquals(Codex.CLIENT_ID, form["client_id"])
            assertEquals("the loopback redirect, not the device callback", Codex.REDIRECT_URI, form["redirect_uri"])

            // PKCE the way it is meant to work: the verifier sent is the one behind the
            // challenge the browser saw, and it was generated here, not by the provider.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(form.getValue("code_verifier").toByteArray(Charsets.US_ASCII))
            assertEquals(params["code_challenge"], Base64.getUrlEncoder().withoutPadding().encodeToString(digest))

            assertTrue("the port is released after the exchange", portIsFree())
        }

    @Test
    fun `unrelated callbacks are ignored until the matching redirect arrives`() = runBlocking {
        // Deliberately replaces the old fail-on-mismatch contract: strangers must neither
        // exchange a code nor terminate a login that the real browser can still complete.
        val bodies = mutableListOf<String>()
        val provider = CodexProvider(answering(tokenJson, bodies))
        val challenge = provider.beginLogin() as LoginChallenge.Redirect
        val state = query(challenge.authorizationUrl).getValue("state")

        val completing = async(Dispatchers.IO) { runCatching { provider.completeLogin(challenge, null) } }
        withContext(Dispatchers.IO) {
            for (query in listOf("code=stranger&state=not-ours", "code=stranger",
                "error=access_denied&state=not-ours", "error=access_denied")) {
                // A broken listener closes after the first stranger. Keep the fixture moving
                // so the assertion reports that login failure rather than connection refusal.
                runCatching { send(Codex.REDIRECT_PORT, "GET /auth/callback?$query HTTP/1.1") }
            }
            runCatching { send(Codex.REDIRECT_PORT, "GET /auth/callback?code=real-code&state=$state HTTP/1.1") }
        }
        val credentials = completing.await().getOrThrow()

        assertEquals("a", credentials.accessToken)
        val body = bodies.single()
        val form = body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("only the matching callback is exchanged", "real-code", form["code"])
        assertTrue(portIsFree())
    }

    @Test
    fun `a matching denial ends login without exchanging a code`() = runBlocking {
        val bodies = mutableListOf<String>()
        val provider = CodexProvider(answering(tokenJson, bodies))
        val challenge = provider.beginLogin() as LoginChallenge.Redirect
        val state = query(challenge.authorizationUrl).getValue("state")
        val completing = async(Dispatchers.IO) { runCatching { provider.completeLogin(challenge, null) } }
        withContext(Dispatchers.IO) {
            send(Codex.REDIRECT_PORT, "GET /auth/callback?error=access_denied&state=$state HTTP/1.1")
        }
        assertTrue(completing.await().exceptionOrNull() is ProviderException.LoginCancelled)
        assertTrue(bodies.isEmpty())
        assertTrue(portIsFree())
    }

    @Test
    fun `when the port is taken the device flow takes over`() = runBlocking {
        ServerSocket(Codex.REDIRECT_PORT, 1, InetAddress.getByName("127.0.0.1")).use {
            val challenge = CodexProvider(answering(deviceJson)).beginLogin()

            assertTrue("device flow when the redirect cannot be received", challenge is LoginChallenge.DeviceCode)
            challenge as LoginChallenge.DeviceCode
            assertEquals("WDJB-MJHT", CodexProvider.displayCode(challenge.userCode))
            assertEquals(Codex.DEVICE_VERIFICATION_URL, challenge.verificationUri)
        }
    }

    @Test
    fun `the device exchange keeps the device callback as its redirect`() = runBlocking {
        // The fallback's code was issued against the device callback, and the token endpoint
        // checks that the exchange names the same one. The test-only entry point defaults to
        // it, so the existing one-time-grant test keeps exercising that path unchanged.
        val bodies = mutableListOf<String>()
        CodexProvider(answering(tokenJson, bodies)).exchangeCode("C1", "v")

        val form = bodies.single().split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(Codex.DEVICE_EXCHANGE_REDIRECT_URI, form["redirect_uri"])
        assertEquals(URLEncoder.encode(Codex.DEVICE_EXCHANGE_REDIRECT_URI, "UTF-8"), bodies.single().substringAfter("redirect_uri=").substringBefore('&'))
    }
}
