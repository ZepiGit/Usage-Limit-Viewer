package com.usagelimits.widget

import androidx.compose.ui.graphics.Color
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.WindowCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a see-through widget keeps light ink unless told otherwise`() {
        // No panel to read against, so the guess is the app's own dark wallpaper default…
        val clear = WidgetStyle(WidgetBackground(0xFFFFFFFF.toInt(), 0))
        assertTrue(clear.lightInk)
        // …and the override exists for a wallpaper that guess gets wrong.
        assertFalse(clear.copy(textTone = WidgetTextTone.DARK).lightInk)
        assertTrue(WidgetStyle(WidgetBackground(0xFFFFFFFF.toInt(), 100), WidgetTextTone.LIGHT).lightInk)
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
