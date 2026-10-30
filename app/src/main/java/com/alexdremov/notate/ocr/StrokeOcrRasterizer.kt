package com.alexdremov.notate.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import kotlin.math.ceil
import kotlin.math.min

data class OcrRaster(
    val bitmap: Bitmap,
    val worldBounds: RectF,
    val scale: Float,
) {
    fun toWorld(block: OcrBlock): OcrBlock {
        val mapped = block.quadrilateral.copyOf()
        for (index in mapped.indices step 2) {
            mapped[index] = worldBounds.left + mapped[index] / scale
            mapped[index + 1] = worldBounds.top + mapped[index + 1] / scale
        }
        val bounds = RectF()
        for (index in mapped.indices step 2) {
            if (index == 0) bounds.set(mapped[index], mapped[index + 1], mapped[index], mapped[index + 1])
            else bounds.union(mapped[index], mapped[index + 1])
        }
        return block.copy(quadrilateral = mapped, bounds = bounds)
    }
}

object StrokeOcrRasterizer {
    const val MAX_BITMAP_SIDE = 960
    private const val PADDING = 24f

    fun render(strokes: List<Stroke>): OcrRaster? {
        if (strokes.isEmpty()) return null
        val bounds = RectF()
        strokes.forEachIndexed { index, stroke ->
            if (index == 0) bounds.set(stroke.bounds) else bounds.union(stroke.bounds)
        }
        bounds.inset(-PADDING, -PADDING)
        return render(strokes, bounds)
    }

    fun render(
        strokes: List<Stroke>,
        bounds: RectF,
    ): OcrRaster? {
        if (bounds.width() <= 1f || bounds.height() <= 1f) return null

        val scale = min(1f, MAX_BITMAP_SIDE / maxOf(bounds.width(), bounds.height()))
        val width = ceil(bounds.width() * scale).toInt().coerceIn(1, MAX_BITMAP_SIDE)
        val height = ceil(bounds.height() * scale).toInt().coerceIn(1, MAX_BITMAP_SIDE)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        canvas.translate(-bounds.left, -bounds.top)

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        strokes.filter { RectF.intersects(it.bounds, bounds) }.forEach { stroke ->
            val points = stroke.points
            if (points.isEmpty()) return@forEach
            paint.strokeWidth = stroke.width.coerceAtLeast(1f)
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(path, paint)
        }
        return OcrRaster(bitmap, bounds, scale)
    }
}
