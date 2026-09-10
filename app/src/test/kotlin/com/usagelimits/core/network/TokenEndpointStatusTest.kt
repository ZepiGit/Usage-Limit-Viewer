package com.usagelimits.core.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a 400 means depends entirely on which endpoint sent it.
 *
 * On a token endpoint it is `invalid_grant` — RFC 6749 §5.2 assigns that status to a spent,
 * revoked or malformed refresh token — and the only useful response is to tell the user to sign
 * in again. On a quota endpoint the same status means the request was wrong, and reading it as
 * an expired sign-in would tell someone to reconnect a perfectly healthy account.
 *
 * Both codes really do occur. Probing the live token endpoints with a deliberately invalid
 * refresh token, OpenAI answers 401 (`token_expired`) while Google's answers 400. Handling only
 * one of them leaves the other reported as an unexplained HTTP failure, which for a refresh is
 * the difference between "reconnect this account" and a permanently broken account showing a
 * provider-outage message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenEndpointStatusTest {

    private fun clientReturning(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("stubbed")
                    .body(body.toResponseBody(null))
                    .build()
            }
            .build()

    private val invalidGrant = """{"error":"invalid_grant"}"""

    @Test
    fun `a token endpoint 400 is a dead credential`() = runTest {
        val http = HttpClient(clientReturning(400, invalidGrant))

        assertThrows(ProviderException.Unauthorized::class.java) {
            kotlinx.coroutines.runBlocking {
                http.request(
                    url = "https://oauth2.googleapis.com/token",
                    method = "POST",
                    body = HttpClient.formBody(mapOf("grant_type" to "refresh_token")),
                    retries = 0,
                    badRequestMeansExpired = true,
                )
            }
        }
    }

    @Test
    fun `a token endpoint 401 is a dead credential too`() = runTest {
        // OpenAI's actual answer to an invalid refresh token, confirmed against the live
        // endpoint: 401 rather than the 400 the spec assigns to invalid_grant.
        val http = HttpClient(clientReturning(401, """{"error":"token_expired"}"""))

        assertThrows(ProviderException.Unauthorized::class.java) {
            kotlinx.coroutines.runBlocking {
                http.request(
                    url = "https://auth.openai.com/oauth/token",
                    method = "POST",
                    body = HttpClient.formBody(mapOf("grant_type" to "refresh_token")),
                    retries = 0,
                    badRequestMeansExpired = true,
                )
            }
        }
    }

    @Test
    fun `a 400 anywhere else is not a dead credential`() = runTest {
        // The default. A usage endpoint that rejects a request must not cost the user their
        // session — they would be told to reconnect an account that is working fine.
        val http = HttpClient(clientReturning(400, """{"detail":"bad request"}"""))

        val thrown = assertThrows(ProviderException::class.java) {
            kotlinx.coroutines.runBlocking {
                http.request(url = "https://chatgpt.com/backend-api/wham/usage", retries = 0)
            }
        }
        assertTrue(
            "a plain 400 should stay unexplained, not become Unauthorized",
            thrown is ProviderException.Unexpected,
        )
    }
}
