package app.notate.export.doc

import android.graphics.Canvas
import android.graphics.Paint
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream

/**
 * A [Canvas] that writes what the paper templates draw (rectangles, lines, dots, clips) into a PDF content stream instead of a
 * bitmap, so exported templates are the very lines `PaperRenderer` draws on screen, as vectors. Only the calls the template
 * renderers make are supported; anything else is ignored. The stream must already carry the page→PDF matrix.
 */
internal class PdfCanvas(private val cs: PDPageContentStream, private val styles: PdfStyles) : Canvas() {
    private var depth = 0

    override fun save(): Int { cs.saveGraphicsState(); return ++depth }

    override fun restore() {
        if (depth == 0) return
        cs.restoreGraphicsState()
        depth--
    }

    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        cs.addRect(left, top, right - left, bottom - top)
        cs.clip()
        return true
    }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        cs.saveGraphicsState()
        cs.addRect(left, top, right - left, bottom - top)
        paintPath(paint)
        cs.restoreGraphicsState()
    }

    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
        cs.saveGraphicsState()
        strokeStyle(paint)
        cs.moveTo(startX, startY)
        cs.lineTo(stopX, stopY)
        cs.stroke()
        cs.restoreGraphicsState()
    }

    override fun drawLines(pts: FloatArray, paint: Paint) = drawLines(pts, 0, pts.size, paint)

    override fun drawLines(pts: FloatArray, offset: Int, count: Int, paint: Paint) {
        cs.saveGraphicsState()
        strokeStyle(paint)
        var i = offset
        while (i + 3 < offset + count) {
            cs.moveTo(pts[i], pts[i + 1])
            cs.lineTo(pts[i + 2], pts[i + 3])
            i += 4
        }
        cs.stroke()
        cs.restoreGraphicsState()
    }

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        cs.saveGraphicsState()
        PdfStyles.circle(cs, cx, cy, radius)
        paintPath(paint)
        cs.restoreGraphicsState()
    }

    /** Releases states a renderer left open (a safety net; the renderers balance save/restore). */
    fun finish() { while (depth > 0) restore() }

    private fun strokeStyle(p: Paint) {
        styles.stroke(cs, p.color)
        styles.transparency(cs, p.color)
        cs.setLineWidth(p.strokeWidth)
    }

    private fun paintPath(p: Paint) {
        styles.transparency(cs, p.color)
        if (p.style == Paint.Style.STROKE) {
            styles.stroke(cs, p.color)
            cs.setLineWidth(p.strokeWidth)
            cs.stroke()
        } else {
            styles.fill(cs, p.color)
            cs.fill()
        }
    }
}
