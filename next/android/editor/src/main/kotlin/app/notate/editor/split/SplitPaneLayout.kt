package app.notate.editor.split

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import app.notate.core.workspace.DividerDrop
import app.notate.core.workspace.Orientation
import app.notate.core.workspace.SplitGeometry
import app.notate.editor.ui.dp

/**
 * Holds one pane, or two panes with a 12 dp divider between them (04 §6). While the divider is dragged only a preview line
 * moves (the panes stay as they are, which saves e-ink refreshes); on release [Listener.onDividerDropped] says what to do and
 * the owner lays the panes out again. Main thread only.
 */
class SplitPaneLayout(context: Context, private val listener: Listener) : ViewGroup(context) {
    interface Listener {
        /** A drag of the divider began (the pen is kept off the screen meanwhile). */
        fun onDividerDragStarted()

        /** The divider was let go at the position that [drop] describes. */
        fun onDividerDropped(drop: DividerDrop)

        /** The drag ended without a drop (cancelled). */
        fun onDividerDragCancelled()
    }

    private val dividerThickness = dp(DIVIDER_DP)
    private val divider = DividerView(context)
    private val preview = View(context).apply { setBackgroundColor(Color.BLACK); visibility = GONE }
    private var first: View? = null
    private var second: View? = null

    var orientation: Orientation = Orientation.HORIZONTAL
        set(value) {
            if (field == value) return
            field = value
            divider.invalidate()
            requestLayout()
        }

    /** Share of the space that the first (left or top) pane takes. */
    var ratio: Float = 0.5f
        set(value) {
            val v = value.coerceIn(0.05f, 0.95f)
            if (field == v) return
            field = v
            requestLayout()
        }

    val isSplit: Boolean get() = second != null

    init {
        addView(divider)
        addView(preview)
        divider.visibility = GONE
    }

    /** Shows [a] alone ([b] null) or [a] and [b] side by side. */
    fun setPanes(a: View?, b: View?) {
        if (a === first && b === second) {      // only the ratio or the direction changed
            requestLayout()
            return
        }
        first?.let { removeView(it) }
        second?.let { removeView(it) }
        first = a
        second = b
        a?.let { addView(it, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        b?.let { addView(it, 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        divider.visibility = if (b != null) VISIBLE else GONE
        cancelDrag()
        requestLayout()
    }

    /** True when ([x], [y]) in window coordinates is on the divider. */
    fun hitsDivider(x: Float, y: Float): Boolean {
        if (divider.visibility != VISIBLE) return false
        val loc = IntArray(2)
        divider.getLocationInWindow(loc)
        return x >= loc[0] && x < loc[0] + divider.width && y >= loc[1] && y < loc[1] + divider.height
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), MeasureSpec.EXACTLY)

    /** Size in px of the first pane along the split axis for a total of [total] px. */
    internal fun firstSize(total: Int): Int = ((total - dividerThickness) * ratio).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val a = first
        val b = second
        if (a == null) return
        if (b == null) {
            a.measure(exactly(w), exactly(h))
            return
        }
        if (orientation == Orientation.HORIZONTAL) {
            val aw = firstSize(w)
            a.measure(exactly(aw), exactly(h))
            b.measure(exactly(w - dividerThickness - aw), exactly(h))
            divider.measure(exactly(dividerThickness), exactly(h))
        } else {
            val ah = firstSize(h)
            a.measure(exactly(w), exactly(ah))
            b.measure(exactly(w), exactly(h - dividerThickness - ah))
            divider.measure(exactly(w), exactly(dividerThickness))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val a = first
        val c = second
        if (a == null) return
        if (c == null) {
            a.layout(0, 0, w, h)
            return
        }
        if (orientation == Orientation.HORIZONTAL) {
            val aw = firstSize(w)
            a.layout(0, 0, aw, h)
            divider.layout(aw, 0, aw + dividerThickness, h)
            c.layout(aw + dividerThickness, 0, w, h)
        } else {
            val ah = firstSize(h)
            a.layout(0, 0, w, ah)
            divider.layout(0, ah, w, ah + dividerThickness)
            c.layout(0, ah + dividerThickness, w, h)
        }
        preview.layout(0, 0, 0, 0)
    }

    // --- Dragging -------------------------------------------------------------------------------------------

    private var dragging = false

    private fun cancelDrag() {
        if (!dragging) return
        dragging = false
        preview.visibility = GONE
        listener.onDividerDragCancelled()
    }

    private fun showPreviewAt(pos: Float) {
        val horizontal = orientation == Orientation.HORIZONTAL
        val thick = dp(PREVIEW_DP)
        val p = pos.toInt().coerceIn(0, (if (horizontal) width else height) - thick)
        preview.visibility = VISIBLE
        if (horizontal) preview.layout(p, 0, p + thick, height) else preview.layout(0, p, width, p + thick)
    }

    /** Share of the first pane when the divider's centre is at [pos] px along the axis. */
    internal fun shareAt(pos: Float): Float {
        val total = if (orientation == Orientation.HORIZONTAL) width else height
        val usable = (total - dividerThickness).coerceAtLeast(1)
        return ((pos - dividerThickness / 2f) / usable).coerceIn(0f, 1f)
    }

    private inner class DividerView(context: Context) : View(context) {
        private val gripPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        private val linePaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f }
        private var lastShown = Float.NaN

        override fun onDraw(canvas: Canvas) {
            val horizontal = orientation == Orientation.HORIZONTAL
            canvas.drawColor(Color.WHITE)
            val gw = dp(GRIP_SHORT_DP).toFloat()
            val gl = dp(GRIP_LONG_DP).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            if (horizontal) {
                canvas.drawLine(0f, 0f, 0f, height.toFloat(), linePaint)
                canvas.drawLine(width - 1f, 0f, width - 1f, height.toFloat(), linePaint)
                canvas.drawRect(RectF(cx - gw / 2, cy - gl / 2, cx + gw / 2, cy + gl / 2), gripPaint)
            } else {
                canvas.drawLine(0f, 0f, width.toFloat(), 0f, linePaint)
                canvas.drawLine(0f, height - 1f, width.toFloat(), height - 1f, linePaint)
                canvas.drawRect(RectF(cx - gl / 2, cy - gw / 2, cx + gl / 2, cy + gw / 2), gripPaint)
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val horizontal = orientation == Orientation.HORIZONTAL
            // Position along the axis in the parent's coordinates.
            val pos = (if (horizontal) ev.x + left else ev.y + top)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    dragging = true
                    lastShown = Float.NaN
                    listener.onDividerDragStarted()
                    showPreviewAt(pos - dividerThickness / 2f)
                }
                MotionEvent.ACTION_MOVE -> if (dragging && (lastShown.isNaN() || kotlin.math.abs(pos - lastShown) >= dp(MIN_STEP_DP))) {
                    lastShown = pos
                    showPreviewAt(pos - dividerThickness / 2f)
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    dragging = false
                    preview.visibility = GONE
                    listener.onDividerDropped(SplitGeometry.drop(shareAt(pos)))
                }
                MotionEvent.ACTION_CANCEL -> cancelDrag()
            }
            return true
        }
    }

    private companion object {
        const val DIVIDER_DP = 12
        const val PREVIEW_DP = 4
        const val GRIP_SHORT_DP = 4
        const val GRIP_LONG_DP = 32
        const val MIN_STEP_DP = 8
    }
}
