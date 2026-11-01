package app.notate.core.geometry

import kotlin.math.sqrt

/**
 * Partial-eraser core: removes the parts of a polyline covered by a swept path of circles and returns the remaining pieces.
 * Pure function; any thread.
 */
object PolylineCutter {
    /** A surviving piece spanning parameters [fromT]..[toT] of the original polyline, where t = segment index + fraction in segment. */
    data class Piece(val fromT: Float, val toT: Float)

    private const val EPS = 1e-4f

    /**
     * Cuts [xy] by [circles] (`cx,cy,r,…`); a polyline point is covered when it lies within `r + halfWidth` of a circle centre.
     * Pieces shorter than a tiny epsilon are dropped. A single-point polyline yields one zero-length piece if uncovered.
     */
    fun cut(xy: FloatArray, halfWidth: Float, circles: FloatArray): List<Piece> {
        val n = xy.size / 2
        if (n == 0) return emptyList()
        val total = (n - 1).toFloat()
        val covered = ArrayList<FloatArray>()   // [from, to] in t, in increasing order after sort
        if (n == 1) {
            var ci = 0
            while (ci + 2 < circles.size) {
                val rr = circles[ci + 2] + halfWidth
                val dx = xy[0] - circles[ci]; val dy = xy[1] - circles[ci + 1]
                if (dx * dx + dy * dy <= rr * rr) return emptyList()
                ci += 3
            }
            return listOf(Piece(0f, 0f))
        }
        for (i in 0 until n - 1) {
            val ax = xy[2 * i]; val ay = xy[2 * i + 1]
            val dx = xy[2 * i + 2] - ax; val dy = xy[2 * i + 3] - ay
            val a = dx * dx + dy * dy
            var ci = 0
            while (ci + 2 < circles.size) {
                val rr = circles[ci + 2] + halfWidth
                val fx = ax - circles[ci]; val fy = ay - circles[ci + 1]
                val c = fx * fx + fy * fy - rr * rr
                if (a == 0f) {
                    if (c <= 0f) covered.add(floatArrayOf(i.toFloat(), i + 1f))
                } else {
                    val b = 2f * (fx * dx + fy * dy)
                    val disc = b * b - 4f * a * c
                    if (disc >= 0f) {
                        val sq = sqrt(disc)
                        val s0 = ((-b - sq) / (2f * a)).coerceIn(0f, 1f)
                        val s1 = ((-b + sq) / (2f * a)).coerceIn(0f, 1f)
                        val lo = (-b - sq) / (2f * a)
                        val hi = (-b + sq) / (2f * a)
                        if (hi >= 0f && lo <= 1f && s1 >= s0) covered.add(floatArrayOf(i + s0, i + s1))
                    }
                }
                ci += 3
            }
        }
        covered.sortBy { it[0] }
        val merged = ArrayList<FloatArray>()
        for (iv in covered) {
            val last = merged.lastOrNull()
            if (last != null && iv[0] <= last[1] + EPS) { if (iv[1] > last[1]) last[1] = iv[1] } else merged.add(iv.copyOf())
        }
        val pieces = ArrayList<Piece>()
        var cursor = 0f
        for (iv in merged) {
            if (iv[0] - cursor > EPS) pieces.add(Piece(cursor, iv[0]))
            if (iv[1] > cursor) cursor = iv[1]
        }
        if (total - cursor > EPS) pieces.add(Piece(cursor, total))
        return pieces
    }
}
