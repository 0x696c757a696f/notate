package app.notate.core.ink.outline

import app.notate.core.model.InkStyle
import app.notate.core.model.PenType
import app.notate.core.model.StrokePoints
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Outline of the pressure-sensitive pens (BALLPOINT, FOUNTAIN, PENCIL): one closed polygon with round caps (8 segments
 * each) and miter joins limited to 2× half-width, falling back to round joins. The polygon may self-intersect;
 * fill it with the winding rule.
 */
class VariableWidthOutline(private val pen: PenType) : OutlineGenerator {
    override fun outline(points: StrokePoints, style: InkStyle): InkGeometry {
        require(points.size >= 1) { "empty stroke" }
        val widths = PenWidths.forStroke(pen, points, style.widthPt)
        val (xs, ys, hs) = dedupe(points, widths)
        val n = hs.size
        if (n == 1) return InkGeometry.Fill(circle(xs[0], ys[0], hs[0], CIRCLE_SEGMENTS))

        val out = FloatBuilder(n * 8 + 40)
        val dx = FloatArray(n - 1)
        val dy = FloatArray(n - 1)
        for (i in 0 until n - 1) {
            val l = hypot(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
            dx[i] = (xs[i + 1] - xs[i]) / l
            dy[i] = (ys[i + 1] - ys[i]) / l
        }
        // Left side forward.
        side(out, xs, ys, hs, dx, dy, 1f, false)
        // End cap, left → right through the direction of travel.
        cap(out, xs[n - 1], ys[n - 1], hs[n - 1], atan2(dy[n - 2], dx[n - 2]) + PI.toFloat() / 2f)
        // Right side backward.
        side(out, xs, ys, hs, dx, dy, -1f, true)
        // Start cap, right → left through the opposite direction.
        cap(out, xs[0], ys[0], hs[0], atan2(-dy[0], -dx[0]) + PI.toFloat() / 2f)
        return InkGeometry.Fill(out.toArray())
    }

    /** Emits one side of the stroke. [sign] = +1 left, -1 right. When [reverse] the points are emitted from last to first. */
    private fun side(
        out: FloatBuilder, xs: FloatArray, ys: FloatArray, hs: FloatArray,
        dx: FloatArray, dy: FloatArray, sign: Float, reverse: Boolean,
    ) {
        val n = hs.size
        val tmp = FloatBuilder(n * 4)
        for (i in 0 until n) {
            val h = hs[i]
            when {
                i == 0 -> tmp.add(xs[0] - dy[0] * h * sign, ys[0] + dx[0] * h * sign)
                i == n - 1 -> tmp.add(xs[i] - dy[i - 1] * h * sign, ys[i] + dx[i - 1] * h * sign)
                else -> join(tmp, xs[i], ys[i], h, dx[i - 1], dy[i - 1], dx[i], dy[i], sign)
            }
        }
        val a = tmp.toArray()
        if (!reverse) for (k in a.indices step 2) out.add(a[k], a[k + 1])
        else for (k in a.size - 2 downTo 0 step 2) out.add(a[k], a[k + 1])
    }

    private fun join(
        out: FloatBuilder, x: Float, y: Float, h: Float,
        d1x: Float, d1y: Float, d2x: Float, d2y: Float, sign: Float,
    ) {
        val n1x = -d1y * sign; val n1y = d1x * sign
        val n2x = -d2y * sign; val n2y = d2x * sign
        val cross = d1x * d2y - d1y * d2x
        val dot = d1x * d2x + d1y * d2y
        // Straight enough: a single offset point along the averaged normal.
        if (kotlin.math.abs(cross) < 1e-4f && dot > 0f) { out.add(x + n1x * h, y + n1y * h); return }
        val mx = n1x + n2x; val my = n1y + n2y
        val ml = hypot(mx, my)
        val cosHalf = ml / 2f   // cos of half the turn angle
        val miterLen = if (cosHalf > 1e-4f) h / cosHalf else Float.MAX_VALUE
        if (ml > 1e-4f && miterLen <= 2f * h) {
            out.add(x + mx / ml * miterLen, y + my / ml * miterLen)
            return
        }
        // Inner side of the turn (offset towards the turn centre) just emits both offsets; the outer side gets a round join.
        val outer = cross * sign < 0f
        if (!outer) {
            out.add(x + n1x * h, y + n1y * h); out.add(x + n2x * h, y + n2y * h)
            return
        }
        val a1 = atan2(n1y, n1x)
        var delta = atan2(n2y, n2x) - a1
        while (delta > PI) delta -= 2 * PI.toFloat()
        while (delta < -PI) delta += 2 * PI.toFloat()
        val steps = maxOf(1, (kotlin.math.abs(delta) / (PI.toFloat() / 8f)).toInt() + 1)
        for (k in 0..steps) {
            val a = a1 + delta * k / steps
            out.add(x + cos(a) * h, y + sin(a) * h)
        }
    }

    /** Half circle around (x, y) starting at [startAngle] and turning clockwise (decreasing angle) by 180°, 8 segments. */
    private fun cap(out: FloatBuilder, x: Float, y: Float, h: Float, startAngle: Float) {
        for (k in 0..CAP_SEGMENTS) {
            val a = startAngle - PI.toFloat() * k / CAP_SEGMENTS
            out.add(x + cos(a) * h, y + sin(a) * h)
        }
    }

    private fun circle(x: Float, y: Float, h: Float, segs: Int): FloatArray {
        val o = FloatArray(segs * 2)
        for (k in 0 until segs) {
            val a = 2f * PI.toFloat() * k / segs
            o[2 * k] = x + cos(a) * h; o[2 * k + 1] = y + sin(a) * h
        }
        return o
    }

    /** Drops points that coincide with their predecessor; returns x, y and half-widths. */
    private fun dedupe(p: StrokePoints, w: FloatArray): Triple<FloatArray, FloatArray, FloatArray> {
        val xs = FloatBuilder(p.size); val ys = FloatBuilder(p.size); val hs = FloatBuilder(p.size)
        for (i in 0 until p.size) {
            val x = p.xy[2 * i]; val y = p.xy[2 * i + 1]
            if (xs.size > 0 && hypot(x - xs.last(), y - ys.last()) < 1e-4f) continue
            xs.add(x); ys.add(y); hs.add(w[i] / 2f)
        }
        return Triple(xs.toArray(), ys.toArray(), hs.toArray())
    }

    private companion object {
        const val CAP_SEGMENTS = 8
        const val CIRCLE_SEGMENTS = 16
    }
}

/** Minimal growable float list. */
internal class FloatBuilder(capacity: Int = 16) {
    private var a = FloatArray(maxOf(capacity, 4))
    var size = 0
        private set

    fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun add(x: Float, y: Float) { add(x); add(y) }
    fun last() = a[size - 1]
    fun toArray(): FloatArray = a.copyOf(size)
}
