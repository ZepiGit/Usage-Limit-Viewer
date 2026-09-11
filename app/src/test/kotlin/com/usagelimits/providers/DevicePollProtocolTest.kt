package com.usagelimits.providers

import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.kimi.KimiProvider
import com.usagelimits.providers.xai.XaiProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Entirely synthetic replies; no provider connection is made. */
class DevicePollProtocolTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `HTTP errors end both device grants and slow down persists`() = runTest {
        for (kimi in listOf(true, false)) {
            for (error in listOf("access_denied", "expired_token", "invalid_client", "slow_down")) {
                val replies = if (error == "slow_down") listOf(
                    400 to """{"error":"slow_down"}""",
                    400 to """{"error":"authorization_pending"}""",
                    200 to """{"access_token":"synthetic-access"}""",
                ) else listOf(
                    400 to """{"error":"$error"}""",
                    200 to """{"access_token":"synthetic-unexpected"}""",
                )
                var requests = 0
                val http = HttpClient(OkHttpClient.Builder().addInterceptor { chain ->
                    val (code, body) = replies[requests++]
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(code).message("synthetic").body(body.toResponseBody(HttpClient.JSON_MEDIA_TYPE)).build()
                }.build())
                val provider: UsageProvider = if (kimi) KimiProvider(http, nowMs = { 0 }, version = "test")
                    else XaiProvider(http, nowMs = { 0 })
                val challenge = LoginChallenge.DeviceCode(
                    userCode = if (kimi) "TEST|synthetic-device" else "TEST|synthetic-device|https://auth.x.ai/token",
                    verificationUri = "https://example.invalid", verificationUriComplete = null,
                    expiresAt = 60_000, pollIntervalMs = 5_000,
                )
                val start = testScheduler.currentTime
                val result = runCatching { provider.completeLogin(challenge) }
                if (error == "slow_down") {
                    assertEquals("synthetic-access", result.getOrThrow().accessToken)
                    assertEquals("both waits use the increased interval", 20_000L, testScheduler.currentTime - start)
                    assertEquals(3, requests)
                } else {
                    assertTrue("$kimi / $error must fail", result.isFailure)
                    if (error != "invalid_client") assertTrue(result.exceptionOrNull() is ProviderException.LoginCancelled)
                    assertEquals(1, requests)
                }
            }
        }
    }
}
