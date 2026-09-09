package com.usagelimits.ui.theme

import androidx.compose.ui.graphics.Color
import com.usagelimits.core.model.Severity
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Every status word must clear WCAG 1.4.3 AA on the ground it is drawn on.
 *
 * The pill label is `labelMedium` — 12sp — so it is normal text and needs 4.5:1, not the 3:1
 * large-text allowance. Drawing it in the same hue as its own tinted container left
 * "Exhausted"/"Error" at 3.88:1 and "Stale" at 2.78:1, and "Stale" is the very first thing a
 * freshly added account shows. The maths here is pure Kotlin, so this stays a plain JVM test.
 */
class StatusContrastTest {

    private companion object {
        const val AA_NORMAL_TEXT = 4.5
    }

    /** WCAG 2.x relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** Source-over composite, which is what a translucent chip background actually renders as. */
    private fun over(top: Color, bottom: Color): Color {
        val alpha = top.alpha
        return Color(
            red = top.red * alpha + bottom.red * (1 - alpha),
            green = top.green * alpha + bottom.green * (1 - alpha),
            blue = top.blue * alpha + bottom.blue * (1 - alpha),
        )
    }

    @Test
    fun `every StatusPill label clears AA on its own container`() {
        // The pill sits on a UsageCard (Surface) on Overview and Accounts, and directly on
        // Background on Account detail. Surface is the lighter of the two, so it is the
        // harder case for light text — check both anyway.
        for (ground in listOf(UsageColors.Surface, UsageColors.Background)) {
            for (severity in Severity.entries) {
                val chip = over(SeverityPalette.container(severity), ground)
                val ratio = contrast(SeverityPalette.textColor(severity), chip)
                assertTrue(
                    "$severity label measures %.2f:1 on its container over $ground".format(ratio),
                    ratio >= AA_NORMAL_TEXT,
                )
            }
        }
    }

    @Test
    fun `the Resets row status word clears AA on the card it sits on`() {
        for (severity in Severity.entries) {
            val ratio = contrast(SeverityPalette.textColor(severity), UsageColors.Surface)
            assertTrue(
                "$severity measures %.2f:1 on Surface".format(ratio),
                ratio >= AA_NORMAL_TEXT,
            )
        }
    }

    /**
     * The widget palette is a deliberate copy of the app's (Glance cannot read the app theme),
     * so it has to be held to the same floor or the two drift apart again. The values are
     * restated here rather than read from the private `W` object.
     */
    @Test
    fun `the widget status word and percentage clear AA on the widget card`() {
        val widgetCard = Color(0xFF242322)
        val widgetTextTones = mapOf(
            "healthy" to Color(0xFF6FBF73),
            "moderate/low" to Color(0xFFE0A33E),
            "untouched" to Color(0xFF4FBFA8),
            "exhausted/error" to Color(0xFFE8756B),
            "stale" to Color(0xFFA29F94),
        )

        for ((name, tone) in widgetTextTones) {
            val ratio = contrast(tone, widgetCard)
            assertTrue(
                "widget $name text measures %.2f:1 on the card".format(ratio),
                ratio >= AA_NORMAL_TEXT,
            )
        }
    }

    @Test
    fun `the lifted tones are only used where the raw accents actually failed`() {
        // Guards against someone "simplifying" textColor back to accent: these two are the
        // measurements that made the lift necessary in the first place.
        val redChip = over(UsageColors.RedSurface, UsageColors.Surface)
        val slateChip = over(UsageColors.SlateSurface, UsageColors.Surface)

        assertTrue(contrast(UsageColors.Red, redChip) < AA_NORMAL_TEXT)
        assertTrue(contrast(UsageColors.Slate, slateChip) < AA_NORMAL_TEXT)
    }
}
