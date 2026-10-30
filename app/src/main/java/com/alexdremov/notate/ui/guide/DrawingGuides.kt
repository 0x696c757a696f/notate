package com.alexdremov.notate.ui.guide

import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where a pen stroke that starts next to a drawing guide gets pulled to. All coordinates are view
 * (screen) pixels: guides float above the page like a physical ruler.
 */
sealed class GuideSnap {
    /** Straight edge through ([x0], [y0]) along the unit direction ([dx], [dy]). */
    data class Line(
        val x0: Float,
        val y0: Float,
        val dx: Float,
        val dy: Float,
    ) : GuideSnap()

    /** Circular arc around ([cx], [cy]). */
    data class Arc(
        val cx: Float,
        val cy: Float,
        val radius: Float,
    ) : GuideSnap()

    /** Ray from ([cx], [cy]); its angle from [baseAngle] snaps to whole degrees. */
    data class Ray(
        val cx: Float,
        val cy: Float,
        val baseAngle: Float,
    ) : GuideSnap()
}

/** Builds the guided stroke (screen points) for a pen that went down at the start and is now at (x, y). */
object GuidePath {
    const val ARC_STEP_RAD = PI / 90 // 2 degrees

    fun line(
        g: GuideSnap.Line,
        startX: Float,
        startY: Float,
        x: Float,
        y: Float,
    ): List<PointF> = listOf(project(g, startX, startY), project(g, x, y))

    fun project(
        g: GuideSnap.Line,
        x: Float,
        y: Float,
    ): PointF {
        val t = (x - g.x0) * g.dx + (y - g.y0) * g.dy
        return PointF(g.x0 + g.dx * t, g.y0 + g.dy * t)
    }

    /** Angle of (x, y) around the arc centre. */
    fun angleOf(
        g: GuideSnap.Arc,
        x: Float,
        y: Float,
    ) = atan2((y - g.cy).toDouble(), (x - g.cx).toDouble())

    /** Smallest signed difference [to] - [from] in (-PI, PI]; used to accumulate the arc sweep. */
    fun angleDelta(
        from: Double,
        to: Double,
    ): Double {
        var d = to - from
        while (d > PI) d -= 2 * PI
        while (d <= -PI) d += 2 * PI
        return d
    }

    /** Arc from [startAngle] sweeping [sweep] radians (may exceed PI; clamped to a full circle). */
    fun arc(
        g: GuideSnap.Arc,
        startAngle: Double,
        sweep: Double,
    ): List<PointF> {
        val s = sweep.coerceIn(-2 * PI, 2 * PI)
        val steps = max(1, (abs(s) / ARC_STEP_RAD).roundToInt())
        return (0..steps).map { i ->
            val a = startAngle + s * i / steps
            PointF((g.cx + g.radius * cos(a)).toFloat(), (g.cy + g.radius * sin(a)).toFloat())
        }
    }

    /** Ray from the centre to the pen's distance, at the nearest whole-degree angle. */
    fun ray(
        g: GuideSnap.Ray,
        x: Float,
        y: Float,
    ): List<PointF> {
        val len = hypot(x - g.cx, y - g.cy)
        val raw = atan2((y - g.cy).toDouble(), (x - g.cx).toDouble()) - g.baseAngle
        val deg = Math.round(Math.toDegrees(raw)).toDouble()
        val a = g.baseAngle + Math.toRadians(deg)
        return listOf(PointF(g.cx, g.cy), PointF((g.cx + len * cos(a)).toFloat(), (g.cy + len * sin(a)).toFloat()))
    }
}

/** Snaps a rotation to the nearest multiple of [stepDeg] when within [toleranceDeg]. */
internal fun snapAngle(
    angle: Float,
    stepDeg: Double,
    toleranceDeg: Double,
): Float {
    val step = Math.toRadians(stepDeg)
    val nearest = Math.round(angle / step) * step
    return if (abs(angle - nearest) < Math.toRadians(toleranceDeg)) nearest.toFloat() else angle
}

/** Angle in degrees, normalised to [0, 180) as a line direction reads on a protractor. */
fun lineDegrees(angle: Float): Int {
    var d = Math.toDegrees(angle.toDouble()) % 180.0
    if (d < 0) d += 180.0
    // Screen y points down: report counter-clockwise angles like a maths protractor.
    val ccw = (180.0 - d) % 180.0
    return ccw.roundToInt() % 180
}

/**
 * A straight ruler floating over the page. One finger moves it, two fingers rotate it (sticking at
 * 0/45/90°). A stroke started next to either long edge is drawn as a straight line along that edge.
 */
class Ruler {
    var cx = 0f
    var cy = 0f
    var angle = 0f
        private set
    var length = 0f
        private set
    var width = 0f
        private set

