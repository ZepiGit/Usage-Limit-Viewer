package com.usagelimits.providers.xai

import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * All fixtures are synthetic: invented amounts and periods, no captured payload and no
 * credential material of any kind. Amounts are integer cents, as the endpoint reports them.
 */
class XaiBillingParserTest {

    private val now = 1_757_400_000_000L

    /** The weekly credit view: the one xAI endpoint that reports a ready-made percentage. */
    private val creditsPayload = """
        {
          "creditUsagePercent": 34.0,
          "currentPeriod": {
            "start": "2026-09-02T00:00:00Z",
            "end": "2026-09-09T00:00:00Z",
            "type": "weekly"
          }
        }
    """.trimIndent()

    /** The monthly spend view. 4200 of 10000 cents included, on-demand untouched. */
    private val billingPayload = """
        {
          "monthlyLimit": 10000,
          "used": 4200,
          "onDemandCap": 5000,
          "onDemandUsed": 0,
          "billingPeriodStart": "2026-09-01T00:00:00Z",
          "billingPeriodEnd": "2026-10-01T00:00:00Z"
        }
    """.trimIndent()

    /** Spend past the allowance, with no explicit on-demand figure to read. */
    private val overspentPayload = """
        {
          "monthlyLimit": 10000,
          "used": 14000,
          "onDemandCap": 5000,
          "billingPeriodStart": "2026-09-01T00:00:00Z",
          "billingPeriodEnd": "2026-10-01T00:00:00Z"
        }
    """.trimIndent()

    private fun credits(raw: String) =
        XaiBillingParser.parseCredits(JsonSupport.parseObject(raw), now)

    private fun billing(raw: String) =
        XaiBillingParser.parseBilling(JsonSupport.parseObject(raw), now)

    @Test
    fun `credit view yields one weekly window with a derived period`() {
        val windows = credits(creditsPayload)

        assertEquals(1, windows.size)
        val window = windows.single()
        assertEquals("xai-credits", window.id)
        assertEquals("Weekly credits", window.label)
        assertEquals(34.0, window.usedPercent!!, 1e-9)
        assertEquals(66.0, window.remainingPercent!!, 1e-9)
        assertFalse(window.exhausted)

        // Derived from the period stamps, not assumed from the "weekly" label.
        assertEquals(604_800L, window.periodSeconds)
        assertEquals(WindowCategory.WEEKLY, window.category)
        assertEquals(Instant.parse("2026-09-09T00:00:00Z").toEpochMilli(), window.resetAt)
    }

    @Test
    fun `a credit period with no usable stamps stays uncategorised rather than guessed`() {
        val windows = credits("""{ "creditUsagePercent": 12.5 }""")

        val window = windows.single()
        assertEquals(12.5, window.usedPercent!!, 1e-9)
        assertNull(window.periodSeconds)
        assertNull(window.resetAt)
        assertEquals(WindowCategory.OTHER, window.category)
    }

