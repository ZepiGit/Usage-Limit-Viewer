package com.usagelimits.widget

import androidx.compose.ui.graphics.Color
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.WindowCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ink follows the panel. A light panel with light text is a blank widget, and the old
 * fixed palette produced exactly that the moment a launcher or a user changed the panel.
 */
class WidgetStyleTest {

    @Test
    fun `the default is the app's dark panel at full opacity with light ink`() {
        val style = WidgetStyle()
        assertEquals(Color(0xFF0F0F0E), style.background.color)
        assertEquals(100, style.background.clampedOpacity)
        assertFalse(style.background.isTransparent)
        assertTrue(style.lightInk)
        assertEquals(WidgetBackground.DEFAULT_ARGB, WidgetStyle.CHOICES.first().argb)
    }

    @Test
    fun `a light panel gets dark ink and a dark panel light ink`() {
        val cream = WidgetStyle(WidgetBackground(0xFFF3F0E8.toInt(), 100))
        val navy = WidgetStyle(WidgetBackground(0xFF16213A.toInt(), 100))
        assertFalse(cream.lightInk)
        assertTrue(navy.lightInk)
        // The status words switch with the ink; the bars keep the raw accent on both.
        assertEquals(WidgetStyle.Amber, navy.textColor(Severity.MEDIUM))
        assertTrue(cream.textColor(Severity.MEDIUM) != WidgetStyle.Amber)
        val row = WidgetRow("5h limit", WindowCategory.FIVE_HOUR, 40.0, null, Severity.MEDIUM)
        assertEquals(WidgetStyle.Amber, cream.bar(row))
        assertEquals(WidgetStyle.Amber, navy.bar(row))
    }

    @Test
    fun `opacity is folded into the panel colour and clamped`() {
        val half = WidgetBackground(0xFFFFFFFF.toInt(), 50)
        assertEquals(0.5f, half.color.alpha, 0.01f)
        assertEquals(1f, half.color.red, 0.001f)
        assertEquals(100, WidgetBackground(0, 140).clampedOpacity)
        assertEquals(0, WidgetBackground(0, -5).clampedOpacity)
        assertTrue(WidgetBackground(0xFFFFFFFF.toInt(), 0).isTransparent)
    }

    @Test
    fun `auto tone is continuous as opacity crosses zero`() {
        // WGT-003. The old rule special-cased full transparency and inverted its guess there,
        // so a white panel chose dark ink at 1% opacity and light ink at 0% — a deterministic
        // black/white flip from one nudge of the opacity slider. AUTO now reads the chosen
        // panel colour at every opacity; a see-through widget whose wallpaper defeats that
        // assumption is what the explicit LIGHT/DARK overrides are for.
        val white = WidgetBackground(0xFFFFFFFF.toInt(), 100)
        assertFalse(WidgetStyle(white).lightInk)
        assertFalse(WidgetStyle(white.copy(opacityPercent = 5)).lightInk)
        assertFalse(WidgetStyle(white.copy(opacityPercent = 0)).lightInk)
        val navy = WidgetBackground(0xFF16213A.toInt(), 0)
        assertTrue(WidgetStyle(navy).lightInk)
        // The overrides are untouched, at every opacity.
        assertTrue(WidgetStyle(white.copy(opacityPercent = 0), WidgetTextTone.LIGHT).lightInk)
        assertFalse(WidgetStyle(navy, WidgetTextTone.DARK).lightInk)
        assertTrue(WidgetStyle(white, WidgetTextTone.LIGHT).lightInk)
    }

    @Test
    fun `a stale full quota is not healthy teal`() {
        // WGT-006. Data validity outranks quota decoration: the teal "untouched" state is a
        // claim about the numbers, and a stale reading is not one to be trusted.
        val full = WidgetRow("5h limit", WindowCategory.FIVE_HOUR, 100.0, null, Severity.HEALTHY)
        val style = WidgetStyle()
        assertEquals(WidgetStyle.Teal, style.bar(full))
        assertEquals(WidgetStyle.Slate, style.bar(full, Severity.STALE))
        assertEquals(style.textColor(Severity.STALE), style.barText(full, Severity.STALE))
    }

    @Test
    fun `an error full quota keeps the invalid-data treatment`() {
        val full = WidgetRow("5h limit", WindowCategory.FIVE_HOUR, 100.0, null, Severity.HEALTHY)
        val style = WidgetStyle()
        assertEquals(WidgetStyle.Red, style.bar(full, Severity.ERROR))
        assertEquals(style.textColor(Severity.ERROR), style.barText(full, Severity.ERROR))
    }

    @Test
    fun `a healthy short window does not inherit an exhausted long window's colour`() {
        // The other direction of WGT-006: validity is DATA validity only. Quota severity stays
        // per row, so an exhausted monthly window never recolours a healthy five-hour one.
        val short = WidgetRow("5h limit", WindowCategory.FIVE_HOUR, 40.0, null, Severity.MEDIUM)
        val style = WidgetStyle()
        assertEquals(WidgetStyle.Amber, style.bar(short))
        assertEquals(WidgetStyle.Amber, style.bar(short, validity = null))
        // And a depleted long horizon on the same account does not make the data untrusted.
        val exhaustedAccount = WidgetAccount("a", "A", null, emptyList(), Severity.EXHAUSTED)
        assertNull(exhaustedAccount.dataValidity)
    }

    @Test
    fun `data validity is staleness, failure or reconnection — nothing else`() {
        assertNull(WidgetAccount("a", "A", null, emptyList(), Severity.HEALTHY).dataValidity)
        assertEquals(Severity.STALE, WidgetAccount("a", "A", null, emptyList(), Severity.STALE).dataValidity)
        assertEquals(Severity.ERROR, WidgetAccount("a", "A", null, emptyList(), Severity.ERROR).dataValidity)
        assertEquals(
            Severity.ERROR,
            WidgetAccount("a", "A", null, emptyList(), Severity.EXHAUSTED, requiresReauthentication = true).dataValidity,
        )
    }

    @Test
    fun `stored rows map onto a style and the old flag still wins`() {
        val fresh = WidgetStyle.fromStored(null, null, transparent = false, textTone = null)
        assertEquals(WidgetStyle(), fresh)

        val legacy = WidgetStyle.fromStored(null, 100, transparent = true, textTone = "nonsense")
        assertTrue(legacy.background.isTransparent)
        assertEquals(WidgetTextTone.AUTO, legacy.textTone)

        val chosen = WidgetStyle.fromStored(0xFF16213A.toInt(), 70, transparent = false, textTone = "DARK")
        assertEquals(70, chosen.background.clampedOpacity)
        assertEquals(WidgetTextTone.DARK, chosen.textTone)
        assertFalse(chosen.lightInk)
    }

    @Test
    fun `the widget view derives transparency from its style`() {
        val view = WidgetUpdater.WidgetView(WidgetSnapshot.Empty, WidgetStyle(WidgetBackground(0, 0)))
        assertTrue(view.transparent)
        assertFalse(WidgetUpdater.WidgetView(WidgetSnapshot.Empty).transparent)
    }
}