    fun place(
        viewWidth: Int,
        viewHeight: Int,
        density: Float,
    ) {
        cx = viewWidth / 2f
        cy = viewHeight / 2f
        angle = 0f
        length = hypot(viewWidth.toFloat(), viewHeight.toFloat()) * 1.2f
        width = WIDTH_DP * density
    }

    fun moveBy(
        dx: Float,
        dy: Float,
    ) {
        cx += dx
        cy += dy
    }

    /** Sets the rotation; it sticks to multiples of 45° when close. */
    fun setRotation(raw: Float) {
        angle = snapAngle(raw, 45.0, ROTATION_SNAP_DEG)
    }

    val degrees: Int get() = lineDegrees(angle)

    /** (u, v) in ruler space: u along the ruler, v across it (top edge at -width/2). */
    fun toLocal(
        x: Float,
        y: Float,
    ): PointF {
        val dx = x - cx
        val dy = y - cy
        val c = cos(angle)
        val s = sin(angle)
        return PointF(dx * c + dy * s, -dx * s + dy * c)
    }

    fun toScreen(
        u: Float,
        v: Float,
    ): PointF {
        val c = cos(angle)
        val s = sin(angle)
        return PointF(cx + u * c - v * s, cy + u * s + v * c)
    }

    fun contains(
        x: Float,
        y: Float,
    ): Boolean {
        val p = toLocal(x, y)
        return abs(p.x) <= length / 2 && abs(p.y) <= width / 2
    }

    /** The edge a stroke starting at (x, y) should follow, if it starts within [tolerance] of one. */
    fun snapAt(
        x: Float,
        y: Float,
        tolerance: Float,
    ): GuideSnap.Line? {
        val p = toLocal(x, y)
        if (abs(p.x) > length / 2) return null
        val edgeV =
            when {
                abs(p.y + width / 2) < tolerance -> -width / 2
                abs(p.y - width / 2) < tolerance -> width / 2
                else -> return null
            }
        val o = toScreen(0f, edgeV)
        return GuideSnap.Line(o.x, o.y, cos(angle), sin(angle))
    }

    companion object {
        const val WIDTH_DP = 64f
        const val ROTATION_SNAP_DEG = 3.0
    }
}

/**
 * A 180° protractor: a half disc whose straight base runs through ([cx], [cy]) at [angle]. One finger
 * moves it, two fingers rotate and resize it. Strokes snap to its arc (circular arcs), its base
 * (straight line) or, starting at its centre, to rays at whole-degree angles.
 */
class Protractor {
    var cx = 0f
    var cy = 0f
    var angle = 0f
        private set
    var radius = 0f
        private set
    private var minRadius = 0f
    private var maxRadius = Float.MAX_VALUE

    fun place(
        viewWidth: Int,
        viewHeight: Int,
        density: Float,
    ) {
        radius = min(viewWidth, viewHeight) * 0.3f
        minRadius = MIN_RADIUS_DP * density
        maxRadius = max(viewWidth, viewHeight).toFloat()
        cx = viewWidth / 2f
        cy = viewHeight / 2f + radius / 2f
        angle = 0f
    }

    fun moveBy(
        dx: Float,
        dy: Float,
    ) {
        cx += dx
        cy += dy
    }

    fun setRotation(raw: Float) {
        angle = snapAngle(raw, 45.0, Ruler.ROTATION_SNAP_DEG)
    }

    fun setRadius(r: Float) {
        radius = r.coerceIn(minRadius, maxRadius)
    }

    val degrees: Int get() = lineDegrees(angle)

    fun toLocal(
        x: Float,
        y: Float,
    ): PointF {
        val dx = x - cx
        val dy = y - cy
        val c = cos(angle)
        val s = sin(angle)
        return PointF(dx * c + dy * s, -dx * s + dy * c)
    }

    /** Inside the half disc (the side with negative local v, i.e. "above" the base). */
    fun contains(
        x: Float,
        y: Float,
    ): Boolean {
        val p = toLocal(x, y)
        return p.y <= 0f && hypot(p.x, p.y) <= radius
    }

    fun snapAt(
        x: Float,
        y: Float,
        tolerance: Float,
    ): GuideSnap? {
        val p = toLocal(x, y)
        val d = hypot(p.x, p.y)
        if (d < tolerance * CENTER_TOLERANCE_FACTOR) return GuideSnap.Ray(cx, cy, angle)
        if (abs(d - radius) < tolerance && p.y <= tolerance) return GuideSnap.Arc(cx, cy, radius)
        if (abs(p.y) < tolerance && abs(p.x) <= radius) return GuideSnap.Line(cx, cy, cos(angle), sin(angle))
        return null
    }

    companion object {
        const val MIN_RADIUS_DP = 80f
        const val CENTER_TOLERANCE_FACTOR = 1.5f
    }
}
