package com.usagelimits.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usagelimits.core.model.Severity

/**
 * Maps a [Severity] onto the palette.
 *
 * Kept here rather than at each call site so the whole app — screens, widgets and
 * notifications — colours a status identically.
 */
object SeverityPalette {

    fun accent(severity: Severity) = when (severity) {
        Severity.HEALTHY -> UsageColors.Green
        Severity.MEDIUM -> UsageColors.Amber
        Severity.LOW -> UsageColors.Amber
        Severity.EXHAUSTED -> UsageColors.Red
        Severity.STALE -> UsageColors.Slate
        Severity.ERROR -> UsageColors.Red
    }

    /**
     * The tone to write a status *word* in.
     *
     * Deliberately not [accent]: red and slate carry enough weight as a 7dp dot or a filled
     * bar, but as small text on [container] they land under the 4.5:1 AA floor, so those two
     * are lifted. Everything else keeps its accent, which already clears it.
     */
    fun textColor(severity: Severity) = when (severity) {
        Severity.EXHAUSTED, Severity.ERROR -> UsageColors.RedText
        Severity.STALE -> UsageColors.SlateText
        else -> accent(severity)
    }

    fun container(severity: Severity) = when (severity) {
        Severity.HEALTHY -> UsageColors.GreenSurface
        Severity.MEDIUM, Severity.LOW -> UsageColors.AmberSurface
        Severity.EXHAUSTED, Severity.ERROR -> UsageColors.RedSurface
        Severity.STALE -> UsageColors.SlateSurface
    }

    /**
     * Bar colour for a single window.
     *
     * An untouched window gets teal rather than green, preserving the reference design's
     * distinction between "100 % available" and "healthy but partly used".
     */
    fun barColor(remainingPercent: Double?, severity: Severity) = when {
        remainingPercent != null && remainingPercent >= 99.5 -> UsageColors.Teal
        else -> accent(severity)
    }

    fun label(severity: Severity) = when (severity) {
        Severity.HEALTHY -> "Healthy"
        Severity.MEDIUM -> "Moderate"
        Severity.LOW -> "Low"
        Severity.EXHAUSTED -> "Exhausted"
        Severity.STALE -> "Stale"
        Severity.ERROR -> "Error"
    }

}

private val UsageColorScheme = darkColorScheme(
    primary = UsageColors.Terracotta,
    onPrimary = UsageColors.Background,
    secondary = UsageColors.TerracottaMuted,
    onSecondary = UsageColors.TextPrimary,
    background = UsageColors.Background,
    onBackground = UsageColors.TextPrimary,
    surface = UsageColors.Surface,
    onSurface = UsageColors.TextPrimary,
    surfaceVariant = UsageColors.SurfaceElevated,
    onSurfaceVariant = UsageColors.TextSecondary,
    outline = UsageColors.Outline,
    error = UsageColors.Red,
)

/** Large radii throughout — the reference design's most distinctive trait. */
private val UsageShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val UsageTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 15.sp,
    ),
)

/**
 * The app is dark-only by design.
 *
 * [darkTheme] is accepted so a future light palette can be dropped in without touching call
 * sites, but the AMOLED palette is currently used in both cases — a half-finished light mode
 * would look worse than a deliberate dark one.
 */
@Composable
fun UsageLimitsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    providerIcons: Map<String, String> = emptyMap(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMotionEnabled provides rememberMotionEnabled(), com.usagelimits.ui.LocalProviderIcons provides providerIcons) {
        MaterialTheme(
            colorScheme = UsageColorScheme,
            shapes = UsageShapes,
            typography = UsageTypography,
            content = content,
        )
    }
}
