package com.usagelimits.widget

import kotlin.math.floor

/** Layout uses the host's available dp area; launcher cell labels are only placement hints. */
object WidgetLayout {
    fun accountColumns(width: Float, height: Float): Int =
        if (width >= 240f && height >= 240f) 2 else 1

    fun miniColumns(width: Float): Int = maxOf(1, floor((width - 8f) / 52f).toInt())
    fun miniRows(height: Float): Int = maxOf(1, floor((height - 8f) / 28f).toInt())
    fun miniCapacity(width: Float, height: Float): Int = miniColumns(width) * miniRows(height)
}
