package com.alexdremov.notate.util

import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns roughly drawn polygons into the "intended" ones, the way note apps beautify shapes.
 *
 * Triangles: near-equilateral becomes equilateral, a near-right angle becomes exactly 90°, two nearly
 * equal sides become equal (isosceles). Quadrilaterals: nearly parallel opposite sides become parallel
 * (parallelogram / trapezoid), nearly equal sides become equal (rhombus / isosceles trapezoid).
 * Any polygon whose side is nearly horizontal or vertical is rotated to make it exact. Anything clearly
 * irregular keeps its drawn corners.
 */
object ShapeBeautifier {
    /** Max deviation of a corner from 90° (or 60°) to snap, in degrees. */
    const val ANGLE_TOLERANCE_DEG = 10.0

    /** Max relative length difference for sides to count as equal. */
    const val SIDE_TOLERANCE = 0.1f

    /** Max tilt of a side from horizontal/vertical to straighten the whole triangle. */
    const val LEVEL_TOLERANCE_DEG = 8.0

    fun triangle(vertices: List<PointF>): List<PointF> {
        if (vertices.size != 3) return vertices
        var v = vertices.map { PointF(it.x, it.y) }
        val angles = angles(v)

        v =
            when {
                angles.all { abs(it - 60.0) < ANGLE_TOLERANCE_DEG } -> equilateral(v)
                angles.any { abs(it - 90.0) < ANGLE_TOLERANCE_DEG } -> rightAngle(v, angles.indexOfFirst { abs(it - 90.0) < ANGLE_TOLERANCE_DEG })
                else -> isosceles(v) ?: v
            }
        return level(v)
    }

