package app.notate.editor.popup

import android.content.Context
import android.graphics.Canvas
import android.view.View
import app.notate.core.geometry.Rect
import app.notate.core.model.InkStyle
import app.notate.core.model.ItemId
import app.notate.core.model.PenType
import app.notate.core.model.Stroke
import app.notate.core.model.StrokePoints
import app.notate.render.ink.GeometryCache
import app.notate.render.ink.InkRenderer
import app.notate.render.ink.PaintPool
import kotlin.math.PI
import kotlin.math.sin

/** Draws a fixed sine-wave stroke in the given pen style with the real ink renderer (what the pen will look like). */
class SampleStrokeView(context: Context, pen: PenType, color: Int, widthPt: Float, private val xdpi: Float) : View(context) {
    private val renderer = InkRenderer(GeometryCache(maxEntries = 2))
    private val paints = PaintPool()
    private var stroke: Stroke = build(pen, color, widthPt)

    /** Re-draws with a new style. */
    fun setStyle(pen: PenType, color: Int, widthPt: Float) {
        stroke = build(pen, color, widthPt)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(dpPx(SAMPLE_W_DP), dpPx(SAMPLE_H_DP))

    private fun dpPx(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDraw(canvas: Canvas) {
        val s = xdpi / 72f      // pt → px
        canvas.save()
        canvas.translate(0f, height / 2f)
        canvas.scale(s, s)
        renderer.draw(canvas, stroke, paints)
        canvas.restore()
    }

    private fun build(pen: PenType, color: Int, widthPt: Float): Stroke {
        val n = POINTS
        val wPt = SAMPLE_W_DP * resources.displayMetrics.density / (xdpi / 72f)
        val xy = FloatArray(2 * n)
        val pr = FloatArray(n)
        val t = IntArray(n)
        for (i in 0 until n) {
            val f = i / (n - 1f)
            xy[2 * i] = wPt * (0.06f + 0.88f * f)
            xy[2 * i + 1] = (8f * sin(f * 2 * PI)).toFloat()
            pr[i] = (0.25f + 0.65f * sin(f * PI)).toFloat()
            t[i] = i * 8
        }
        val bounds = Rect(0f, -12f, wPt, 12f)
        return Stroke(ItemId(0), 0, InkStyle(pen, color, widthPt), StrokePoints(xy, pr, t), bounds)
    }

    private companion object {
        const val POINTS = 48
        const val SAMPLE_W_DP = 120
        const val SAMPLE_H_DP = 40
    }
}
