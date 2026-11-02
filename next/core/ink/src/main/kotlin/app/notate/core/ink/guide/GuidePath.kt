package app.notate.core.ink.guide

import app.notate.core.geometry.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Builds the stroke geometry a pen draws along a [GuideSnap] from the raw pen positions. Pure. */
object GuidePath {
    /**
     * [xy] are the raw pen positions (x0, y0, x1, y1, …), at least one. Line: the segment between the lowest and highest projected
     * positions. Arc: the arc swept by the pen (up to a full circle). Ray: the segment from the centre to the farthest pen
     * position, at the whole-degree angle of the last one. Returns x/y pairs.
     */
    fun build(snap: GuideSnap, xy: FloatArray): FloatArray {
        val n = xy.size / 2
        if (n == 0) return FloatArray(0)
        return when (snap) {
            is GuideSnap.Line -> line(snap, xy, n)
            is GuideSnap.Arc -> arc(snap, xy, n)
            is GuideSnap.Ray -> ray(snap, xy, n)
        }
    }

    private fun line(g: GuideSnap.Line, xy: FloatArray, n: Int): FloatArray {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until n) {
            val t = (xy[2 * i] - g.x0) * g.dx + (xy[2 * i + 1] - g.y0) * g.dy
            if (t < lo) lo = t
            if (t > hi) hi = t
        }
        val a = Vec2(g.x0 + g.dx * lo, g.y0 + g.dy * lo)
        if (hi - lo < 1e-3f) return floatArrayOf(a.x, a.y)
        return floatArrayOf(a.x, a.y, g.x0 + g.dx * hi, g.y0 + g.dy * hi)
    }

    private fun arc(g: GuideSnap.Arc, xy: FloatArray, n: Int): FloatArray {
        val start = atan2((xy[1] - g.cy).toDouble(), (xy[0] - g.cx).toDouble())
        var prev = start
        var sweep = 0.0
        for (i in 1 until n) {
            val a = atan2((xy[2 * i + 1] - g.cy).toDouble(), (xy[2 * i] - g.cx).toDouble())
            sweep += delta(prev, a)
            prev = a
        }
        val s = sweep.coerceIn(-2 * PI, 2 * PI)
        val steps = max(1, (abs(s) / Guides.ARC_STEP).roundToInt())
        if (abs(s) < 1e-3) return floatArrayOf(g.cx + g.radius * cos(start).toFloat(), g.cy + g.radius * sin(start).toFloat())
        val out = FloatArray((steps + 1) * 2)
        for (i in 0..steps) {
            val a = start + s * i / steps
            out[2 * i] = g.cx + g.radius * cos(a).toFloat()
            out[2 * i + 1] = g.cy + g.radius * sin(a).toFloat()
        }
        return out
    }

    private fun ray(g: GuideSnap.Ray, xy: FloatArray, n: Int): FloatArray {
        var len = 0f
        for (i in 0 until n) len = max(len, hypot(xy[2 * i] - g.cx, xy[2 * i + 1] - g.cy))
        val a = Math.toRadians(Guides.rayAngleDeg(g, xy[2 * n - 2], xy[2 * n - 1]).toDouble())
        if (len < 1e-3f) return floatArrayOf(g.cx, g.cy)
        return floatArrayOf(g.cx, g.cy, g.cx + len * cos(a).toFloat(), g.cy + len * sin(a).toFloat())
    }

    /** Smallest signed difference to - from in (-PI, PI]. */
    private fun delta(from: Double, to: Double): Double {
        var d = to - from
        while (d > PI) d -= 2 * PI
        while (d <= -PI) d += 2 * PI
        return d
    }
}
