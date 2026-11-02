package app.notate.core.ink.guide

import app.notate.core.geometry.Vec2
import app.notate.core.model.Units
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class GuideKind { RULER, PROTRACTOR }

/**
 * The position of a ruler or protractor on a page and the finger arithmetic to move it (04 §3.7). Page coordinates (pt). The angle
 * shown ([angleDeg]) sticks to multiples of 15° while the raw angle from the fingers is kept, so it can leave the notch again.
 * Main thread only.
 */
class GuideBody(val kind: GuideKind, var cx: Float, var cy: Float) {
    private var rawAngle = 0f
    private var protractorRadius = Units.mm(60f)
    private var grabOffset = 0f

    val angleDeg: Float get() = Guides.snapAngle(rawAngle)
    val ruler: RulerState get() = RulerState(cx, cy, angleDeg)
    val protractor: ProtractorState get() = ProtractorState(cx, cy, angleDeg, protractorRadius)

    fun moveBy(dx: Float, dy: Float) { cx += dx; cy += dy }

    /** Rotates by [deltaDeg] around the page point ([fx], [fy]) (a two-finger twist); the guide's centre orbits that point. */
    fun rotateAbout(fx: Float, fy: Float, deltaDeg: Float) {
        rawAngle += deltaDeg
        val r = Math.toRadians(deltaDeg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val x = cx - fx
        val y = cy - fy
        cx = fx + x * c - y * s
        cy = fy + x * s + y * c
    }

    /** Protractor only: scales the radius by [factor] (30–120 mm). */
    fun resizeBy(factor: Float) {
        if (kind == GuideKind.PROTRACTOR) protractorRadius = (protractorRadius * factor).coerceIn(Units.mm(30f), Units.mm(120f))
    }

    /** Local (u along the base, v across it; the disc is at v < 0) coordinates of a page point. */
    private fun local(x: Float, y: Float): Vec2 {
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        return Vec2((x - cx) * dx + (y - cy) * dy, -(x - cx) * dy + (y - cy) * dx)
    }

    /** Whether the page point lies on the guide, widened by [slackPt] so that a fingertip does not have to be exact. */
    fun contains(x: Float, y: Float, slackPt: Float): Boolean {
        val p = local(x, y)
        return when (kind) {
            GuideKind.RULER -> abs(p.x) <= ruler.lengthPt / 2 + slackPt && abs(p.y) <= ruler.widthPt / 2 + slackPt
            GuideKind.PROTRACTOR -> p.y <= slackPt && hypot(p.x, p.y) <= protractorRadius + slackPt
        }
    }

    /** Page position of the protractor's rotation handle (middle of the arc). */
    fun handle(): Vec2 {
        val a = Math.toRadians(angleDeg.toDouble())
        return Vec2(cx + protractorRadius * sin(a).toFloat(), cy - protractorRadius * cos(a).toFloat())
    }

    fun nearHandle(x: Float, y: Float, tolPt: Float): Boolean =
        kind == GuideKind.PROTRACTOR && hypot(x - handle().x, y - handle().y) <= tolPt

    /** Starts rotating by the handle with the finger at the page point ([x], [y]). */
    fun beginHandleDrag(x: Float, y: Float) { grabOffset = rawAngle - handleAngle(x, y) }

    fun dragHandle(x: Float, y: Float) { rawAngle = handleAngle(x, y) + grabOffset }

    private fun handleAngle(x: Float, y: Float): Float = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat() + 90f
}