    @Test
    fun `billing view splits included allowance from on-demand`() {
        val windows = billing(billingPayload)

        assertEquals(listOf("xai-monthly", "xai-on-demand"), windows.map { it.id })

        val included = windows.first()
        assertEquals("Monthly included", included.label)
        // 4200 of 10000 cents. The unit cancels out; no money is ever displayed.
        assertEquals(42.0, included.usedPercent!!, 1e-9)
        assertEquals(WindowCategory.MONTHLY, included.category)
        assertEquals(2_592_000L, included.periodSeconds)
        assertFalse(included.exhausted)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), included.resetAt)

        val onDemand = windows[1]
        assertEquals("On-demand", onDemand.label)
        assertEquals(0.0, onDemand.usedPercent!!, 1e-9)
        assertEquals(WindowCategory.MONTHLY, onDemand.category)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), onDemand.resetAt)
    }

    @Test
    fun `merge keeps credits first and then billing`() {
        val merged = XaiBillingParser.merge(credits(creditsPayload), billing(billingPayload))

        assertEquals(listOf("xai-credits", "xai-monthly", "xai-on-demand"), merged.map { it.id })
    }

    @Test
    fun `merge drops a billing window whose id the credit view already reported`() {
        val duplicated = credits(creditsPayload)

        val merged = XaiBillingParser.merge(duplicated, duplicated + billing(billingPayload))

        assertEquals(listOf("xai-credits", "xai-monthly", "xai-on-demand"), merged.map { it.id })
        // First occurrence wins, so the credit view stays authoritative.
        assertEquals(34.0, merged.first().usedPercent!!, 1e-9)
    }

    @Test
    fun `on-demand is omitted when the cap is zero or absent`() {
        val zeroCap = billing(
            """{ "monthlyLimit": 10000, "used": 4200, "onDemandCap": 0 }""",
        )
        assertEquals(listOf("xai-monthly"), zeroCap.map { it.id })

        val noCap = billing("""{ "monthlyLimit": 10000, "used": 4200 }""")
        assertEquals(listOf("xai-monthly"), noCap.map { it.id })
    }

    @Test
    fun `on-demand use is derived from the overage when it is not reported`() {
        val onDemand = billing(overspentPayload).single { it.id == "xai-on-demand" }

        // 14000 spent against a 10000 allowance is 4000 of the 5000 on-demand cap.
        assertEquals(80.0, onDemand.usedPercent!!, 1e-9)
        assertFalse(onDemand.exhausted)
    }

    @Test
    fun `overspending clamps the included window at one hundred percent`() {
        val included = billing(overspentPayload).single { it.id == "xai-monthly" }

        assertEquals(100.0, included.usedPercent!!, 1e-9)
        assertEquals(0.0, included.remainingPercent!!, 1e-9)
        assertTrue(included.exhausted)
    }

    @Test
    fun `snake_case payloads parse identically to camelCase ones`() {
        val snakeCredits = credits(
            """
            {
              "credit_usage_percent": 34.0,
              "current_period": { "start": "2026-09-02T00:00:00Z", "end": "2026-09-09T00:00:00Z" }
            }
            """.trimIndent(),
        )
        assertEquals(34.0, snakeCredits.single().usedPercent!!, 1e-9)
        assertEquals(604_800L, snakeCredits.single().periodSeconds)

        val snakeBilling = billing(
            """
            {
              "monthly_limit": 10000,
              "used": 4200,
              "on_demand_cap": 5000,
              "on_demand_used": 2500,
              "billing_period_start": "2026-09-01T00:00:00Z",
              "billing_period_end": "2026-10-01T00:00:00Z"
            }
            """.trimIndent(),
        )
        assertEquals(listOf("xai-monthly", "xai-on-demand"), snakeBilling.map { it.id })
        assertEquals(42.0, snakeBilling.first().usedPercent!!, 1e-9)
        assertEquals(50.0, snakeBilling[1].usedPercent!!, 1e-9)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), snakeBilling.first().resetAt)
    }

    @Test
    fun `a zero monthly limit reports an unknown percentage instead of dividing by zero`() {
        val included = billing("""{ "monthlyLimit": 0, "used": 4200 }""").single()

        assertEquals("xai-monthly", included.id)
        assertNull(included.usedPercent)
        assertNull(included.remainingPercent)
        assertFalse(included.exhausted)
    }

    @Test
    fun `empty payloads yield no windows and merge to nothing`() {
        val emptyCredits = credits("{}")
        val emptyBilling = billing("{}")

        assertTrue(emptyCredits.isEmpty())
        assertTrue(emptyBilling.isEmpty())
        assertTrue(XaiBillingParser.merge(emptyCredits, emptyBilling).isEmpty())
    }

    // --- endpoint validation -------------------------------------------------------------
    //
    // The rule that makes runtime OIDC discovery safe: a tampered discovery document must not
    // be able to point token traffic anywhere but xAI, over TLS.

    private val provider = XaiProvider(HttpClient())

    @Test
    fun `validateEndpoint accepts https endpoints on x ai and its subdomains`() {
        assertEquals("https://x.ai", provider.validateEndpoint("https://x.ai", "token_endpoint"))
        assertEquals(
            "https://auth.x.ai/token",
            provider.validateEndpoint("https://auth.x.ai/token", "token_endpoint"),
        )
    }

    @Test
    fun `validateEndpoint rejects plaintext and foreign hosts`() {
        for (rejected in listOf("http://x.ai", "https://evil.com", "https://notx.ai")) {
            assertThrows(ProviderException.Unexpected::class.java) {
                provider.validateEndpoint(rejected, "token_endpoint")
            }
        }
    }

    @Test
    fun `validateEndpoint names the field it rejected`() {
        val failure = assertThrows(ProviderException.Unexpected::class.java) {
            provider.validateEndpoint("https://evil.com", "device_authorization_endpoint")
        }

        assertTrue(failure.message!!.contains("device_authorization_endpoint"))
    }

