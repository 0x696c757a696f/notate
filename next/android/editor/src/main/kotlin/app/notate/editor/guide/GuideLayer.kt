package app.notate.editor.guide

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.notate.core.doc.layout.Viewport
import app.notate.core.geometry.Rect
import app.notate.core.ink.guide.GuideBody
import app.notate.core.ink.guide.GuideKind
import app.notate.core.ink.guide.Guides
import app.notate.core.model.Units
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the ruler (mm scale, 1/5/10 mm ticks, cm numbers) and the protractor (half disc, 1° ticks, 10° numbers, centre cross,
 * rotation handle) over a page, plus the angle labels (04 §3.7). Everything is drawn in page space, so the guide lies on the
 * paper and follows scrolling and zoom. Stateless apart from its paints. Main thread only.
 */
class GuideLayer(private val density: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xB8FFFFFF.toInt() }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER }
    private val ticks = FloatArray(4 * 400)
    private val path = Path()
    private val box = RectF()
    private var px = 1f     // one screen pixel in pt

    /** [rays]: absolute angles (degrees) of the last protractor rays drawn; two of them show the angle between. */
    fun draw(canvas: Canvas, vp: Viewport, frame: Rect, body: GuideBody, rays: List<Float>) {
        val s = vp.scale
        px = 1f / s
        canvas.save()
        canvas.translate((frame.l - vp.scrollX) * s, (frame.t - vp.scrollY) * s)
        canvas.scale(s, s)
        line.strokeWidth = 1.5f * density * px
        text.textSize = 11f * density * px
        canvas.save()
        canvas.translate(body.cx, body.cy)
        canvas.rotate(body.angleDeg)
        if (body.kind == GuideKind.RULER) ruler(canvas, body) else protractor(canvas, body)
        canvas.restore()
        labels(canvas, body, rays)
        canvas.restore()
    }

    private fun ruler(canvas: Canvas, b: GuideBody) {
        val r = b.ruler
        val l = r.lengthPt
        val w = r.widthPt
        canvas.drawRect(-l / 2, -w / 2, l / 2, w / 2, fill)
        canvas.drawRect(-l / 2, -w / 2, l / 2, w / 2, line)
        val mm = Units.mm(1f)
        val thin = Paint(line).apply { strokeWidth = 1f * density * px }
        var n = 0
        for (i in 0..(l / mm).toInt()) {
            val len = Units.mm(if (i % 10 == 0) 5f else if (i % 5 == 0) 3.5f else 2f)
            val x = -l / 2 + i * mm
            ticks[n++] = x; ticks[n++] = -w / 2; ticks[n++] = x; ticks[n++] = -w / 2 + len
            if (n + 4 > ticks.size) { canvas.drawLines(ticks, 0, n, thin); n = 0 }
        }
        for (i in 0..(l / mm).toInt()) {
            val len = Units.mm(if (i % 10 == 0) 5f else if (i % 5 == 0) 3.5f else 2f)
            val x = -l / 2 + i * mm
            ticks[n++] = x; ticks[n++] = w / 2; ticks[n++] = x; ticks[n++] = w / 2 - len
            if (n + 4 > ticks.size) { canvas.drawLines(ticks, 0, n, thin); n = 0 }
        }
        canvas.drawLines(ticks, 0, n, thin)
        var cm = 1
        while (cm * 10 * mm < l - mm) {
            canvas.drawText(cm.toString(), -l / 2 + cm * 10 * mm, -w / 2 + Units.mm(5f) + text.textSize, text)
            cm++
        }
    }

    private fun protractor(canvas: Canvas, b: GuideBody) {
        val r = b.protractor.radiusPt
        path.reset()
        path.moveTo(-r, 0f)
        box.set(-r, -r, r, r)
        path.arcTo(box, 180f, 180f)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, line)
        val thin = Paint(line).apply { strokeWidth = 1f * density * px }
        var n = 0
        for (d in 0..180) {
            val len = Units.mm(if (d % 10 == 0) 4f else if (d % 5 == 0) 3f else 1.5f)
            val a = Math.toRadians(d.toDouble())
            val c = cos(a).toFloat()
            val si = sin(a).toFloat()
            ticks[n++] = r * c; ticks[n++] = -r * si; ticks[n++] = (r - len) * c; ticks[n++] = -(r - len) * si
            if (n + 4 > ticks.size) { canvas.drawLines(ticks, 0, n, thin); n = 0 }
        }
        canvas.drawLines(ticks, 0, n, thin)
        val lr = r - Units.mm(9f)
        for (d in 10..170 step 10) {
            val a = Math.toRadians(d.toDouble())
            canvas.drawText(d.toString(), lr * cos(a).toFloat(), -lr * sin(a).toFloat() + text.textSize / 3f, text)
        }
        val cross = Units.mm(4f)
        canvas.drawLine(-cross, 0f, cross, 0f, line)
        canvas.drawLine(0f, -cross, 0f, cross, line)
        val h = Units.mm(4.5f)
        canvas.drawCircle(0f, -r, h, solid)
        canvas.drawCircle(0f, -r, h, line)
        canvas.drawCircle(0f, -r, h / 3f, Paint(line).apply { style = Paint.Style.FILL })
    }

    /** Upright text boxes: the guide's direction, and for the protractor the angle between the last two rays. */
    private fun labels(canvas: Canvas, b: GuideBody, rays: List<Float>) {
        val a = Math.toRadians(b.angleDeg.toDouble())
        val deg = "${Guides.lineDegrees(b.angleDeg)}°"
        if (b.kind == GuideKind.RULER) {
            label(canvas, deg, b.cx, b.cy)
            return
        }
        val off = Units.mm(8f)
        label(canvas, deg, b.cx - sin(a).toFloat() * off, b.cy + cos(a).toFloat() * off)
        if (rays.size >= 2) {
            val r1 = rays[rays.size - 2]
            val r2 = rays[rays.size - 1]
            var diff = (r2 - r1) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            val mid = Math.toRadians((r1 + diff / 2f).toDouble())
            val d = b.protractor.radiusPt + Units.mm(9f)
            label(canvas, "${Math.round(Guides.betweenDeg(r1, r2))}°", b.cx + d * cos(mid).toFloat(), b.cy + d * sin(mid).toFloat())
        }
    }

    private fun label(canvas: Canvas, s: String, x: Float, y: Float) {
        val pad = 4f * density * px
        val w = text.measureText(s) / 2f + pad
        val h = text.textSize * 0.5f + pad
        box.set(x - w, y - h, x + w, y + h)
        canvas.drawRect(box, solid)
        canvas.drawRect(box, line)
        canvas.drawText(s, x, y + text.textSize / 3f, text)
    }
}
