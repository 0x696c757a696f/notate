package app.notate.core.ink.shape

import app.notate.core.geometry.Vec2
import app.notate.core.model.ShapeKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure math for the shape tool (ported from the legacy `ShapeGeometry`): angle snapping, tick spacing and
 * [paramsFromDrag], which turns a pen-down/pen-up drag into `Shape.params` (layout in plans/rewrite/03 §2).
 * Drawable geometry comes from [ShapeOutline]. Cheap enough to call on every pen move for the live preview.
 */
object ShapeGeometry {
    /** Drags shorter than this (pt) are treated as a tap and produce nothing. */
    const val MIN_DRAG = 8f

    /**
     * Rotates [end] around [start] onto the nearest multiple of [stepDeg] degrees when within [toleranceDeg], so
     * near-horizontal/vertical/45° lines come out exact.
     */
    fun snapAngle(start: Vec2, end: Vec2, stepDeg: Double = 15.0, toleranceDeg: Double = 4.0): Vec2 {
        val dx = (end.x - start.x).toDouble()
        val dy = (end.y - start.y).toDouble()
        val len = hypot(dx, dy)
        if (len == 0.0) return end
        val angle = atan2(dy, dx)
        val step = stepDeg * PI / 180.0
        val snapped = (angle / step).roundToInt() * step
        if (abs(snapped - angle) > toleranceDeg * PI / 180.0) return end
        return Vec2((start.x + len * cos(snapped)).toFloat(), (start.y + len * sin(snapped)).toFloat())
    }

    /** Rounds [raw] to 1, 2 or 5 times a power of ten (axis tick spacing). */
    fun niceStep(raw: Float): Float {
        if (raw <= 0f || raw.isNaN()) return 1f
        val base = 10.0.pow(floor(log10(raw.toDouble())))
        val f = raw / base
        val nice = when {
            f < 1.5 -> 1.0
            f < 3.5 -> 2.0
            f < 7.5 -> 5.0
            else -> 10.0
        }
        return (nice * base).toFloat()
    }

    /**
     * `Shape.params` for a drag from [start] to [end], or null for a tap or an unsupported [kind]
     * (DOUBLE_ARROW included in the supported set; POLYLINE has no drag form). [polygonSides] applies to POLYGON.
     */
    fun paramsFromDrag(kind: ShapeKind, start: Vec2, end: Vec2, polygonSides: Int = 5): FloatArray? {
        if (hypot(end.x - start.x, end.y - start.y) < MIN_DRAG) return null
        val minX = min(start.x, end.x); val maxX = max(start.x, end.x)
        val minY = min(start.y, end.y); val maxY = max(start.y, end.y)
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW -> {
                val tip = snapAngle(start, end)
                floatArrayOf(start.x, start.y, tip.x, tip.y)
            }
            ShapeKind.RECT -> floatArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY)
            ShapeKind.TRIANGLE -> floatArrayOf((minX + maxX) / 2f, minY, maxX, maxY, minX, maxY)
            ShapeKind.ELLIPSE -> {
                var rx = (maxX - minX) / 2f
                var ry = (maxY - minY) / 2f
                // A nearly square drag means "circle".
                if (abs(rx - ry) < max(rx, ry) * 0.06f) { val r = (rx + ry) / 2f; rx = r; ry = r }
                floatArrayOf((minX + maxX) / 2f, (minY + maxY) / 2f, rx, ry, 0f)
            }
            ShapeKind.POLYGON -> {
                val sides = polygonSides.coerceIn(3, 12)
                val v = snapAngle(start, end)
                val r = hypot(v.x - start.x, v.y - start.y)
                val a0 = atan2((v.y - start.y).toDouble(), (v.x - start.x).toDouble())
                FloatArray(sides * 2).also {
                    for (i in 0 until sides) {
                        val a = a0 + i * 2.0 * PI / sides
                        it[2 * i] = (start.x + r * cos(a)).toFloat()
                        it[2 * i + 1] = (start.y + r * sin(a)).toFloat()
                    }
                }
            }
            ShapeKind.AXES_2D -> axesParams(minX, minY, maxX, maxY, (minX + maxX) / 2f, (minY + maxY) / 2f)
            ShapeKind.AXES_Q1 -> axesParams(minX, minY, maxX, maxY, minX, maxY)
            ShapeKind.POLYLINE -> null
        }
    }

    private fun axesParams(minX: Float, minY: Float, maxX: Float, maxY: Float, ox: Float, oy: Float): FloatArray {
        val w = maxX - minX
        val h = maxY - minY
        val reference = max(min(maxX - ox, oy - minY), min(w, h) / 2f)
        val step = max(niceStep(reference / 5f), niceStep(max(w, h) / 30f))
        // AXES_2D lengths are the full extent of each axis; AXES_Q1 lengths run from the origin.
        return floatArrayOf(ox, oy, w, h, 0f, step)
    }
}