@Test
    fun `the production billing payloads use enum period types and cent objects`() {
        // Pins real production payload shapes captured from xAI against enum miscategorisation:
        // the type is USAGE_PERIOD_TYPE_WEEKLY, never bare "weekly"; matching that literal exactly
        // misclassifies it. The timestamp-free variant prevents a measured span from rescuing that defect.
        // These real production shapes also pin mishandled cent objects, spurious zero-cap windows
        // and rejection of the unknown history and productUsage arrays.
        val creditsPayload = JsonSupport.parseObject(
            """
            { "config": {
                "currentPeriod": { "type": "USAGE_PERIOD_TYPE_WEEKLY",
                                   "start": "2026-09-03T18:32:26.743829+00:00",
                                   "end": "2026-09-10T18:32:26.743829+00:00" },
                "creditUsagePercent": 64.0,
                "onDemandCap": { "val": 0 }, "onDemandUsed": { "val": 0 },
                "isUnifiedBillingUser": true, "prepaidBalance": { "val": 659 },
                "productUsage": [ { "product": "GrokBuild", "usagePercent": 56.0 },
                                  { "product": "GrokVoice" } ],
                "billingPeriodEnd": "2026-09-10T18:32:26.743829+00:00" } }
            """.trimIndent()
        )
        val billingPayload = JsonSupport.parseObject(
            """
            { "config": { "monthlyLimit": { "val": 1000 }, "used": { "val": 341 },
                "onDemandCap": { "val": 0 },
                "billingPeriodStart": "2026-09-01T00:00:00+00:00",
                "billingPeriodEnd": "2026-10-01T00:00:00+00:00",
                "history": [ { "billingCycle": { "year": 2026, "month": 8 },
                               "includedUsed": { "val": 0 }, "totalUsed": { "val": 0 } } ] } }
            """.trimIndent()
        )
        val creditsWithoutPeriodTimestamps = JsonSupport.parseObject(
            """
            { "config": {
                "currentPeriod": { "type": "USAGE_PERIOD_TYPE_WEEKLY" },
                "creditUsagePercent": 64.0,
                "onDemandCap": { "val": 0 }, "onDemandUsed": { "val": 0 },
                "isUnifiedBillingUser": true, "prepaidBalance": { "val": 659 },
                "productUsage": [ { "product": "GrokBuild", "usagePercent": 56.0 },
                                  { "product": "GrokVoice" } ],
                "billingPeriodEnd": "2026-09-10T18:32:26.743829+00:00" } }
            """.trimIndent()
        )

        val creditsWindows = XaiBillingParser.parseCredits(creditsPayload, now)
        assertEquals(1, creditsWindows.size)
        assertEquals(64.0, creditsWindows.single().usedPercent!!, 0.001)
        assertEquals(WindowCategory.WEEKLY, creditsWindows.single().category)
        assertFalse(creditsWindows.any { it.id == "xai-on-demand" })

        val enumOnlyWindows = XaiBillingParser.parseCredits(creditsWithoutPeriodTimestamps, now)
        assertEquals(1, enumOnlyWindows.size)
        assertEquals(WindowCategory.WEEKLY, enumOnlyWindows.single().category)
        assertEquals(64.0, enumOnlyWindows.single().usedPercent!!, 0.001)
        assertFalse(enumOnlyWindows.any { it.id == "xai-on-demand" })

        val billingWindows = XaiBillingParser.parseBilling(billingPayload, now)
        val monthly = billingWindows.single { it.id == "xai-monthly" }
        assertEquals(34.1, monthly.usedPercent!!, 0.001)
        assertFalse(billingWindows.any { it.id == "xai-on-demand" })
    }
}
