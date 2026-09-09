package com.usagelimits.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * How the shell should arrange itself at the current window size.
 *
 * Derived from the *window* width, not the physical screen: a folded phone, an unfolded
 * foldable, a tablet, a freeform window and a split-screen pane are all just widths, and
 * treating them uniformly means the app adapts to states no one enumerated — including a
 * fold being opened while a screen is on display.
 */
enum class NavigationLayout {
    /** Phones, folded foldables, narrow split-screen: bottom navigation bar. */
    BOTTOM_BAR,

    /** Unfolded foldables, tablets, desktop windows: a side rail, freeing vertical space. */
    SIDE_RAIL,
    ;

    companion object {
        fun forWidth(widthSizeClass: WindowWidthSizeClass): NavigationLayout =
            when (widthSizeClass) {
                WindowWidthSizeClass.Compact -> BOTTOM_BAR
                // Medium starts at 600dp — an unfolded inner display or a small tablet. A
                // rail reaches the thumb better than a bottom bar at that width and does not
                // eat height that the usage list can use.
                else -> SIDE_RAIL
            }
    }
}

/** True once there is room to show the account list and one account's detail side by side. */
fun WindowSizeClass.supportsTwoPane(): Boolean =
    widthSizeClass == WindowWidthSizeClass.Expanded

/**
 * Caps content width and centres it on wide windows.
 *
 * Usage rows are label + bar + percent + countdown. Stretched across a tablet the eye has to
 * travel the full width to connect a label to its number, so the content stops growing at a
 * comfortable measure and the extra space becomes margin.
 */
@Composable
fun ConstrainedContent(
    modifier: Modifier = Modifier,
    maxWidth: androidx.compose.ui.unit.Dp = 720.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth()) {
            content()
        }
    }
}
