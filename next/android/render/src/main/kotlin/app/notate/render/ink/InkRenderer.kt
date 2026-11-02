package app.notate.render.ink

import android.graphics.Canvas
import android.graphics.Paint
import app.notate.core.model.ImageItem
import app.notate.core.model.Item
import app.notate.core.model.LinkItem
import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.Stroke
import app.notate.core.model.TextBox

/**
 * Draws page items. The canvas must already carry the page→target matrix (items are in page pt). Strokes and shapes are
 * drawn from [GeometryCache]; text, images and links are drawn as placeholders until R2-7. Any thread, but the
 * [PaintPool] passed in must belong to the calling thread.
 */
class InkRenderer(private val geometry: GeometryCache) {

    fun draw(canvas: Canvas, item: Item, paint: PaintPool) {
        when (item) {
            is Stroke -> drawStroke(canvas, item, paint)
            is Shape -> drawShape(canvas, item, paint)
            is TextBox -> placeholder(canvas, item.frame.l, item.frame.t, item.frame.r, item.frame.b, "Text", paint)
            is ImageItem -> placeholder(canvas, item.frame.l, item.frame.t, item.frame.r, item.frame.b, "Image", paint)
            is LinkItem -> placeholder(canvas, item.frame.l, item.frame.t, item.frame.r, item.frame.b, "Link", paint)
        }
    }

    private fun drawStroke(canvas: Canvas, s: Stroke, paints: PaintPool) {
        val g = geometry.pathFor(s)
        val pen = s.style.pen
        val highlighter = pen == PenType.HIGHLIGHTER
        g.fill?.let { canvas.drawPath(it, paints.fill(s.style.color, highlighter)) }
        for (part in g.strokes) {
            val cap = when (pen) {
                PenType.HIGHLIGHTER -> Paint.Cap.SQUARE
                PenType.DASHED -> Paint.Cap.BUTT
                else -> Paint.Cap.ROUND
            }
            val join = if (highlighter) Paint.Join.MITER else Paint.Join.ROUND
            canvas.drawPath(part.path, paints.stroke(s.style.color, part.width, cap, join, part.dash, highlighter))
        }
        g.dots?.let { d ->
            val p = paints.fill(s.style.color)
            for (i in d.radii.indices) canvas.drawCircle(d.centers[2 * i], d.centers[2 * i + 1], d.radii[i], p)
        }
    }

    private fun drawShape(canvas: Canvas, s: Shape, paints: PaintPool) {
        val g = geometry.pathFor(s)
        val highlighter = s.style.pen == PenType.HIGHLIGHTER
        val fillColor = s.fill
        if (fillColor != null) g.fill?.let { canvas.drawPath(it, paints.fill(fillColor)) }
        for (part in g.strokes) {
            canvas.drawPath(part.path, paints.stroke(s.style.color, part.width, Paint.Cap.ROUND, Paint.Join.ROUND, part.dash, highlighter))
        }
    }

    private fun placeholder(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, label: String, paints: PaintPool) {
        canvas.drawRect(l, t, r, b, paints.stroke(PLACEHOLDER_COLOR, 1f, Paint.Cap.BUTT, Paint.Join.MITER, floatArrayOf(4f, 3f)))
        canvas.drawText(label, l + 3f, t + 12f, paints.text(PLACEHOLDER_COLOR, 10f))
    }

    private companion object {
        const val PLACEHOLDER_COLOR = 0xFF808080.toInt()
    }
}
