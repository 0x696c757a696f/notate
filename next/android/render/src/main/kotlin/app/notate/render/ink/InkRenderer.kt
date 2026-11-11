package app.notate.render.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import app.notate.render.image.ImageCache
import app.notate.render.image.TextLayouts
import kotlin.math.hypot
import app.notate.core.model.ImageItem
import app.notate.core.model.Item
import app.notate.core.model.LinkItem
import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.Stroke
import app.notate.core.model.TextBox

/**
 * Draws page items. The canvas must already carry the page→target matrix (items are in page pt). Strokes and shapes are
 * drawn from [GeometryCache], text with `StaticLayout`, images from [images] (a grey placeholder until loaded; without a cache
 * always), links as a small chain icon in the frame's top-right corner. Any thread, but the
 * [PaintPool] passed in must belong to the calling thread.
 */
class InkRenderer(private val geometry: GeometryCache, private val images: ImageCache? = null) {

    fun draw(canvas: Canvas, item: Item, paint: PaintPool) {
        when (item) {
            is Stroke -> drawStroke(canvas, item, paint)
            is Shape -> drawShape(canvas, item, paint)
            is TextBox -> drawText(canvas, item)
            is ImageItem -> drawImage(canvas, item, paint)
            is LinkItem -> drawLink(canvas, item, paint)
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

    private fun drawText(canvas: Canvas, t: TextBox) {
        if (t.text.isEmpty()) return
        val layout = TextLayouts.build(t.text, t.fontSizePt, t.bold, t.frame.width, t.align, t.color)
        canvas.save()
        if (t.rotationDeg != 0f) canvas.rotate(t.rotationDeg, t.frame.center.x, t.frame.center.y)
        canvas.translate(t.frame.l, t.frame.t)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawImage(canvas: Canvas, img: ImageItem, paints: PaintPool) {
        val f = img.frame
        val bmp = images?.get(img.asset, f.width * scaleOf(canvas))
        canvas.save()
        if (img.rotationDeg != 0f) canvas.rotate(img.rotationDeg, f.center.x, f.center.y)
        if (bmp == null) {
            canvas.drawRect(f.l, f.t, f.r, f.b, paints.fill(PLACEHOLDER_FILL))
            canvas.drawRect(f.l, f.t, f.r, f.b, paints.stroke(PLACEHOLDER_COLOR, 1f, Paint.Cap.BUTT, Paint.Join.MITER, floatArrayOf(4f, 3f)))
        } else {
            val p = paints.fill(Color.WHITE)
            p.isFilterBitmap = true
            p.alpha = (img.opacity.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawBitmap(bmp, null, RectF(f.l, f.t, f.r, f.b), p)
        }
        canvas.restore()
    }

    /** Links have no box, only an icon: two interlocked rounded rectangles turned 45°. */
    private fun drawLink(canvas: Canvas, link: LinkItem, paints: PaintPool) {
        val f = link.frame
        val size = minOf(LINK_ICON_PT, f.width, f.height)
        if (size <= 0f) return
        val p = paints.stroke(LINK_COLOR, size * 0.12f, Paint.Cap.ROUND, Paint.Join.ROUND)
        canvas.save()
        canvas.translate(f.r - size / 2f - 1f, f.t + size / 2f + 1f)
        canvas.rotate(-45f)
        val w = size * 0.62f
        val h = size * 0.3f
        val gap = size * 0.12f
        canvas.drawRoundRect(-w - gap / 2f, -h / 2f, gap / 2f + w * 0.25f, h / 2f, h / 2f, h / 2f, p)
        canvas.drawRoundRect(-gap / 2f - w * 0.25f, -h / 2f, w + gap / 2f, h / 2f, h / 2f, h / 2f, p)
        canvas.restore()
    }

    /** Pixels per page pt on the (software) canvas: lets an image ask for a bitmap that is only as big as it is drawn. */
    @Suppress("DEPRECATION")
    private fun scaleOf(canvas: Canvas): Float {
        val m = Matrix()
        canvas.getMatrix(m)
        val v = FloatArray(9)
        m.getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y]).takeIf { it > 0f } ?: 1f
    }

    private companion object {
        const val PLACEHOLDER_COLOR = 0xFF808080.toInt()
        const val PLACEHOLDER_FILL = 0xFFE6E6E6.toInt()
        const val LINK_COLOR = 0xFF000000.toInt()
        /** 10 dp on the Boox (227 dpi) at 100 % zoom is about 4.5 pt of page. */
        const val LINK_ICON_PT = 5f
    }
}
