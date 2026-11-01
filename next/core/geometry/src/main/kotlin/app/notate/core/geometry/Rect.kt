package app.notate.core.geometry

import kotlin.math.max
import kotlin.math.min

/** Axis-aligned rectangle (left, top, right, bottom), y grows downwards. Empty when `r <= l` or `b <= t`. */
data class Rect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val width: Float get() = r - l
    val height: Float get() = b - t
    val isEmpty: Boolean get() = r <= l || b <= t
    val center: Vec2 get() = Vec2((l + r) / 2f, (t + b) / 2f)

    /** True when the interiors overlap; touching edges do not count and empty rects never intersect. */
    fun intersects(o: Rect): Boolean = !isEmpty && !o.isEmpty && l < o.r && o.l < r && t < o.b && o.t < b
    fun contains(x: Float, y: Float): Boolean = x >= l && x <= r && y >= t && y <= b
    fun contains(o: Rect): Boolean = !o.isEmpty && o.l >= l && o.r <= r && o.t >= t && o.b <= b

    /** Smallest rect covering both; an empty operand is ignored. */
    fun union(o: Rect): Rect = when {
        isEmpty -> o
        o.isEmpty -> this
        else -> Rect(min(l, o.l), min(t, o.t), max(r, o.r), max(b, o.b))
    }

    /** Shrinks by [d] on every side (negative grows). */
    fun inset(d: Float): Rect = Rect(l + d, t + d, r - d, b - d)
    fun offset(dx: Float, dy: Float): Rect = Rect(l + dx, t + dy, r + dx, b + dy)

    companion object {
        val EMPTY = Rect(0f, 0f, 0f, 0f)

        /** Bounding box of interleaved x,y values; [EMPTY] when there are no points. */
        fun ofPoints(xy: FloatArray): Rect {
            if (xy.size < 2) return EMPTY
            var l = xy[0]; var r = xy[0]; var t = xy[1]; var b = xy[1]
            var i = 2
            while (i + 1 < xy.size) {
                val x = xy[i]; val y = xy[i + 1]
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                i += 2
            }
            return Rect(l, t, r, b)
        }

        fun around(x: Float, y: Float, r: Float): Rect = Rect(x - r, y - r, x + r, y + r)
    }
}
