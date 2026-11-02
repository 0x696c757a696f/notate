package app.notate.core.ink.guide

import app.notate.core.geometry.Vec2
import app.notate.core.model.Units
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A straight ruler lying on a page. Coordinates are page coordinates (pt); [angleDeg] is the direction of the long axis in the
 * page's y-down system (positive = clockwise on screen).
 */
data class RulerState(
    val cx: Float,
    val cy: Float,
    val angleDeg: Float,
    val lengthPt: Float = Units.mm(150f),
    val widthPt: Float = Units.mm(18f),
)

/** A half-disc protractor: its straight base runs through the centre at [angleDeg]; the disc lies on the left of that direction. */
data class ProtractorState(
    val cx: Float,
    val cy: Float,
    val angleDeg: Float,
    val radiusPt: Float = Units.mm(60f),
)

/** Where a stroke that starts next to a guide gets pulled to. Page coordinates (pt). */
sealed interface GuideSnap {
    /** Straight edge through ([x0], [y0]) along the unit direction ([dx], [dy]). */
    data class Line(val x0: Float, val y0: Float, val dx: Float, val dy: Float) : GuideSnap

    /** Circular arc around ([cx], [cy]). */
    data class Arc(val cx: Float, val cy: Float, val radius: Float) : GuideSnap

    /** Ray from ([cx], [cy]); its angle from [baseAngleDeg] snaps to whole degrees. */
    data class Ray(val cx: Float, val cy: Float, val baseAngleDeg: Float) : GuideSnap
}

/** Snapping and projection onto ruler and protractor geometry. Pure. */
object Guides {
    /** A start point within this many centre-tolerances of the protractor centre draws a ray. */
    const val CENTER_FACTOR = 1.5f

    /** The long edge of [r] nearest to (x, y) when the point is within [tolPt] of it (and not beyond the ends), else null. */
    fun rulerSnap(r: RulerState, x: Float, y: Float, tolPt: Float): GuideSnap.Line? {
        val a = Math.toRadians(r.angleDeg.toDouble())
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        val px = x - r.cx
        val py = y - r.cy
        val u = px * dx + py * dy
        val v = -px * dy + py * dx
        if (abs(u) > r.lengthPt / 2) return null
        val top = abs(v + r.widthPt / 2)
        val bottom = abs(v - r.widthPt / 2)
        val edge = when {
            top <= bottom && top < tolPt -> -r.widthPt / 2
            bottom < tolPt -> r.widthPt / 2
            else -> return null
        }
        return GuideSnap.Line(r.cx - dy * edge, r.cy + dx * edge, dx, dy)
    }

    /** Ray when (x, y) is near the centre; Arc near the arc; Line near the base; else null. */
    fun protractorSnap(p: ProtractorState, x: Float, y: Float, tolPt: Float): GuideSnap? {
        val a = Math.toRadians(p.angleDeg.toDouble())
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        val px = x - p.cx
        val py = y - p.cy
        val u = px * dx + py * dy
        val v = -px * dy + py * dx
        val d = hypot(u, v)
        if (d < tolPt * CENTER_FACTOR) return GuideSnap.Ray(p.cx, p.cy, p.angleDeg)
        if (abs(d - p.radiusPt) < tolPt && v <= tolPt) return GuideSnap.Arc(p.cx, p.cy, p.radiusPt)
        if (abs(v) < tolPt && abs(u) <= p.radiusPt) return GuideSnap.Line(p.cx, p.cy, dx, dy)
        return null
    }

    /** The point of [snap] nearest to (x, y); for a ray: the point at the same distance from the centre on the whole-degree ray. */
    fun project(snap: GuideSnap, x: Float, y: Float): Vec2 = when (snap) {
        is GuideSnap.Line -> {
            val t = (x - snap.x0) * snap.dx + (y - snap.y0) * snap.dy
            Vec2(snap.x0 + snap.dx * t, snap.y0 + snap.dy * t)
        }
        is GuideSnap.Arc -> {
            val l = hypot(x - snap.cx, y - snap.cy)
            if (l < 1e-4f) Vec2(snap.cx + snap.radius, snap.cy)
            else Vec2(snap.cx + (x - snap.cx) / l * snap.radius, snap.cy + (y - snap.cy) / l * snap.radius)
        }
        is GuideSnap.Ray -> {
            val len = hypot(x - snap.cx, y - snap.cy)
            val a = Math.toRadians(rayAngleDeg(snap, x, y).toDouble())
            Vec2(snap.cx + len * cos(a).toFloat(), snap.cy + len * sin(a).toFloat())
        }
    }

    /** Absolute angle (degrees, page y-down) of the whole-degree ray of [snap] that points towards (x, y). */
    fun rayAngleDeg(snap: GuideSnap.Ray, x: Float, y: Float): Float {
        val raw = Math.toDegrees(atan2((y - snap.cy).toDouble(), (x - snap.cx).toDouble())) - snap.baseAngleDeg
        return snap.baseAngleDeg + Math.round(raw).toFloat()
    }

    /** [deg] moved to the nearest multiple of [step] when it is within [tol] of it. */
    fun snapAngle(deg: Float, step: Float = 15f, tol: Float = 3f): Float {
        val nearest = (deg / step).roundToInt() * step
        return if (abs(deg - nearest) <= tol) nearest else deg
    }

    /** Direction of a line at [angleDeg] as a maths protractor reads it (counter-clockwise, 0 until 180). */
    fun lineDegrees(angleDeg: Float): Int {
        var d = angleDeg % 180f
        if (d < 0f) d += 180f
        return ((180f - d) % 180f).roundToInt() % 180
    }

    /** Angle between two rays given by absolute angles, 0..180. */
    fun betweenDeg(a: Float, b: Float): Float {
        var d = abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }

    internal const val ARC_STEP = (PI / 90).toFloat()   // 2°
}
