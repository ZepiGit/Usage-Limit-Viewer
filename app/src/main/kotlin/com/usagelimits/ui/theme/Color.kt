package com.usagelimits.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette, following Claude Code's visual language.
 *
 * Two decisions drive everything here:
 *
 *  - **Warm neutrals, not blue-black.** Every surface and text tone sits on a warm grey axis
 *    (a touch of red/yellow), which is what makes Claude's dark mode read as paper-in-low-light
 *    rather than as a console. A cool grey next to these would look broken.
 *  - **Terracotta is the app's identity, not a status.** Claude's signature accent marks
 *    brand moments — the app icon, the active tab, reset actions — and is deliberately kept
 *    out of the quota ramp, so it never competes with "how much is left".
 *
 * Colour is never the only channel: every status also carries a label and a bar length, so
 * the screens stay readable for colour-blind users and in sunlight.
 */
object UsageColors {

    // Surfaces — warm near-black, lifted by luminance rather than shadow. Dark enough to
    // still benefit from AMOLED, warm enough to stay in Claude's family.
    val Background = Color(0xFF0F0F0E)
    val Surface = Color(0xFF1A1918)
    val SurfaceElevated = Color(0xFF242322)
    val SurfaceMuted = Color(0xFF2E2D2B)
    val Outline = Color(0xFF3A3936)

    // Text — warm off-white through warm greys, never pure white or pure grey.
    val TextPrimary = Color(0xFFF0EEE6)
    val TextSecondary = Color(0xFFB4B2A9)
    val TextTertiary = Color(0xFF8A887F)

    /** Claude's terracotta. Brand and action only — see the class note. */
    val Terracotta = Color(0xFFD97757)
    val TerracottaMuted = Color(0xFFB35F42)

    /**
     * Status ramp, warmed to sit on the neutral surfaces above.
     *
     * Teal is reserved for "untouched" (a full window) — calmer than green, which keeps green
     * meaningful for "healthy but in use". Red is deliberately scarce: it appears only at real
     * exhaustion, so it keeps its urgency.
     */
    val Teal = Color(0xFF4FBFA8)
    val Green = Color(0xFF6FBF73)
    val Amber = Color(0xFFE0A33E)
    val Red = Color(0xFFD9584F)
    val Slate = Color(0xFF6B6960)

    /**
     * Text tones for the two accents that are not legible as words.
     *
     * [Red] and [Slate] are fine as a bar or a dot, but as a status label on their own tinted
     * container they measure 3.88:1 and 2.78:1 — the 12sp label is normal text, so WCAG AA
     * wants 4.5:1. These are the same hues lifted until they clear it on the lightest ground
     * either is drawn on. Pinned by StatusContrastTest.
     */
    val RedText = Color(0xFFE8756B)
    val SlateText = Color(0xFFA29F94)

    /** Track behind every usage bar. */
    val ProgressTrack = Color(0xFF2E2D2B)

    // Tinted chip backgrounds — the accent at low alpha, so they sit on any surface.
    val GreenSurface = Color(0x266FBF73)
    val TealSurface = Color(0x264FBFA8)
    val AmberSurface = Color(0x26E0A33E)
    val RedSurface = Color(0x26D9584F)
    val TerracottaSurface = Color(0x26D97757)
    val SlateSurface = Color(0x266B6960)
}
