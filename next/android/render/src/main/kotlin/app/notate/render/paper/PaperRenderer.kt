package app.notate.render.paper

import android.graphics.Canvas
import android.graphics.Paint
import app.notate.core.geometry.Rect
import app.notate.core.model.Background
import app.notate.core.model.PageInfo
import app.notate.core.model.PaperPattern
import app.notate.core.model.Units.mm
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws the 7 paper templates (04-ux-spec §2.1) in page pt: lines 0.5 pt in `lineColor`, dots radius 0.6 pt. Only the part
 * intersecting `clipDoc` is drawn. Whiteboard pages (height == null) extend the pattern infinitely in every direction;
 * fixed pages skip the lines on the page edge. ENGINEERING treats `spacingPt` as the bold line spacing (5 mm) with a thin
 * grid every fifth of it. Any thread (paints are created per call).
 */
class PaperRenderer : BackgroundRenderer {

    override fun draw(canvas: Canvas, page: PageInfo, clipDoc: Rect) {
        val bg = page.background as? Background.Paper ?: return
        draw(canvas, page, bg, clipDoc)
    }

    fun draw(canvas: Canvas, page: PageInfo, background: Background.Paper, clipDoc: Rect) {
        val area = pageArea(page, clipDoc)
        if (area.isEmpty) return
        val fixed = page.height != null
        val s = max(background.spacingPt, 1f)
        canvas.save()
        canvas.clipRect(area.l, area.t, area.r, area.b)
        canvas.drawRect(area.l, area.t, area.r, area.b, Paint().apply { color = background.paperColor; style = Paint.Style.FILL })
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background.lineColor; style = Paint.Style.STROKE; strokeWidth = LINE_PT }
        when (background.pattern) {
            PaperPattern.BLANK -> Unit
            PaperPattern.LINED -> horizontals(canvas, area, s, fixed, line)
            PaperPattern.GRID -> { horizontals(canvas, area, s, fixed, line); verticals(canvas, area, s, fixed, line) }
            PaperPattern.DOTS -> dots(canvas, area, s, fixed, background.lineColor)
            PaperPattern.ENGINEERING -> engineering(canvas, area, s, fixed, background.lineColor)
            PaperPattern.CORNELL -> cornell(canvas, page, background, area, line)
            PaperPattern.ISOMETRIC -> isometric(canvas, area, s, line)
        }
        canvas.restore()
    }

    private fun horizontals(c: Canvas, a: Rect, step: Float, fixed: Boolean, p: Paint) {
        val r = indexRange(a.t, a.b, step, fixed) ?: return
        val pts = FloatArray((r.last - r.first + 1) * 4)
        for ((n, k) in r.withIndex()) {
            val y = k * step
            pts[4 * n] = a.l; pts[4 * n + 1] = y; pts[4 * n + 2] = a.r; pts[4 * n + 3] = y
        }
        c.drawLines(pts, p)
    }

    private fun verticals(c: Canvas, a: Rect, step: Float, fixed: Boolean, p: Paint) {
        val r = indexRange(a.l, a.r, step, fixed) ?: return
        val pts = FloatArray((r.last - r.first + 1) * 4)
        for ((n, k) in r.withIndex()) {
            val x = k * step
            pts[4 * n] = x; pts[4 * n + 1] = a.t; pts[4 * n + 2] = x; pts[4 * n + 3] = a.b
        }
        c.drawLines(pts, p)
    }

    private fun dots(c: Canvas, a: Rect, step: Float, fixed: Boolean, color: Int) {
        val xs = indexRange(a.l - DOT_PT, a.r + DOT_PT, step, fixed) ?: return
        val ys = indexRange(a.t - DOT_PT, a.b + DOT_PT, step, fixed) ?: return
        if ((xs.last - xs.first + 1).toLong() * (ys.last - ys.first + 1) > MAX_DOTS) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        for (ky in ys) for (kx in xs) c.drawCircle(kx * step, ky * step, DOT_PT, p)
    }

    private fun engineering(c: Canvas, a: Rect, major: Float, fixed: Boolean, color: Int) {
        val minor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = (color and 0x00FFFFFF) or (((color ushr 24) * 2 / 5) shl 24)
            style = Paint.Style.STROKE; strokeWidth = LINE_PT
        }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = BOLD_PT }
        val fine = major / 5f
        if (fine >= mm(0.5f)) { horizontals(c, a, fine, fixed, minor); verticals(c, a, fine, fixed, minor) }
        horizontals(c, a, major, fixed, bold); verticals(c, a, major, fixed, bold)
    }

    /** Cue column line, header rule and summary rule (spec 04 §2.1: left column 60 mm, bottom area 50 mm). */
    private fun cornell(c: Canvas, page: PageInfo, bg: Background.Paper, a: Rect, p: Paint) {
        val colX = bg.marginLeftPt ?: mm(60f)
        val headerY = bg.headerPt ?: mm(25f)
        val bottomY = page.height?.let { it - mm(50f) }
        c.drawLine(a.l, headerY, a.r, headerY, p)
        c.drawLine(colX, headerY, colX, bottomY ?: a.b, p)
        if (bottomY != null) c.drawLine(a.l, bottomY, a.r, bottomY, p)
    }

    /** Triangular lattice: three line families at 0°, 60° and 120°; perpendicular spacing `s·√3/2`. */
    private fun isometric(c: Canvas, a: Rect, s: Float, p: Paint) {
        val d = s * (sqrt(3f) / 2f)
        val cx = (a.l + a.r) / 2f
        val cy = (a.t + a.b) / 2f
        val reach = hypot(a.width, a.height)
        for (deg in intArrayOf(0, 60, 120)) {
            val rad = Math.toRadians(deg.toDouble())
            val ux = kotlin.math.cos(rad).toFloat(); val uy = kotlin.math.sin(rad).toFloat()
            val nx = -uy; val ny = ux
            // Range of n·p over the area corners.
            val dots = floatArrayOf(a.l * nx + a.t * ny, a.r * nx + a.t * ny, a.l * nx + a.b * ny, a.r * nx + a.b * ny)
            val lo = ceil(dots.min() / d).toInt(); val hi = floor(dots.max() / d).toInt()
            if (hi - lo > MAX_LINES) continue
            val centerProj = cx * nx + cy * ny
            for (k in lo..hi) {
                val off = k * d - centerProj          // distance of this line from the center, along n
                val px = cx + off * nx; val py = cy + off * ny
                c.drawLine(px - ux * reach, py - uy * reach, px + ux * reach, py + uy * reach, p)
            }
        }
    }

    /** Integer multiples k of [step] with k·step inside [lo, hi]; on fixed pages k ≥ 1. Null when empty or absurdly many. */
    private fun indexRange(lo: Float, hi: Float, step: Float, fixed: Boolean): IntRange? {
        var first = ceil(lo / step).toInt()
        val last = floor(hi / step).toInt()
        if (fixed) first = max(first, 1)
        if (last < first || last - first > MAX_LINES) return null
        return first..last
    }

    companion object {
        private const val LINE_PT = 0.5f
        private const val BOLD_PT = 0.75f
        private const val DOT_PT = 0.6f
        private const val MAX_LINES = 4000
        private const val MAX_DOTS = 40_000L

        /** [clipDoc] limited to the page extent (fixed pages) or unchanged (whiteboard). */
        fun pageArea(page: PageInfo, clipDoc: Rect): Rect {
            val h = page.height ?: return clipDoc
            return Rect(max(clipDoc.l, 0f), max(clipDoc.t, 0f), min(clipDoc.r, page.width), min(clipDoc.b, h))
        }
    }
}
