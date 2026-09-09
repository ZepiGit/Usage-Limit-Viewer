package com.usagelimits.providers

import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.providers.codex.CodexUsageParser
import com.usagelimits.providers.xai.XaiBillingParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for three payload shapes the parsers originally got wrong.
 *
 * Each was found by comparing against the reference implementations rather than by a failing
 * test — the original fixtures were hand-written in the shape the code expected, so they
 * agreed with the bug. These fixtures follow the upstream types instead.
 *
 * All values are synthetic.
 */
class UpstreamShapeRegressionTest {

    private val now = 1_757_000_000_000L

    // --- xAI: payloads are wrapped in `config`, money can be {"val": n} -------------------

    @Test
    fun `credits payload wrapped in config is parsed`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "config": {
                "creditUsagePercent": 34.0,
                "currentPeriod": {
                  "type": "weekly",
                  "start": "2026-09-01T00:00:00Z",
                  "end": "2026-09-08T00:00:00Z"
                }
              }
            }
            """.trimIndent(),
        )

        val windows = XaiBillingParser.parseCredits(payload, now)

        assertEquals(1, windows.size)
        assertEquals(34.0, windows[0].usedPercent!!, 0.001)
        // A seven-day span must classify as weekly, not OTHER.
        assertEquals(WindowCategory.WEEKLY, windows[0].category)
    }

    @Test
    fun `billing cents wrapped as val objects are parsed`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "config": {
                "monthlyLimit": { "val": 10000 },
                "used": { "val": 4200 },
                "onDemandCap": { "val": 5000 },
                "onDemandUsed": { "val": 1000 },
                "billingPeriodEnd": "2026-10-01T00:00:00Z"
              }
            }
            """.trimIndent(),
        )

        val windows = XaiBillingParser.parseBilling(payload, now)

        val monthly = windows.first { it.id == "xai-monthly" }
        assertEquals(42.0, monthly.usedPercent!!, 0.001)

        val onDemand = windows.first { it.id == "xai-on-demand" }
        assertEquals(20.0, onDemand.usedPercent!!, 0.001)
        assertNotNull(monthly.resetAt)
    }

    @Test
    fun `flat billing payload without the config envelope still parses`() {
        // Older responses were flat; the envelope is accepted, not required.
        val payload = JsonSupport.parseObject(
            """{ "monthlyLimit": 10000, "used": 2500 }""",
        )

        val windows = XaiBillingParser.parseBilling(payload, now)

        assertEquals(25.0, windows.first { it.id == "xai-monthly" }.usedPercent!!, 0.001)
    }

    // --- Codex: additional_rate_limits nest their windows under `rate_limit` --------------

    @Test
    fun `additional rate limit windows nested under rate_limit are parsed`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "additional_rate_limits": [
                {
                  "metered_feature": "sora",
                  "rate_limit": {
                    "limit_reached": false,
                    "primary_window": { "used_percent": 30.0, "limit_window_seconds": 18000 },
                    "secondary_window": { "used_percent": 60.0, "limit_window_seconds": 604800 }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val windows = CodexUsageParser.parse(payload, now)

        assertEquals(2, windows.size)
        // metered_feature names the group when limit_name is absent.
        assertTrue(windows.all { it.group == "sora" })
        assertEquals(30.0, windows.first { it.category == WindowCategory.FIVE_HOUR }.usedPercent!!, 0.001)
        assertEquals(60.0, windows.first { it.category == WindowCategory.WEEKLY }.usedPercent!!, 0.001)
    }

    @Test
    fun `exhausted additional limit is not hidden from the account severity`() {
        // The severity of a snapshot is the worst of its windows, so an additional limit that
        // fails to parse would let a spent account read as healthy.
        val payload = JsonSupport.parseObject(
            """
            {
              "additional_rate_limits": [
                {
                  "limit_name": "deep research",
                  "rate_limit": {
                    "limit_reached": true,
                    "primary_window": { "limit_window_seconds": 18000 }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val windows = CodexUsageParser.parse(payload, now)

        assertEquals(1, windows.size)
        assertTrue(windows[0].exhausted)
        assertEquals(100.0, windows[0].usedPercent!!, 0.001)
    }

    @Test
    fun `flat additional rate limit entry still parses`() {
        val payload = JsonSupport.parseObject(
            """
            {
              "additional_rate_limits": [
                {
                  "limit_name": "legacy",
                  "primary_window": { "used_percent": 10.0, "limit_window_seconds": 18000 }
                }
              ]
            }
            """.trimIndent(),
        )

        val windows = CodexUsageParser.parse(payload, now)

        assertEquals(1, windows.size)
        assertEquals(10.0, windows[0].usedPercent!!, 0.001)
    }
}
