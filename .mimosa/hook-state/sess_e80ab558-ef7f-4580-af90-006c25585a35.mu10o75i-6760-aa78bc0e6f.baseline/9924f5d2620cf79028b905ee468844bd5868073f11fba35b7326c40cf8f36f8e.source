package com.usagelimits.widget

object WidgetLayout {
    data class Grid(val columns: Int, val rows: Int) { val capacity: Int get() = columns * rows }

    fun accountColumns(width: Float, height: Float, metrics: WidgetMetrics? = null): Int =
        if (metrics == null) { if (width >= 240f && height >= 240f) 2 else 1 }
        else { val span = metrics.span(width, height); if (span.columns >= 2 && span.rows >= 4) 2 else 1 }

    fun miniGrid(width: Float, height: Float, metrics: WidgetMetrics = WidgetMetrics()): Grid {
        val span = metrics.span(width, height)
        return if (span.landscape) Grid(span.columns * 2, span.rows) else Grid(span.columns, span.rows * 2)
    }

    fun miniCapacity(width: Float, height: Float): Int = miniGrid(width, height).capacity

    /**
     * Which content the compact widget lays out at a given width.
     *
     * The compact XML admits 180 dp, but the four-tile row cannot fit it: 20 dp of outer
     * padding, three 8 dp gaps, a 40 dp refresh target and each tile's own 20 dp of padding
     * leave roughly 4 dp of text width per tile — four labels cannot be read in that, and
     * large font scales make it worse before the minimum is even reached. Rather than forbid
     * the resize, the content yields: two metrics when two fit, and one focused readout when
     * not. The budget below is the arithmetic the layout actually obeys, kept pure so the
     * contract is testable without a launcher.
     */
    enum class CompactLayout { FOUR_TILES, SUMMARY, FOCUS }

    /** Horizontal chrome of the compact widget, in dp. */
    private const val OUTER_PADDING_DP = 20f
    private const val GAP_DP = 8f
    private const val REFRESH_TARGET_DP = 40f
    private const val TILE_PADDING_DP = 20f

    /** Widest fixed tile content — a label or value — at unit font scale, in dp. */
    private const val TILE_TEXT_DP = 52f

    fun compactLayout(widthDp: Float, fontScale: Float): CompactLayout {
        val text = TILE_TEXT_DP * fontScale.coerceAtLeast(1f)
        val fourTiles = OUTER_PADDING_DP + REFRESH_TARGET_DP + 3 * GAP_DP + 4 * (TILE_PADDING_DP + text)
        if (widthDp >= fourTiles) return CompactLayout.FOUR_TILES
        val twoTiles = OUTER_PADDING_DP + REFRESH_TARGET_DP + GAP_DP + 2 * (TILE_PADDING_DP + text)
        return if (widthDp >= twoTiles) CompactLayout.SUMMARY else CompactLayout.FOCUS
    }
}
