package com.usagelimits.widget

import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject

/** A launcher cell is not necessarily square. Capture its dimensions when a widget is placed. */
data class WidgetMetrics(
    val portraitWidth: Float = 72f,
    val portraitHeight: Float = 72f,
    val landscapeWidth: Float = 72f,
    val landscapeHeight: Float = 72f,
) {
    data class Span(val columns: Int, val rows: Int, val landscape: Boolean)

    fun span(width: Float, height: Float): Span {
        fun candidate(cellWidth: Float, cellHeight: Float, landscape: Boolean): Pair<Float, Span> {
            val x = (width + 8f) / cellWidth.coerceAtLeast(1f)
            val y = (height + 8f) / cellHeight.coerceAtLeast(1f)
            val columns = x.roundToInt().coerceIn(1, 8)
            val rows = y.roundToInt().coerceIn(1, 8)
            return abs(x - columns) + abs(y - rows) to Span(columns, rows, landscape)
        }
        val portrait = candidate(portraitWidth, portraitHeight, false)
        val landscape = candidate(landscapeWidth, landscapeHeight, true)
        return if (landscape.first + 0.05f < portrait.first) landscape.second else portrait.second
    }

    fun toJson(): String = JSONObject().put("pw", portraitWidth).put("ph", portraitHeight)
        .put("lw", landscapeWidth).put("lh", landscapeHeight).toString()

    companion object {
        fun fromJson(value: String): WidgetMetrics? = runCatching {
            val json = JSONObject(value)
            WidgetMetrics(json.getDouble("pw").toFloat(), json.getDouble("ph").toFloat(),
                json.getDouble("lw").toFloat(), json.getDouble("lh").toFloat())
                .takeIf { listOf(it.portraitWidth, it.portraitHeight, it.landscapeWidth, it.landscapeHeight).all { size -> size.isFinite() && size > 0 } }
        }.getOrNull()

        fun fromPlacement(minWidth: Int, minHeight: Int, maxWidth: Int, maxHeight: Int, columns: Int, rows: Int): WidgetMetrics =
            WidgetMetrics((minWidth.coerceAtLeast(56) + 8f) / columns.coerceAtLeast(1),
                (maxHeight.coerceAtLeast(56) + 8f) / rows.coerceAtLeast(1),
                (maxWidth.coerceAtLeast(56) + 8f) / columns.coerceAtLeast(1),
                (minHeight.coerceAtLeast(56) + 8f) / rows.coerceAtLeast(1))
    }
}
