package com.alexdremov.notate.ui.zoom

import android.graphics.Matrix
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * State and geometry of the zoom writing window (a magnified writing box docked at the bottom of the
 * canvas, as in GoodNotes/Notability).
 *
 * The window shows the world rectangle [target] magnified into the screen rectangle [writing]. Strokes
 * written inside [writing] are mapped back to [target], so small text and formulas can be written with
 * large, comfortable handwriting. [controls] is a button strip on top of the writing area.
 *
 * All screen rects are in view coordinates; [target] is in world coordinates.
 */
class ZoomWindow {
    enum class Control { ZOOM_OUT, ZOOM_IN, PREV, NEXT, NEW_LINE, CLOSE }

    var isOpen = false
        private set

    /** World area currently being written into. */
    val target = RectF()

    /** Whole docked box (controls + writing area), screen coordinates. */
    val box = RectF()

    /** Button strip at the top of [box]. */
    val controls = RectF()

    /** Magnified writing area below [controls]. */
    val writing = RectF()

    /** Extra magnification on top of the canvas zoom. */
    var magnification = DEFAULT_MAGNIFICATION
        private set

    /** Canvas (main view) scale the target size was computed for. */
    private var viewScale = 1f

    /** World x the window returns to on [Control.NEW_LINE]. */
    private var lineStartX = 0f

    private var density = 1f

    /** Effective world -> screen scale inside the writing area. */
    val scale: Float get() = viewScale * magnification

    /**
     * Opens the window docked to the bottom of a [viewWidth] x [viewHeight] view, with the target at the
     * top-left of what is currently visible. [viewMatrix] is the canvas world -> screen matrix.
     */
    fun open(
        viewMatrix: Matrix,
        viewWidth: Int,
        viewHeight: Int,
        density: Float,
    ) {
        this.density = density
        layout(viewWidth, viewHeight)
        viewScale = matrixScale(viewMatrix)

        val inverse = Matrix()
        viewMatrix.invert(inverse)
        val start = floatArrayOf(box.left + MARGIN_DP * density * 2, MARGIN_DP * density * 4)
        inverse.mapPoints(start)
        lineStartX = start[0]
        setTargetOrigin(start[0], start[1])
        isOpen = true
    }

    fun close() {
        isOpen = false
    }

    /** Re-docks the box after a view size change, keeping the target's top-left corner. */
    fun layout(
        viewWidth: Int,
        viewHeight: Int,
    ) {
        val margin = MARGIN_DP * density
        val height = (viewHeight * HEIGHT_FRACTION).coerceIn(MIN_HEIGHT_DP * density, MAX_HEIGHT_DP * density)
        box.set(margin, viewHeight - margin - height, viewWidth - margin, viewHeight - margin)
        controls.set(box.left, box.top, box.right, box.top + CONTROLS_DP * density)
        writing.set(box.left, controls.bottom, box.right, box.bottom)
        if (isOpen) setTargetOrigin(target.left, target.top)
    }

    /** Keeps the magnification relative to the canvas zoom when the canvas is zoomed. */
    fun onViewScaleChanged(newViewScale: Float) {
        if (newViewScale <= 0f || newViewScale == viewScale) return
        viewScale = newViewScale
        setTargetOrigin(target.left, target.top)
    }

    fun zoomBy(factor: Float) {
        magnification = (magnification * factor).coerceIn(MIN_MAGNIFICATION, MAX_MAGNIFICATION)
        setTargetOrigin(target.left, target.top)
    }

    /** Moves the target by a fraction of its width (negative = left). */
    fun step(fraction: Float) {
        setTargetOrigin(target.left + target.width() * fraction, target.top)
    }

    fun newLine() {
        setTargetOrigin(lineStartX, target.top + target.height())
    }

    /** Moves the target by a world-space delta (finger drag). */
    fun moveBy(
        dxWorld: Float,
        dyWorld: Float,
    ) {
        setTargetOrigin(target.left + dxWorld, target.top + dyWorld)
    }

    /** Called once a drag ends: the new position becomes the start of the line. */
    fun commitLineStart() {
        lineStartX = target.left
    }

    /**
     * Slides the target along when a stroke ends near its right edge, so the user can keep writing
     * without touching the controls. Returns true if the target moved.
     */
    fun advanceAfterStroke(strokeWorldBounds: RectF): Boolean {
        if (!isOpen || target.width() <= 0f) return false
        val threshold = target.left + target.width() * ADVANCE_TRIGGER
        if (strokeWorldBounds.right < threshold) return false
        setTargetOrigin(strokeWorldBounds.right - target.width() * ADVANCE_KEEP, target.top)
        return true
    }

    fun isInWriting(
        x: Float,
        y: Float,
    ) = isOpen && writing.contains(x, y)

    fun isInBox(
        x: Float,
        y: Float,
    ) = isOpen && box.contains(x, y)

    /** World -> screen matrix for the writing area. */
    fun worldToWriting(out: Matrix = Matrix()): Matrix {
        out.reset()
        out.postTranslate(-target.left, -target.top)
        out.postScale(scale, scale)
        out.postTranslate(writing.left, writing.top)
        return out
    }

    /** Screen rects of the control buttons, in [Control] order, laid out right-to-left. */
    fun controlRects(): List<Pair<Control, RectF>> {
        val size = controls.height()
        val order = listOf(Control.CLOSE, Control.NEW_LINE, Control.NEXT, Control.PREV)
        val result = ArrayList<Pair<Control, RectF>>()
        var right = controls.right
        for (c in order) {
            result.add(c to RectF(right - size * 1.4f, controls.top, right, controls.bottom))
            right -= size * 1.4f
        }
        var left = controls.left
        for (c in listOf(Control.ZOOM_OUT, Control.ZOOM_IN)) {
            result.add(c to RectF(left, controls.top, left + size * 1.4f, controls.bottom))
            left += size * 1.4f
        }
        return result
    }

    fun controlAt(
        x: Float,
        y: Float,
    ): Control? {
        if (!isOpen || !controls.contains(x, y)) return null
        return controlRects().firstOrNull { it.second.contains(x, y) }?.first
    }

    private fun setTargetOrigin(
        left: Float,
        top: Float,
    ) {
        val s = max(scale, 1e-3f)
        target.set(left, top, left + writing.width() / s, top + writing.height() / s)
    }

    companion object {
        const val DEFAULT_MAGNIFICATION = 2.5f
        const val MIN_MAGNIFICATION = 1.25f
        const val MAX_MAGNIFICATION = 6f
        const val ZOOM_STEP = 1.25f

        /** Arrow buttons move the target by this fraction of its width. */
        const val STEP_FRACTION = 0.8f

        /** A stroke ending past this fraction of the target width moves the target ... */
        const val ADVANCE_TRIGGER = 0.75f

        /** ... so that the stroke's right edge lands at this fraction. */
        const val ADVANCE_KEEP = 0.25f

        private const val HEIGHT_FRACTION = 0.3f
        private const val MIN_HEIGHT_DP = 180f
        private const val MAX_HEIGHT_DP = 420f
        private const val CONTROLS_DP = 44f
        private const val MARGIN_DP = 8f

        fun matrixScale(m: Matrix): Float {
            val v = FloatArray(9)
            m.getValues(v)
            return min(max(v[Matrix.MSCALE_X], 1e-3f), 1e3f)
        }
    }
}
