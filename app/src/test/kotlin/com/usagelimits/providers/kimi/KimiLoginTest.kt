package com.usagelimits.providers.kimi

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.ProviderEndpoints.Kimi
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.LoginChallenge
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * Kimi signs in with its device flow, and says who is asking.
 *
 * The identity is the whole point of these tests: Moonshot forbids one thing, presenting
 * another program's name, and allowlists a program under its own. So every request here has
 * to carry this app's name and never the CLI's. All values synthetic.
 */
class KimiLoginTest {

    private val deviceJson = """
        {"device_code":"dev-1","user_code":"KIMI-ABCD","verification_uri":"https://auth.kimi.com/device",
         "verification_uri_complete":"https://auth.kimi.com/device?code=KIMI-ABCD","expires_in":900,"interval":5}
    """.trimIndent()
    private val tokenJson = """{"access_token":"acc","refresh_token":"ref","token_type":"Bearer","expires_in":3600.0}"""
    private val usageJson = """{"userId":"kimi-user-7","usage":{"limit":100,"used":10}}"""

    private class Recorded(val request: Request, val body: String)

    /** Answers requests from [replies] in order, recording each one; the last reply repeats. */
    private fun scripted(vararg replies: Pair<Int, String>, seen: MutableList<Recorded> = mutableListOf()) =
        HttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
                    seen += Recorded(request, body)
                    val (code, json) = replies.getOrElse(seen.size - 1) { replies.last() }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("scripted")
                        .body(json.toResponseBody(HttpClient.JSON_MEDIA_TYPE))
                        .build()
                }
                .build(),
        )

    private fun form(body: String): Map<String, String> =
        body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun provider(http: HttpClient, now: () -> Long = { 1_000_000L }) =
        KimiProvider(http, nowMs = now, version = "9.9.9")

    @Test
    fun `the device flow asks under this app's own name, never the CLI's`() = runBlocking {
        val seen = mutableListOf<Recorded>()
        val challenge = provider(scripted(200 to deviceJson, seen = seen)).beginLogin()

        val request = seen.single().request
        assertEquals(Kimi.DEVICE_CODE_URL, request.url.toString())
        assertEquals("UsageLimits", request.header("X-Msh-Platform"))
        assertEquals("9.9.9", request.header("X-Msh-Version"))
        assertEquals("UsageLimits/9.9.9 (Android)", request.header("User-Agent"))
        assertFalse("no borrowed identity", seen.single().body.contains("kimi_cli") || request.header("User-Agent")!!.contains("KimiCLI"))
        assertEquals(Kimi.CLIENT_ID, form(seen.single().body)["client_id"])

        assertTrue(challenge is LoginChallenge.DeviceCode)
        challenge as LoginChallenge.DeviceCode
        assertEquals("KIMI-ABCD", KimiProvider.displayCode(challenge.userCode))
        assertEquals("https://auth.kimi.com/device?code=KIMI-ABCD", challenge.verificationUriComplete)
        assertEquals(5_000L, challenge.pollIntervalMs)
        assertEquals(1_000_000L + 900_000L, challenge.expiresAt)
    }

    @Test
    fun `a pending poll keeps waiting and the approval yields tokens`() = runBlocking {
        val seen = mutableListOf<Recorded>()
        val http = scripted(
            200 to deviceJson,
            200 to """{"error":"authorization_pending"}""",
            400 to """{"error":"authorization_pending"}""",
            200 to tokenJson,
            seen = seen,
        )
        // A clock that does not move, and a challenge whose interval is zero, so the poll does
        // not actually sleep.
        val subject = provider(http)
        val begun = subject.beginLogin() as LoginChallenge.DeviceCode
        val challenge = begun.copy(pollIntervalMs = 0)

        val credentials = subject.completeLogin(challenge, null)

        assertEquals("acc", credentials.accessToken)
        assertEquals("ref", credentials.refreshToken)
        assertEquals(1_000_000L + 3_600_000L, credentials.expiresAt)
        assertEquals("four requests: the code, two pending polls, the approval", 4, seen.size)
        val poll = form(seen[1].body)
        assertEquals(Kimi.DEVICE_CODE_GRANT_TYPE, poll["grant_type"])
        assertEquals("dev-1", poll["device_code"])
        assertEquals(Kimi.CLIENT_ID, poll["client_id"])
        assertEquals("UsageLimits", seen[1].request.header("X-Msh-Platform"))
    }

    @Test
    fun `a refusal ends the poll`() = runBlocking {
        val http = scripted(200 to deviceJson, 200 to """{"error":"access_denied"}""")
        val subject = provider(http)
        val challenge = (subject.beginLogin() as LoginChallenge.DeviceCode).copy(pollIntervalMs = 0)

        val failure = runCatching { subject.completeLogin(challenge, null) }.exceptionOrNull()

        assertTrue("expected LoginCancelled, got $failure", failure is ProviderException.LoginCancelled)
    }

    @Test
    fun `HTTP 400 denial or expiry ends the poll before another request`() = runBlocking {
        for (error in listOf("access_denied", "expired_token")) {
            val seen = mutableListOf<Recorded>()
            val subject = provider(scripted(
                400 to """{"error":"$error"}""",
                200 to tokenJson,
                seen = seen,
            ))
            val challenge = LoginChallenge.DeviceCode(
                userCode = "SYNTHETIC|device-synthetic", verificationUri = "https://auth.kimi.com/device",
                verificationUriComplete = null, expiresAt = 2_000_000, pollIntervalMs = 0,
            )

            val failure = runCatching { subject.completeLogin(challenge, null) }.exceptionOrNull()

            assertTrue("$error must end the attempt", failure is ProviderException.LoginCancelled)
            assertEquals("no poll after a terminal answer", 1, seen.size)
        }
    }

    @Test
    fun `a non OAuth HTTP failure is terminal`() = runBlocking {
        val seen = mutableListOf<Recorded>()
        val subject = provider(scripted(404 to "{}", 200 to tokenJson, seen = seen))
        val challenge = LoginChallenge.DeviceCode(
            userCode = "SYNTHETIC|device-synthetic", verificationUri = "https://auth.kimi.com/device",
            verificationUriComplete = null, expiresAt = 2_000_000, pollIntervalMs = 0,
        )
        val failure = runCatching { subject.completeLogin(challenge, null) }.exceptionOrNull()
        assertTrue(failure is ProviderException.Unexpected)
        assertEquals(1, seen.size)
    }

    @Test
    fun `an OAuth account refreshes, a key account is handed back unchanged`() = runBlocking {
        val seen = mutableListOf<Recorded>()
        val subject = provider(scripted(200 to tokenJson, seen = seen))

        val refreshed = subject.refresh(
            OAuthCredentials(accessToken = "old", refreshToken = "ref-1", idToken = null, expiresAt = 0),
        )
        assertEquals("acc", refreshed.accessToken)
        val body = form(seen.single().body)
        assertEquals("refresh_token", body["grant_type"])
        assertEquals("ref-1", body["refresh_token"])
        assertEquals(Kimi.CLIENT_ID, body["client_id"])
        assertEquals("UsageLimits", seen.single().request.header("X-Msh-Platform"))

        val key = OAuthCredentials(accessToken = "sk-synthetic", refreshToken = null, idToken = null, expiresAt = null)
        assertEquals(key, subject.refresh(key))
        assertEquals("no request for a key", 1, seen.size)
    }

    @Test
    fun `the pasted key is still proved against the usage endpoint, under the same name`() = runBlocking {
        val seen = mutableListOf<Recorded>()
        val subject = provider(scripted(200 to usageJson, seen = seen))

        val challenge = subject.keyLoginChallenge()
        assertEquals(Kimi.CONSOLE_URL, challenge.consoleUrl)
        val credentials = subject.completeLogin(challenge, " sk-synthetic-1 ")

        assertEquals("sk-synthetic-1", credentials.accessToken)
        assertNull(credentials.refreshToken)
        val request = seen.single().request
        assertEquals(Kimi.USAGE_ENDPOINT, request.url.toString())
        assertEquals("Bearer sk-synthetic-1", request.header("Authorization"))
        assertEquals("UsageLimits", request.header("X-Msh-Platform"))

        val profile = subject.fetchProfile(credentials)
        assertEquals("kimi-user-7", profile.externalAccountId)
    }

    @Test
    fun `a key that cannot read usage is refused before any account exists`() = runBlocking {
        val subject = provider(scripted(401 to """{"error":"unauthorized"}"""))

        val failure = runCatching { subject.completeLogin(subject.keyLoginChallenge(), "sk-bad") }.exceptionOrNull()

        assertTrue("expected Unauthorized, got $failure", failure is ProviderException.Unauthorized)
    }
}