    /** Interior angle at each vertex, in degrees. */
    fun angles(v: List<PointF>): List<Double> =
        v.indices.map { i ->
            val p = v[(i + 2) % 3]
            val c = v[i]
            val n = v[(i + 1) % 3]
            val a1 = atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())
            val a2 = atan2((n.y - c.y).toDouble(), (n.x - c.x).toDouble())
            var d = abs(a1 - a2)
            if (d > PI) d = 2 * PI - d
            d * 180.0 / PI
        }

    private fun dist(
        a: PointF,
        b: PointF,
    ) = hypot(b.x - a.x, b.y - a.y)

    private fun centroid(v: List<PointF>) = PointF(v.map { it.x }.average().toFloat(), v.map { it.y }.average().toFloat())

    /** Equilateral triangle with the same centroid, average size and the orientation of the first vertex. */
    private fun equilateral(v: List<PointF>): List<PointF> {
        val c = centroid(v)
        val r = v.map { dist(it, c) }.average()
        val start = atan2((v[0].y - c.y).toDouble(), (v[0].x - c.x).toDouble())
        // Keep the drawn winding order.
        val dir = if (cross(v) >= 0f) 1 else -1
        return (0 until 3).map { i ->
            val a = start + dir * i * 2 * PI / 3
            PointF((c.x + r * cos(a)).toFloat(), (c.y + r * sin(a)).toFloat())
        }
    }

    /** Makes the corner at [corner] exactly 90°, keeping leg lengths; equalizes legs if nearly equal. */
    private fun rightAngle(
        v: List<PointF>,
        corner: Int,
    ): List<PointF> {
        val c = v[corner]
        val a = v[(corner + 1) % 3]
        val b = v[(corner + 2) % 3]
        var la = dist(c, a)
        var lb = dist(c, b)
        if (la <= 0f || lb <= 0f) return v
        if (abs(la - lb) / maxOf(la, lb) < SIDE_TOLERANCE) {
            la = (la + lb) / 2f
            lb = la
        }
        val angA = atan2((a.y - c.y).toDouble(), (a.x - c.x).toDouble())
        val angB = atan2((b.y - c.y).toDouble(), (b.x - c.x).toDouble())
        // Signed turn from leg A to leg B, set to exactly ±90° by splitting the error between both legs.
        var turn = angB - angA
        while (turn > PI) turn -= 2 * PI
        while (turn < -PI) turn += 2 * PI
        val target = if (turn >= 0) PI / 2 else -PI / 2
        val fix = (target - turn) / 2
        val newA = angA - fix
        val newB = angB + fix
        val out = MutableList(3) { PointF() }
        out[corner] = PointF(c.x, c.y)
        out[(corner + 1) % 3] = PointF((c.x + la * cos(newA)).toFloat(), (c.y + la * sin(newA)).toFloat())
        out[(corner + 2) % 3] = PointF((c.x + lb * cos(newB)).toFloat(), (c.y + lb * sin(newB)).toFloat())
        return out
    }

    /** Moves the apex onto the base's perpendicular bisector when its two sides are nearly equal. */
    private fun isosceles(v: List<PointF>): List<PointF>? {
        var best = -1
        var bestDiff = Float.MAX_VALUE
        for (apex in 0 until 3) {
            val s1 = dist(v[apex], v[(apex + 1) % 3])
            val s2 = dist(v[apex], v[(apex + 2) % 3])
            val diff = abs(s1 - s2) / maxOf(s1, s2, 1e-3f)
            if (diff < bestDiff) {
                bestDiff = diff
                best = apex
            }
        }
        if (best < 0 || bestDiff >= SIDE_TOLERANCE) return null
        val p = v[(best + 1) % 3]
        val q = v[(best + 2) % 3]
        val base = dist(p, q)
        if (base <= 0f) return null
        val mx = (p.x + q.x) / 2f
        val my = (p.y + q.y) / 2f
        // Unit normal of the base
        val nx = -(q.y - p.y) / base
        val ny = (q.x - p.x) / base
        val h = (v[best].x - mx) * nx + (v[best].y - my) * ny
        val out = v.toMutableList()
        out[best] = PointF(mx + nx * h, my + ny * h)
        return out
    }

    /** Rotates around the centroid so the side closest to horizontal or vertical becomes exactly so. */
    fun level(v: List<PointF>): List<PointF> {
        if (v.size < 2) return v
        var bestTilt = Double.MAX_VALUE
        for (i in v.indices) {
            val a = v[i]
            val b = v[(i + 1) % v.size]
            val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            // Signed distance to the nearest multiple of 90°
            val quarter = PI / 2
            val tilt = ang - Math.round(ang / quarter) * quarter
            if (abs(tilt) < abs(bestTilt)) bestTilt = tilt
        }
        if (abs(bestTilt) * 180.0 / PI >= LEVEL_TOLERANCE_DEG || bestTilt == 0.0) return v
        val c = centroid(v)
        val cosR = cos(-bestTilt).toFloat()
        val sinR = sin(-bestTilt).toFloat()
        return v.map {
            val dx = it.x - c.x
            val dy = it.y - c.y
            PointF(c.x + dx * cosR - dy * sinR, c.y + dx * sinR + dy * cosR)
        }
    }

    /**
     * Beautifies a non-rectangular quadrilateral: both pairs of opposite sides nearly parallel gives a
     * parallelogram (a rhombus if adjacent sides are nearly equal); one pair gives a trapezoid (isosceles
     * if its legs are nearly equal).
     */
    fun quadrilateral(vertices: List<PointF>): List<PointF> {
        if (vertices.size != 4) return vertices
        val v = vertices.map { PointF(it.x, it.y) }
        val e = (0 until 4).map { i -> PointF(v[(i + 1) % 4].x - v[i].x, v[(i + 1) % 4].y - v[i].y) }
        val par02 = antiParallel(e[0], e[2])
        val par13 = antiParallel(e[1], e[3])
        val result =
            when {
                par02 && par13 -> parallelogram(v, e)
                par02 -> trapezoid(v, 0)
                par13 -> trapezoid(v, 1)
                else -> v
            }
        return level(result)
    }

    /** True if [a] and [b] point in nearly opposite directions (opposite sides of a polygon). */
    private fun antiParallel(
        a: PointF,
        b: PointF,
    ): Boolean {
        val la = hypot(a.x, a.y)
        val lb = hypot(b.x, b.y)
        if (la <= 0f || lb <= 0f) return false
        val cosAngle = -(a.x * b.x + a.y * b.y) / (la * lb)
        return cosAngle > cos(ANGLE_TOLERANCE_DEG * PI / 180.0)
    }

    private fun parallelogram(
        v: List<PointF>,
        e: List<PointF>,
    ): List<PointF> {
        val c = centroid(v)
        var a = PointF((e[0].x - e[2].x) / 2f, (e[0].y - e[2].y) / 2f)
        var b = PointF((e[1].x - e[3].x) / 2f, (e[1].y - e[3].y) / 2f)
        val la = hypot(a.x, a.y)
        val lb = hypot(b.x, b.y)
        if (la > 0f && lb > 0f && abs(la - lb) / maxOf(la, lb) < SIDE_TOLERANCE) {
            val l = (la + lb) / 2f
            a = PointF(a.x / la * l, a.y / la * l)
            b = PointF(b.x / lb * l, b.y / lb * l)
        }
        val v0 = PointF(c.x - (a.x + b.x) / 2f, c.y - (a.y + b.y) / 2f)
        val v1 = PointF(v0.x + a.x, v0.y + a.y)
        val v2 = PointF(v1.x + b.x, v1.y + b.y)
        val v3 = PointF(v0.x + b.x, v0.y + b.y)
        return listOf(v0, v1, v2, v3)
    }

    /**
     * Makes side [first] (v[first] -> v[first+1]) and its opposite side exactly parallel, keeping their
     * midpoints and lengths. Equal legs make the trapezoid symmetric.
     */
    private fun trapezoid(
        v: List<PointF>,
        first: Int,
    ): List<PointF> {
        val p0 = v[first]
        val p1 = v[(first + 1) % 4]
        val q0 = v[(first + 2) % 4]
        val q1 = v[(first + 3) % 4]
        // Shared direction: average of side p0->p1 and the reversed opposite side q1->q0.
        val dx1 = p1.x - p0.x
        val dy1 = p1.y - p0.y
        val dx2 = q0.x - q1.x
        val dy2 = q0.y - q1.y
        val l1 = hypot(dx1, dy1)
        val l2 = hypot(dx2, dy2)
        if (l1 <= 0f || l2 <= 0f) return v
        var dx = dx1 / l1 + dx2 / l2
        var dy = dy1 / l1 + dy2 / l2
        val dl = hypot(dx, dy)
        if (dl <= 0f) return v
        dx /= dl
        dy /= dl

        val m1 = PointF((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
        var m2 = PointF((q0.x + q1.x) / 2f, (q0.y + q1.y) / 2f)
        val leg1 = hypot(q0.x - p1.x, q0.y - p1.y)
        val leg2 = hypot(p0.x - q1.x, p0.y - q1.y)
        if (abs(leg1 - leg2) / maxOf(leg1, leg2, 1e-3f) < SIDE_TOLERANCE) {
            // Isosceles: slide the opposite side so both midpoints sit on one perpendicular.
            val along = (m2.x - m1.x) * dx + (m2.y - m1.y) * dy
            m2 = PointF(m2.x - dx * along, m2.y - dy * along)
        }
        val out = MutableList(4) { PointF() }
        out[first] = PointF(m1.x - dx * l1 / 2f, m1.y - dy * l1 / 2f)
        out[(first + 1) % 4] = PointF(m1.x + dx * l1 / 2f, m1.y + dy * l1 / 2f)
        out[(first + 2) % 4] = PointF(m2.x + dx * l2 / 2f, m2.y + dy * l2 / 2f)
        out[(first + 3) % 4] = PointF(m2.x - dx * l2 / 2f, m2.y - dy * l2 / 2f)
        return out
    }

    private fun cross(v: List<PointF>) = (v[1].x - v[0].x) * (v[2].y - v[0].y) - (v[1].y - v[0].y) * (v[2].x - v[0].x)
}
