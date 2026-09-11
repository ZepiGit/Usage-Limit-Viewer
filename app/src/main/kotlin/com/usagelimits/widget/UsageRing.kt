package com.usagelimits.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * The ring the minimal widget is made of, drawn into a bitmap.
 *
 * Glance has no canvas: its building blocks are boxes, text and images. A box with a corner
 * radius can be a circle but not a PARTIAL one, so a ring that fills as quota is consumed has
 * to arrive as a picture. This draws that picture.
 */
object UsageRing {

    /** Degrees of arc for a given remaining fraction, starting at twelve o'clock. */
    fun sweepDegrees(remainingFraction: Float): Float =
        360f * remainingFraction.coerceIn(0f, 1f)

    /**
     * Remaining quota as a fraction, from the percentage a provider reported.
     *
     * Null — a provider that states no percentage — is drawn as a full ring in the track
     * colour rather than an empty one: an empty ring is what "exhausted" looks like, and
     * claiming a limit is spent because nobody said otherwise is the worst way to be wrong.
     */
    fun fractionFor(remainingPercent: Double?): Float =
        ((remainingPercent ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f)

    fun draw(
        sizePx: Int,
        remainingPercent: Double?,
        argb: Int,
        trackArgb: Int,
        strokePx: Float = sizePx * 0.11f,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val inset = strokePx / 2f
        val box = RectF(inset, inset, sizePx - inset, sizePx - inset)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
        }

        paint.color = trackArgb
        canvas.drawArc(box, 0f, 360f, false, paint)

        paint.color = argb
        // From twelve o'clock, clockwise, so a ring that is emptying reads the way a clock
        // face does rather than unwinding backwards.
        canvas.drawArc(box, -90f, sweepDegrees(fractionFor(remainingPercent)), false, paint)

        return bitmap
    }
}
