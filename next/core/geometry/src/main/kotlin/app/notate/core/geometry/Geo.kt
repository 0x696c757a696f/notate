package app.notate.core.geometry

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Stateless geometry helpers over interleaved `[x0,y0,x1,y1,…]` float arrays. Thread-safe. */
object Geo {

    fun distPointSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Distance to the closest point of the polyline; a single point degenerates to point distance; empty gives +Infinity. */
    fun distPointPolyline(px: Float, py: Float, xy: FloatArray): Float {
        val n = xy.size / 2
        if (n == 0) return Float.POSITIVE_INFINITY
        if (n == 1) return hypot(px - xy[0], py - xy[1])
        var best = Float.POSITIVE_INFINITY
        for (i in 0 until n - 1) {
            val d = distPointSegment(px, py, xy[2 * i], xy[2 * i + 1], xy[2 * i + 2], xy[2 * i + 3])
            if (d < best) best = d
        }
        return best
    }

    private fun orient(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private fun onSegment(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Boolean =
        px >= min(ax, bx) && px <= max(ax, bx) && py >= min(ay, by) && py <= max(ay, by)

    /** True when segments AB and CD share at least one point (touching and collinear overlap count). */
    fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Boolean {
        val o1 = orient(ax, ay, bx, by, cx, cy)
        val o2 = orient(ax, ay, bx, by, dx, dy)
        val o3 = orient(cx, cy, dx, dy, ax, ay)
        val o4 = orient(cx, cy, dx, dy, bx, by)
        if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) return true
        if (o1 == 0f && onSegment(ax, ay, bx, by, cx, cy)) return true
        if (o2 == 0f && onSegment(ax, ay, bx, by, dx, dy)) return true
        if (o3 == 0f && onSegment(cx, cy, dx, dy, ax, ay)) return true
        if (o4 == 0f && onSegment(cx, cy, dx, dy, bx, by)) return true
        return false
    }

    /** Even-odd point-in-polygon test; the polygon is implicitly closed. */
    fun pointInPolygon(px: Float, py: Float, poly: FloatArray): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]; val yi = poly[2 * i + 1]
            val xj = poly[2 * j]; val yj = poly[2 * j + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Fraction (0..1) of polyline points that lie inside [poly]; used by the lasso "≥ 50 % of points" rule. */
    fun fractionInside(xy: FloatArray, poly: FloatArray): Float {
        val n = xy.size / 2
        if (n == 0) return 0f
        var inside = 0
        for (i in 0 until n) if (pointInPolygon(xy[2 * i], xy[2 * i + 1], poly)) inside++
        return inside.toFloat() / n
    }

    /**
     * Ramer–Douglas–Peucker simplification. Returns the sorted indices of the points to keep (first and last always kept).
     * Points flagged in [keep] are never removed.
     */
    fun rdp(xy: FloatArray, epsilon: Float, keep: BooleanArray? = null): IntArray {
        val n = xy.size / 2
        if (n <= 2) return IntArray(n) { it }
        val marked = BooleanArray(n)
        marked[0] = true
        marked[n - 1] = true
        if (keep != null) for (i in 0 until min(n, keep.size)) if (keep[i]) marked[i] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = -1f
            var idx = -1
            for (i in s + 1 until e) {
                val d = distPointSegment(xy[2 * i], xy[2 * i + 1], xy[2 * s], xy[2 * s + 1], xy[2 * e], xy[2 * e + 1])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx < 0) continue
            // Keep splitting if the farthest point exceeds epsilon or a forced point lies inside (s, e).
            var forced = -1
            for (i in s + 1 until e) if (marked[i]) { forced = i; break }
            if (maxD > epsilon) {
                marked[idx] = true
                stack.addLast(intArrayOf(s, idx))
                stack.addLast(intArrayOf(idx, e))
            } else if (forced >= 0) {
                stack.addLast(intArrayOf(s, forced))
                stack.addLast(intArrayOf(forced, e))
            }
        }
        var count = 0
        for (m in marked) if (m) count++
        val out = IntArray(count)
        var k = 0
        for (i in 0 until n) if (marked[i]) out[k++] = i
        return out
    }

    /** Points spaced [spacing] apart along the polyline, starting at the first point and ending at the last point. */
    fun resample(xy: FloatArray, spacing: Float): FloatArray {
        val n = xy.size / 2
        if (n == 0) return FloatArray(0)
        require(spacing > 0f) { "spacing must be positive" }
        val out = ArrayList<Float>()
        out.add(xy[0]); out.add(xy[1])
        var carry = 0f   // distance already travelled since the last emitted point
        for (i in 0 until n - 1) {
            val ax = xy[2 * i]; val ay = xy[2 * i + 1]
            val bx = xy[2 * i + 2]; val by = xy[2 * i + 3]
            val segLen = hypot(bx - ax, by - ay)
            if (segLen == 0f) continue
            var pos = spacing - carry
            while (pos <= segLen) {
                val t = pos / segLen
                out.add(ax + t * (bx - ax)); out.add(ay + t * (by - ay))
                pos += spacing
            }
            carry = segLen - (pos - spacing)
        }
        val lx = xy[2 * n - 2]; val ly = xy[2 * n - 1]
        if (out[out.size - 2] != lx || out[out.size - 1] != ly) { out.add(lx); out.add(ly) }
        return out.toFloatArray()
    }

    fun polylineLength(xy: FloatArray): Float {
        var len = 0f
        var i = 0
        while (i + 3 < xy.size) {
            len += hypot(xy[i + 2] - xy[i], xy[i + 3] - xy[i + 1])
            i += 2
        }
        return len
    }
}
