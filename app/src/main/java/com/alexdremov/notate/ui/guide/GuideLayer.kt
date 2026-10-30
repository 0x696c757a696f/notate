package com.alexdremov.notate.ui.guide

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A ruler and a protractor floating over a view: visibility, finger gestures (one finger moves a
 * guide, two rotate it and resize the protractor), drawing and pen snapping, in view pixels.
 *
 * Self-contained so any ink surface can host the guides (used by the web-document reader).
 */
class GuideLayer(
    private val metrics: DisplayMetrics,
) {
    val ruler = Ruler()
    val protractor = Protractor()

    @Volatile
    var isRulerVisible = false
        private set

    @Volatile
    var isProtractorVisible = false
        private set

    val hasVisibleGuide: Boolean get() = isRulerVisible || isProtractorVisible

    /** True while a finger is moving a guide. */
    val isDragging: Boolean get() = gesture != null

    private var gesture: Gesture? = null

    private class Gesture(
        val isRuler: Boolean,
        var lastX: Float,
        var lastY: Float,
    ) {
        var twoFinger = false
        var startPointerAngle = 0f
        var startGuideAngle = 0f
        var startPointerDist = 1f
        var startRadius = 0f
        var lastMidX = 0f
        var lastMidY = 0f
    }

    fun setRulerVisible(
        visible: Boolean,
        viewWidth: Int,
        viewHeight: Int,
    ) {
        if (visible && !isRulerVisible) ruler.place(viewWidth, viewHeight, metrics.density)
        isRulerVisible = visible
        gesture = null
    }

    fun setProtractorVisible(
        visible: Boolean,
        viewWidth: Int,
        viewHeight: Int,
    ) {
        if (visible && !isProtractorVisible) protractor.place(viewWidth, viewHeight, metrics.density)
        isProtractorVisible = visible
        gesture = null
    }

    /** The guide a pen stroke starting at (x, y) should follow, if it starts close enough to one. */
    fun snapAt(
        x: Float,
        y: Float,
    ): GuideSnap? {
        val tolerance = SNAP_DP * metrics.density
        if (isProtractorVisible) protractor.snapAt(x, y, tolerance)?.let { return it }
        if (isRulerVisible) ruler.snapAt(x, y, tolerance)?.let { return it }
        return null
    }

    /**
     * Finger input. Returns true when the event belongs to a guide gesture; the host should then
     * redraw (and, on [MotionEvent.ACTION_UP], do a clean refresh).
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasVisibleGuide) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                gesture =
                    when {
                        isProtractorVisible && protractor.contains(x, y) -> Gesture(false, x, y)
                        isRulerVisible && ruler.contains(x, y) -> Gesture(true, x, y)
                        else -> null
                    }
                return gesture != null
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val g = gesture ?: return false
                if (event.pointerCount >= 2) startTwoFinger(g, event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val g = gesture ?: return false
                if (g.twoFinger && event.pointerCount >= 2) {
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val rotation = g.startGuideAngle + (pointerAngle(event) - g.startPointerAngle)
                    if (g.isRuler) {
                        ruler.setRotation(rotation)
                        ruler.moveBy(midX - g.lastMidX, midY - g.lastMidY)
                    } else {
                        protractor.setRotation(rotation)
                        protractor.setRadius(g.startRadius * pointerDist(event) / g.startPointerDist)
                        protractor.moveBy(midX - g.lastMidX, midY - g.lastMidY)
                    }
                    g.lastMidX = midX
                    g.lastMidY = midY
                } else if (!g.twoFinger) {
                    val dx = event.x - g.lastX
                    val dy = event.y - g.lastY
                    if (g.isRuler) ruler.moveBy(dx, dy) else protractor.moveBy(dx, dy)
                    g.lastX = event.x
                    g.lastY = event.y
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val g = gesture ?: return false
                // Keep moving with the finger that stays down, without a jump.
                g.twoFinger = false
                val remaining = if (event.actionIndex == 0) 1 else 0
                g.lastX = event.getX(remaining)
                g.lastY = event.getY(remaining)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gesture == null) return false
                gesture = null
                return true
            }
        }
        return gesture != null
    }

    private fun startTwoFinger(
        g: Gesture,
        event: MotionEvent,
    ) {
        g.twoFinger = true
        g.startPointerAngle = pointerAngle(event)
        g.startGuideAngle = if (g.isRuler) ruler.angle else protractor.angle
        g.startPointerDist = pointerDist(event).coerceAtLeast(1f)
        g.startRadius = protractor.radius
        g.lastMidX = (event.getX(0) + event.getX(1)) / 2f
        g.lastMidY = (event.getY(0) + event.getY(1)) / 2f
    }

    private fun pointerAngle(event: MotionEvent) = atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))

    private fun pointerDist(event: MotionEvent) = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))

    // --- Drawing ---
    // Tick marks and labels are drawn once into a bitmap, then only moved/rotated per frame.

    private val fillPaint =
        Paint().apply {
            color = Color.argb(70, 160, 160, 160)
            style = Paint.Style.FILL
        }
    private val linePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
        }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var rulerArt: Bitmap? = null
    private var protractorArt: Bitmap? = null
    private var protractorArtRadius = 0f

    fun draw(canvas: Canvas) {
        if (isRulerVisible) drawRuler(canvas)
        if (isProtractorVisible) drawProtractor(canvas)
    }

    fun release() {
        rulerArt?.recycle()
        rulerArt = null
        protractorArt?.recycle()
        protractorArt = null
    }

    private fun drawRuler(cv: Canvas) {
        val r = ruler
        val art = rulerArt()
        cv.save()
        cv.translate(r.cx, r.cy)
        cv.rotate(Math.toDegrees(r.angle.toDouble()).toFloat())
        cv.drawBitmap(art, -art.width / 2f, -art.height / 2f, bitmapPaint)
        textPaint.textSize = r.width * 0.3f
        cv.drawText("${r.degrees}°", 0f, textPaint.textSize * 0.35f + r.width * 0.12f, textPaint)
        cv.restore()
    }

    private fun rulerArt(): Bitmap {
        val w = ceil(ruler.length).toInt().coerceAtLeast(1)
        val h = ceil(ruler.width).toInt().coerceAtLeast(1)
        rulerArt?.let { if (it.width == w && it.height == h) return it }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val mm = metrics.xdpi / 25.4f
        val half = w / 2f
        val hw = h / 2f
        c.translate(half, hw)
        c.drawRect(-half, -hw, half, hw, fillPaint)
        c.drawLine(-half, -hw + 1f, half, -hw + 1f, linePaint)
        c.drawLine(-half, hw - 1f, half, hw - 1f, linePaint)
        textPaint.textSize = h * 0.2f
        val count = (half / mm).toInt()
        for (i in -count..count) {
            val x = i * mm
            val len =
                when {
                    i % 10 == 0 -> h * 0.28f
                    i % 5 == 0 -> h * 0.18f
                    else -> h * 0.1f
                }
            c.drawLine(x, -hw, x, -hw + len, linePaint)
            c.drawLine(x, hw, x, hw - len, linePaint)
            if (i % 10 == 0 && i != 0) c.drawText(abs(i / 10).toString(), x, -hw + len + textPaint.textSize, textPaint)
        }
        rulerArt?.recycle()
        rulerArt = bmp
        return bmp
    }

    private fun drawProtractor(cv: Canvas) {
        val p = protractor
        // While pinching, stretch the cached art; it is redrawn sharp at the new size afterwards.
        val art = protractorArt?.takeIf { gesture != null } ?: protractorArt(p.radius)
        val scale = p.radius / protractorArtRadius
        cv.save()
        cv.translate(p.cx, p.cy)
        cv.rotate(Math.toDegrees(p.angle.toDouble()).toFloat())
        cv.scale(scale, scale)
        cv.drawBitmap(art, -art.width / 2f, -(protractorArtRadius + 2f), bitmapPaint)
        cv.restore()
    }

    private fun protractorArt(radius: Float): Bitmap {
        protractorArt?.let { if (protractorArtRadius == radius) return it }
        val rad = radius
        val bmp = Bitmap.createBitmap(ceil(rad * 2 + 4).toInt(), ceil(rad + 4).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(bmp.width / 2f, rad + 2f) // centre of the base
        val oval = RectF(-rad, -rad, rad, rad)
        c.drawArc(oval, 180f, 180f, true, fillPaint)
        c.drawArc(oval, 180f, 180f, false, linePaint)
        c.drawLine(-rad, 0f, rad, 0f, linePaint)
        textPaint.textSize = (rad * 0.06f).coerceIn(14f, 36f)
        for (deg in 0..180) {
            val len =
                when {
                    deg % 10 == 0 -> rad * 0.1f
                    deg % 5 == 0 -> rad * 0.065f
                    else -> rad * 0.035f
                }
            val a = Math.toRadians(deg.toDouble())
            val cs = cos(a).toFloat()
            val sn = -sin(a).toFloat()
            c.drawLine(cs * rad, sn * rad, cs * (rad - len), sn * (rad - len), linePaint)
            if (deg % 10 == 0) {
                val lr = rad - len - textPaint.textSize
                c.drawText(deg.toString(), cs * lr, sn * lr + textPaint.textSize * 0.35f, textPaint)
            }
        }
        val mark = rad * 0.04f
        c.drawCircle(0f, 0f, mark, linePaint)
        c.drawLine(0f, -mark * 2, 0f, 0f, linePaint)
        protractorArt?.recycle()
        protractorArt = bmp
        protractorArtRadius = radius
        return bmp
    }

    companion object {
        /** A stroke starting this close (dp) to a guide's edge follows the guide. */
        const val SNAP_DP = 20f
    }
}
