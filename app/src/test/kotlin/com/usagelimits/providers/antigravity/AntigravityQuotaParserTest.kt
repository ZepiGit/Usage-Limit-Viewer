package com.usagelimits.providers.antigravity

import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All fixtures are synthetic: invented bucket ids and quota-group names, no captured payload
 * and no credential material of any kind.
 */
class AntigravityQuotaParserTest {

    private val now = 1_757_400_000_000L

    /** camelCase payload with two groups, each metering a 5-hour and a weekly bucket. */
    private val twoGroups = """
        {
          "groups": [
            {
              "displayName": "Gemini Pro",
              "description": "Gemini Flash, Gemini Pro",
              "buckets": [
                { "bucketId": "gemini-5h", "displayName": "5h limit", "window": "5h",
                  "remainingFraction": 0.53, "resetTime": "2026-09-09T17:30:00Z" },
                { "bucketId": "gemini-weekly", "displayName": "Weekly", "window": "weekly",
                  "remainingFraction": 0.56, "resetTime": "2026-09-10T20:00:00Z" }
              ]
            },
            {
              "displayName": "Gemini Ultra",
              "buckets": [
                { "bucketId": "ultra-5h", "displayName": "5h limit", "window": "5h",
                  "remainingFraction": 1.0, "resetTime": "2026-09-09T18:00:00Z" },
                { "bucketId": "ultra-weekly", "displayName": "Weekly", "window": "weekly",
                  "remainingFraction": 0.0, "resetTime": "2026-09-12T00:00:00Z" }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses both groups and converts remaining fraction to consumed percent`() {
        val windows = AntigravityQuotaParser.parse(JsonSupport.parseObject(twoGroups), now)

        assertEquals(4, windows.size)
        assertEquals(
            listOf("gemini-5h", "gemini-weekly", "ultra-5h", "ultra-weekly"),
            windows.map { it.id },
        )

        val fiveHour = windows.first()
        assertEquals("5h limit", fiveHour.label)
        assertEquals(WindowCategory.FIVE_HOUR, fiveHour.category)
        assertEquals(18_000L, fiveHour.periodSeconds)
        // 0.53 remaining is 47 % consumed.
        assertEquals(47.0, fiveHour.usedPercent!!, 1e-9)
        assertEquals(53.0, fiveHour.remainingPercent!!, 1e-9)
        assertFalse(fiveHour.exhausted)
        assertNotNull(fiveHour.resetAt)

        val weekly = windows[1]
        assertEquals(WindowCategory.WEEKLY, weekly.category)
        assertEquals(604_800L, weekly.periodSeconds)
        assertEquals(44.0, weekly.usedPercent!!, 1e-9)
    }

    @Test
    fun `a fully drained bucket is exhausted and an untouched one is not`() {
        val windows = AntigravityQuotaParser.parse(JsonSupport.parseObject(twoGroups), now)
            .associateBy { it.id }

        assertEquals(0.0, windows.getValue("ultra-5h").usedPercent!!, 1e-9)
        assertFalse(windows.getValue("ultra-5h").exhausted)

        assertEquals(100.0, windows.getValue("ultra-weekly").usedPercent!!, 1e-9)
        assertTrue(windows.getValue("ultra-weekly").exhausted)
    }

    @Test
    fun `every window carries its group display name`() {
        val windows = AntigravityQuotaParser.parse(JsonSupport.parseObject(twoGroups), now)

        assertTrue(windows.all { it.group != null })
        assertEquals(
            listOf("Gemini Pro", "Gemini Pro", "Gemini Ultra", "Gemini Ultra"),
            windows.map { it.group },
        )
    }

    /** Upstream serves both spellings depending on the host; they must parse identically. */
    @Test
    fun `snake_case payload parses identically to camelCase`() {
        val snake = """
            {
              "groups": [
                {
                  "display_name": "Gemini Pro",
                  "description": "Gemini Flash, Gemini Pro",
                  "buckets": [
                    { "bucket_id": "gemini-5h", "display_name": "5h limit", "window": "5h",
                      "remaining_fraction": 0.53, "reset_time": "2026-09-09T17:30:00Z" },
                    { "bucket_id": "gemini-weekly", "display_name": "Weekly", "window": "weekly",
                      "remaining_fraction": 0.56, "reset_time": "2026-09-10T20:00:00Z" }
                  ]
                },
                {
                  "display_name": "Gemini Ultra",
                  "buckets": [
                    { "bucket_id": "ultra-5h", "display_name": "5h limit", "window": "5h",
                      "remaining_fraction": 1.0, "reset_time": "2026-09-09T18:00:00Z" },
                    { "bucket_id": "ultra-weekly", "display_name": "Weekly", "window": "weekly",
                      "remaining_fraction": 0.0, "reset_time": "2026-09-12T00:00:00Z" }
                  ]
                }
              ]
            }
        """.trimIndent()

        assertEquals(
            AntigravityQuotaParser.parse(JsonSupport.parseObject(twoGroups), now),
            AntigravityQuotaParser.parse(JsonSupport.parseObject(snake), now),
        )
    }

    @Test
    fun `a bucket without a remaining fraction is skipped but its siblings survive`() {
        val payload = """
            {
              "groups": [
                {
                  "displayName": "Gemini Pro",
                  "buckets": [
                    { "bucketId": "gemini-5h", "displayName": "5h limit", "window": "5h",
                      "remainingFraction": 0.4 },
                    { "bucketId": "gemini-weekly", "displayName": "Weekly", "window": "weekly",
                      "resetTime": "2026-09-10T20:00:00Z" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val windows = AntigravityQuotaParser.parse(JsonSupport.parseObject(payload), now)

        assertEquals(listOf("gemini-5h"), windows.map { it.id })
        assertEquals(60.0, windows.single().usedPercent!!, 1e-9)
    }

    @Test
    fun `a group whose buckets all drop out disappears entirely`() {
        val payload = """
            {
              "groups": [
                { "displayName": "Unavailable", "buckets": [
                    { "bucketId": "nothing-5h", "window": "5h" },
                    { "bucketId": "nothing-weekly", "window": "weekly" }
                ] },
                { "displayName": "Gemini Pro", "buckets": [
                    { "bucketId": "gemini-5h", "displayName": "5h limit", "window": "5h",
                      "remainingFraction": 0.25 }
                ] }
              ]
            }
        """.trimIndent()

        val windows = AntigravityQuotaParser.parse(JsonSupport.parseObject(payload), now)

        assertEquals(1, windows.size)
        assertEquals("Gemini Pro", windows.single().group)
        assertTrue(windows.none { it.group == "Unavailable" })
    }

    @Test
    fun `buckets sort five hour first then weekly then the rest alphabetically`() {
        val payload = """
            {
              "groups": [
                {
                  "displayName": "Gemini Pro",
                  "buckets": [
                    { "bucketId": "zulu", "displayName": "Zulu window", "window": "monthly",
                      "remainingFraction": 0.9 },
                    { "bucketId": "weekly", "displayName": "Weekly", "window": "week",
                      "remainingFraction": 0.8 },
                    { "bucketId": "alpha", "displayName": "Alpha window", "window": "burst",
                      "remainingFraction": 0.7 },
                    { "bucketId": "short", "displayName": "5h limit", "window": "FIVE_HOUR",
                      "remainingFraction": 0.6 }
                  ]
                }
              ]
            }
        """.trimIndent()

        val windows = AntigravityQuotaParser.parse(JsonSupport.parseObject(payload), now)

        assertEquals(listOf("short", "weekly", "alpha", "zulu"), windows.map { it.id })
        // "FIVE_HOUR" and "week" are aliases; matching is case-insensitive after trimming.
        assertEquals(WindowCategory.FIVE_HOUR, windows[0].category)
        assertEquals(WindowCategory.WEEKLY, windows[1].category)
        // An unrecognised window still renders, just without a declared duration.
        assertEquals(WindowCategory.OTHER, windows[2].category)
        assertNull(windows[2].periodSeconds)
    }

    @Test
    fun `out of range fractions are clamped into zero to one`() {
        val payload = """
            {
              "groups": [
                {
                  "displayName": "Gemini Pro",
                  "buckets": [
                    { "bucketId": "over", "displayName": "Over", "window": "5h",
                      "remainingFraction": 1.4 },
                    { "bucketId": "under", "displayName": "Under", "window": "weekly",
                      "remainingFraction": -0.2 }
                  ]
                }
              ]
            }
        """.trimIndent()

        val windows = AntigravityQuotaParser.parse(JsonSupport.parseObject(payload), now)
            .associateBy { it.id }

        assertEquals(0.0, windows.getValue("over").usedPercent!!, 1e-9)
        assertFalse(windows.getValue("over").exhausted)

        assertEquals(100.0, windows.getValue("under").usedPercent!!, 1e-9)
        assertTrue(windows.getValue("under").exhausted)
    }

    @Test
    fun `a bucket without an id falls back to the group slug and window`() {
        val payload = """
            {
              "groups": [
                { "displayName": "Gemini Pro / Flash", "buckets": [
                    { "window": "5h", "remainingFraction": 0.5 },
                    { "remainingFraction": 0.5 }
                ] }
              ]
            }
        """.trimIndent()

        val windows = AntigravityQuotaParser.parse(JsonSupport.parseObject(payload), now)

        assertEquals(listOf("gemini-pro-flash-5h", "gemini-pro-flash-1"), windows.map { it.id })
        // With no display name the id doubles as the label so the row is still identifiable.
        assertEquals("gemini-pro-flash-5h", windows.first().label)
    }

    @Test
    fun `an empty or unrecognised payload yields no windows`() {
        assertTrue(AntigravityQuotaParser.parse(JsonSupport.parseObject("{}"), now).isEmpty())
        assertTrue(
            AntigravityQuotaParser.parse(
                JsonSupport.parseObject("""{ "groups": "unexpected" }"""),
                now,
            ).isEmpty(),
        )
    }
}
