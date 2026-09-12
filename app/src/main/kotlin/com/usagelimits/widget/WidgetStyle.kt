package com.usagelimits.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.usagelimits.core.model.Severity

/**
 * How one placed widget is painted: the panel behind it and the ink on top.
 *
 * Per widget rather than per app, like its scope. The panel colour and its opacity are the
 * user's choice from the configuration screen; the ink is DERIVED from that choice, so a
 * light panel gets dark text and a dark panel light text without a second decision. The
 * derivation lives here, in plain functions over ints, so it is unit-testable and so every
 * widget colours the same choice identically.
 */
data class WidgetBackground(
    /** Opaque ARGB of the panel colour; the opacity is applied separately. */
    val argb: Int = DEFAULT_ARGB,
    /** 0 lets the wallpaper through entirely; 100 is a solid panel. */
    val opacityPercent: Int = 100,
) {
    val clampedOpacity: Int get() = opacityPercent.coerceIn(0, 100)

    /** The panel colour as drawn, opacity folded into its alpha. */
    val color: Color get() = Color(argb).copy(alpha = clampedOpacity / 100f)

    val isTransparent: Boolean get() = clampedOpacity == 0

    /** Whether the chosen panel colour reads as light. Ignores opacity on purpose. */
    val isLightColor: Boolean get() = Color(argb).luminance() > 0.4f

    companion object {
        /** The app's own near-black, `UsageColors.Background`. Signed, as SQLite stores it. */
        const val DEFAULT_ARGB: Int = -15790322 // 0xFF0F0F0E
        const val DEFAULT_OPACITY: Int = 100
    }
}

/** Which ink a widget uses. `AUTO` follows the panel colour; the others override it. */
enum class WidgetTextTone {
    AUTO, LIGHT, DARK;

    companion object {
        fun fromName(value: String?): WidgetTextTone = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/** A choosable panel colour, with the name the configuration screen shows for it. */
data class WidgetColorChoice(val id: String, val label: String, val argb: Int)

/**
 * Everything the widgets need to paint one configuration: the panel, and the ink derived
 * from it.
 *
 * The ink is one of two sets. On a dark panel it is the app's own palette; on a light one it
 * is the same hues pulled down until they clear 4.5:1 against near-white. Bars and rings keep
 * the raw accents on both — a filled bar is not text — and only the words switch.
 */
data class WidgetStyle(
    val background: WidgetBackground = WidgetBackground(),
    val textTone: WidgetTextTone = WidgetTextTone.AUTO,
) {
    /** True when the ink should be dark, i.e. the panel — or the override — is light. */
    val lightInk: Boolean
        get() = when (textTone) {
            WidgetTextTone.LIGHT -> true
            WidgetTextTone.DARK -> false
            // A fully transparent widget has no panel to read against; the app's own dark
            // wallpaper default is the safer guess, and the override exists for the rest.
            WidgetTextTone.AUTO -> background.isTransparent || !background.isLightColor
        }

    val textPrimary: Color get() = if (lightInk) Color(0xFFF0EEE6) else Color(0xFF1A1918)
    val textSecondary: Color get() = if (lightInk) Color(0xFFB4B2A9) else Color(0xFF5C5A53)

    /**
     * A tile or chip on the panel: the ink at low alpha, so it lifts the panel whatever colour
     * that is and disappears gracefully when the panel does.
     */
    val card: Color get() = if (lightInk) Color(0x1FFFFFFF) else Color(0x14000000)

    /** The empty part of a bar or ring. */
    val track: Color get() = if (lightInk) Color(0x40FFFFFF) else Color(0x2E000000)

    fun accent(severity: Severity): Color = when (severity) {
        Severity.HEALTHY -> Green
        Severity.MEDIUM, Severity.LOW -> Amber
        Severity.EXHAUSTED, Severity.ERROR -> Red
        Severity.STALE -> Slate
    }

    /** [accent] for bars and dots; this for anything drawn as words. */
    fun textColor(severity: Severity): Color = when (severity) {
        Severity.HEALTHY -> if (lightInk) Green else Color(0xFF2F7A34)
        Severity.MEDIUM, Severity.LOW -> if (lightInk) Amber else Color(0xFF8A5A0A)
        Severity.EXHAUSTED, Severity.ERROR -> if (lightInk) Color(0xFFE8756B) else Color(0xFFB03A31)
        Severity.STALE -> if (lightInk) Color(0xFFA29F94) else Color(0xFF6B6960)
    }

    fun bar(row: WidgetRow): Color = when {
        row.remainingPercent != null && row.remainingPercent >= 99.5 -> Teal
        else -> accent(row.severity)
    }

    fun barText(row: WidgetRow): Color = when {
        row.remainingPercent != null && row.remainingPercent >= 99.5 -> if (lightInk) Teal else Color(0xFF247A69)
        else -> textColor(row.severity)
    }

    val trackArgb: Int get() = track.toArgb()

    companion object {
        // Mirrors UsageColors. Restated because Glance runs in the launcher's process and
        // cannot read the app's MaterialTheme; kept in one place so the palette is one edit.
        val Teal = Color(0xFF4FBFA8)
        val Green = Color(0xFF6FBF73)
        val Amber = Color(0xFFE0A33E)
        val Red = Color(0xFFD9584F)
        val Slate = Color(0xFF6B6960)

        /** The panel colours the configuration screen offers, default first. */
        val CHOICES: List<WidgetColorChoice> = listOf(
            WidgetColorChoice("charcoal", "Charcoal", WidgetBackground.DEFAULT_ARGB),
            WidgetColorChoice("black", "Black", 0xFF000000.toInt()),
            WidgetColorChoice("graphite", "Graphite", 0xFF2A2927.toInt()),
            WidgetColorChoice("navy", "Navy", 0xFF16213A.toInt()),
            WidgetColorChoice("plum", "Plum", 0xFF2B1A34.toInt()),
            WidgetColorChoice("forest", "Forest", 0xFF13281C.toInt()),
            WidgetColorChoice("terracotta", "Terracotta", 0xFF6E3A2B.toInt()),
            WidgetColorChoice("cream", "Cream", 0xFFF3F0E8.toInt()),
            WidgetColorChoice("light", "Light grey", 0xFFE6E5E0.toInt()),
            WidgetColorChoice("white", "White", 0xFFFFFFFF.toInt()),
        )

        fun fromStored(argb: Int?, opacityPercent: Int?, transparent: Boolean, textTone: String?): WidgetStyle =
            WidgetStyle(
                background = WidgetBackground(
                    argb = argb ?: WidgetBackground.DEFAULT_ARGB,
                    // The pre-9 flag, kept for rows the migration wrote and as a belt to the
                    // opacity's braces: a widget saved transparent stays transparent.
                    opacityPercent = if (transparent) 0 else (opacityPercent ?: WidgetBackground.DEFAULT_OPACITY),
                ),
                textTone = WidgetTextTone.fromName(textTone),
            )
    }
}
