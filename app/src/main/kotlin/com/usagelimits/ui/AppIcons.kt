package com.usagelimits.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The three Material glyphs this app uses that the CORE icon artifact does not ship.
 *
 * Drawn from their published 24dp path data rather than pulled from `material-icons-extended`,
 * which carries several thousand icons for the sake of these three and was the largest single
 * dependency in the debug APK — the build a tester actually installs. R8 strips the unused
 * ones from a release, so this is a build-and-debug-size saving, not a shipped one, and it is
 * stated as such.
 *
 * Same viewport, fill and tint behaviour as the library's own: `Icon(...)` applies
 * `LocalContentColor` over the black fill exactly as it does for `Icons.Default.*`.
 */
object AppIcons {
    val DragHandle: ImageVector by lazy { icon("DragHandle", "M3 8h18v2H3zm0 6h18v2H3z") }
    /** Material "chevron_right". */
    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight", "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z")
    }

    /** Material "people". */
    val People: ImageVector by lazy {
        icon(
            "People",
            "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0" +
                "c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 " +
                "1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 " +
                "1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z",
        )
    }

    /** Material "schedule". */
    val Schedule: ImageVector by lazy {
        icon(
            "Schedule",
            "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2z" +
                "M12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 " +
                "3.15.75-1.23-4.5-2.67z",
        )
    }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
}
