package app.notate.core.ink.shape

import app.notate.core.ink.outline.InkGeometry
import app.notate.core.model.ShapeKind
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Turns a shape (kind + params, layout in plans/rewrite/03 §2) into stroked polylines of width [width].
 * Closed shapes repeat their first point. Arrow wings are 25° off the shaft and `3·width + 4` pt long; axes use
 * 2 pt half-length tick marks. Pure; any thread.
 */
object ShapeOutline {
    private const val ELLIPSE_SEGMENTS = 64
    private const val WING_ANGLE = 25.0 * PI / 180.0
    private const val TICK_HALF_PT = 2f

    fun of(kind: ShapeKind, params: FloatArray, width: Float): List<InkGeometry.Polyline> {
        fun poly(vararg xy: Float) = InkGeometry.Polyline(floatArrayOf(*xy), width, null)
        val out = ArrayList<InkGeometry.Polyline>()
        when (kind) {
            ShapeKind.LINE -> out.add(poly(params[0], params[1], params[2], params[3]))
            ShapeKind.ARROW -> arrow(out, params[0], params[1], params[2], params[3], width, head = true, tail = false)
            ShapeKind.DOUBLE_ARROW -> arrow(out, params[0], params[1], params[2], params[3], width, head = true, tail = true)
            ShapeKind.RECT, ShapeKind.TRIANGLE, ShapeKind.POLYGON -> out.add(InkGeometry.Polyline(closed(params), width, null))
            ShapeKind.POLYLINE -> out.add(InkGeometry.Polyline(params.copyOf(), width, null))
            ShapeKind.ELLIPSE -> out.add(InkGeometry.Polyline(ellipse(params), width, null))
            ShapeKind.AXES_2D, ShapeKind.AXES_Q1 -> axes(out, kind, params, width)
        }
        return out
    }

    private fun closed(v: FloatArray) = if (v.size < 2) v.copyOf() else v.copyOf(v.size + 2).also { it[v.size] = v[0]; it[v.size + 1] = v[1] }

    private fun ellipse(p: FloatArray): FloatArray {
        val a = Math.toRadians(p[4].toDouble())
        val c = cos(a); val s = sin(a)
        val out = FloatArray((ELLIPSE_SEGMENTS + 1) * 2)
        for (i in 0..ELLIPSE_SEGMENTS) {
            val t = 2.0 * PI * i / ELLIPSE_SEGMENTS
            val u = p[2] * cos(t); val v = p[3] * sin(t)
            out[2 * i] = (p[0] + u * c - v * s).toFloat()
            out[2 * i + 1] = (p[1] + u * s + v * c).toFloat()
        }
        return out
    }

    /** Shaft from (x1,y1) to (x2,y2) plus wings at the end ([head]) and/or the start ([tail]). */
    private fun arrow(out: MutableList<InkGeometry.Polyline>, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, head: Boolean, tail: Boolean) {
        out.add(InkGeometry.Polyline(floatArrayOf(x1, y1, x2, y2), w, null))
        val len = 3f * w + 4f
        if (head) wings(out, x2, y2, x1, y1, len, w)
        if (tail) wings(out, x1, y1, x2, y2, len, w)
    }

    /** Two wings at [tip], opening backwards towards ([fx], [fy]). */
    private fun wings(out: MutableList<InkGeometry.Polyline>, tx: Float, ty: Float, fx: Float, fy: Float, len: Float, w: Float) {
        val back = atan2((fy - ty).toDouble(), (fx - tx).toDouble())
        for (side in intArrayOf(-1, 1)) {
            val a = back + side * WING_ANGLE
            out.add(InkGeometry.Polyline(floatArrayOf((tx + len * cos(a)).toFloat(), (ty + len * sin(a)).toFloat(), tx, ty), w, null))
        }
    }

    /**
     * params = ox, oy, xLen, yLen, angleDeg, tickPt. AXES_2D: the axes cross at the origin and extend half of
     * xLen / yLen to each side. AXES_Q1: the axes run from the origin by xLen to the right and yLen up.
     * Arrows point to +x and up (y decreases on the page). Ticks sit at every multiple of tickPt strictly
     * inside each axis (none when tickPt = 0).
     */
    private fun axes(out: MutableList<InkGeometry.Polyline>, kind: ShapeKind, p: FloatArray, w: Float) {
        val ox = p[0]; val oy = p[1]; val xLen = p[2]; val yLen = p[3]; val tick = p[5]
        val a = Math.toRadians(p[4].toDouble())
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        // Local (u right, v up) → page.
        fun px(u: Float, v: Float) = ox + u * c + v * s
        fun py(u: Float, v: Float) = oy + u * s - v * c
        val q1 = kind == ShapeKind.AXES_Q1
        val xFrom = if (q1) 0f else -xLen / 2f; val xTo = if (q1) xLen else xLen / 2f
        val yFrom = if (q1) 0f else -yLen / 2f; val yTo = if (q1) yLen else yLen / 2f
        arrow(out, px(xFrom, 0f), py(xFrom, 0f), px(xTo, 0f), py(xTo, 0f), w, head = true, tail = false)
        arrow(out, px(0f, yFrom), py(0f, yFrom), px(0f, yTo), py(0f, yTo), w, head = true, tail = false)
        if (tick <= 0f) return
        fun tickMark(u: Float, v: Float, horizontalAxis: Boolean) {
            val (du, dv) = if (horizontalAxis) 0f to TICK_HALF_PT else TICK_HALF_PT to 0f
            out.add(InkGeometry.Polyline(floatArrayOf(px(u - du, v - dv), py(u - du, v - dv), px(u + du, v + dv), py(u + du, v + dv)), w, null))
        }
        for (k in 1..ticksIn(xTo, tick)) tickMark(k * tick, 0f, true)
        if (!q1) for (k in 1..ticksIn(-xFrom, tick)) tickMark(-k * tick, 0f, true)
        for (k in 1..ticksIn(yTo, tick)) tickMark(0f, k * tick, false)
        if (!q1) for (k in 1..ticksIn(-yFrom, tick)) tickMark(0f, -k * tick, false)
    }

    /** Number of multiples of [tick] strictly inside (0, [length]). */
    private fun ticksIn(length: Float, tick: Float): Int {
        val k = floor(length / tick - 1e-4f).toInt()
        return if (k < 0) 0 else k
    }

}
