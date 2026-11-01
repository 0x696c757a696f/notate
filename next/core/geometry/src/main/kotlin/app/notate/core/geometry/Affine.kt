package app.notate.core.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2D affine transform `x' = a·x + c·y + e`, `y' = b·x + d·y + f`, i.e. the matrix `[a c e; b d f; 0 0 1]`.
 *
 * Mapping to `android.graphics.Matrix` values: a = MSCALE_X, c = MSKEW_X, e = MTRANS_X, b = MSKEW_Y, d = MSCALE_Y, f = MTRANS_Y.
 * `this * o` applies [o] first, then this (same as `Matrix.preConcat`). Immutable, thread-safe.
 */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {

    operator fun times(o: Affine): Affine = Affine(
        a = a * o.a + c * o.b,
        b = b * o.a + d * o.b,
        c = a * o.c + c * o.d,
        d = b * o.c + d * o.d,
        e = a * o.e + c * o.f + e,
        f = b * o.e + d * o.f + f,
    )

    /** Inverse transform, or null when singular (determinant ~ 0). */
    fun invert(): Affine? {
        val det = a * d - b * c
        if (abs(det) < 1e-12f || det.isNaN()) return null
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
    }

    fun map(x: Float, y: Float): Vec2 = Vec2(a * x + c * y + e, b * x + d * y + f)

    /** Maps interleaved x,y values into a new array. */
    fun mapPoints(xy: FloatArray): FloatArray {
        val out = FloatArray(xy.size)
        var i = 0
        while (i + 1 < xy.size) {
            out[i] = a * xy[i] + c * xy[i + 1] + e
            out[i + 1] = b * xy[i] + d * xy[i + 1] + f
            i += 2
        }
        return out
    }

    /** Bounding box of the four transformed corners. */
    fun mapRect(r: Rect): Rect {
        val p = mapPoints(floatArrayOf(r.l, r.t, r.r, r.t, r.r, r.b, r.l, r.b))
        var l = p[0]; var rr = p[0]; var t = p[1]; var b = p[1]
        for (i in 2 until 8 step 2) {
            l = min(l, p[i]); rr = max(rr, p[i]); t = min(t, p[i + 1]); b = max(b, p[i + 1])
        }
        return Rect(l, t, rr, b)
    }

    /** Length of the transformed x unit vector, `sqrt(a²+b²)`; used to scale line widths. */
    val scaleX: Float get() = sqrt(a * a + b * b)

    /** Length of the transformed y unit vector, `sqrt(c²+d²)`. */
    val scaleY: Float get() = sqrt(c * c + d * d)

    /** Rotation of the x axis in degrees (clockwise on a y-down screen), `atan2(b, a)`. */
    val rotationDeg: Float get() = Math.toDegrees(atan2(b, a).toDouble()).toFloat()

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)

        fun translate(dx: Float, dy: Float) = Affine(1f, 0f, 0f, 1f, dx, dy)

        fun scale(sx: Float, sy: Float, px: Float = 0f, py: Float = 0f) =
            Affine(sx, 0f, 0f, sy, px - sx * px, py - sy * py)

        /** Rotation by [deg] around (px, py); positive is clockwise on a y-down screen. */
        fun rotate(deg: Float, px: Float = 0f, py: Float = 0f): Affine {
            val rad = Math.toRadians(deg.toDouble())
            val cs = cos(rad).toFloat()
            val sn = sin(rad).toFloat()
            return Affine(cs, sn, -sn, cs, px - cs * px + sn * py, py - sn * px - cs * py)
        }
    }
}
