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
}
