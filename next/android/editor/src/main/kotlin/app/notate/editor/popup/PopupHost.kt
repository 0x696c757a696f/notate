package app.notate.editor.popup

import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import app.notate.core.input.BlockReason
import app.notate.core.input.EpdMode
import app.notate.core.input.EpdScheduler
import app.notate.core.input.EpdTiming
import app.notate.core.input.InkArbiter
import app.notate.editor.ui.dp
import app.notate.editor.ui.popupBackground

enum class Placement { BELOW_ANCHOR, CENTER, POINT }

/** Handle to a shown popup. */
interface PopupHandle {
    val isShowing: Boolean
    fun dismiss()
}

/**
 * Shows at most one popup at a time as a plain view on top of [root] (not a PopupWindow: a new window would take the focus
 * and switch the pen driver off). While a popup is up the pen is blocked from the page ([BlockReason.POPUP]) and the popup's
 * rectangle is reported through [onRectChanged] so that the driver excludes it. No animation; only the popup's area is
 * refreshed. Main thread only.
 */
class PopupHost(
    private val root: FrameLayout,
    private val arbiter: InkArbiter,
    private val epd: EpdScheduler<View>,
) {
    /** Called with the popup rectangle (root coordinates) when it appears, null when it is gone. */
    var onRectChanged: ((Rect?) -> Unit)? = null

    private var frame: FrameLayout? = null
    private var rect: Rect? = null
    private var handle: Handle? = null

    val isShowing: Boolean get() = frame != null

    /** The popup rectangle in root coordinates, or null. */
    fun rect(): Rect? = rect?.let { Rect(it) }

    /**
     * Shows [content] near [anchor] (a previous popup is closed first). [Placement.POINT] puts its top-left corner at
     * [windowPoint] (window coordinates), moved inside the window if needed.
     */
    fun show(
        anchor: View?, content: View, placement: Placement = Placement.BELOW_ANCHOR, windowPoint: android.graphics.Point? = null,
    ): PopupHandle {
        dismissInternal(refresh = false)
        val box = FrameLayout(root.context).apply {
            background = popupBackground(root.context)
            val pad = dp(2)
            setPadding(pad, pad, pad, pad)
            addView(content)
            isClickable = true     // swallow taps that land on the popup background
        }
        val maxW = root.width - 2 * root.dp(MARGIN_DP)
        val maxH = root.height - 2 * root.dp(MARGIN_DP)
        box.measure(
            View.MeasureSpec.makeMeasureSpec(if (maxW > 0) maxW else 4096, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(if (maxH > 0) maxH else 4096, View.MeasureSpec.AT_MOST),
        )
        val w = box.measuredWidth
        val h = box.measuredHeight
        val m = root.dp(MARGIN_DP)
        var left: Int
        var top: Int
        if (anchor != null && placement == Placement.BELOW_ANCHOR) {
            val a = anchorRect(anchor)
            left = a.left
            top = a.bottom
            if (top + h > root.height - m && a.top - h >= m) top = a.top - h
        } else if (placement == Placement.POINT && windowPoint != null) {
            val o = IntArray(2)
            root.getLocationInWindow(o)
            left = windowPoint.x - o[0]
            top = windowPoint.y - o[1]
        } else {
            left = (root.width - w) / 2
            top = (root.height - h) / 2
        }
        left = left.coerceIn(m, maxOf(m, root.width - w - m))
        top = top.coerceIn(m, maxOf(m, root.height - h - m))
        val lp = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START).apply { leftMargin = left; topMargin = top }
        root.addView(box, lp)
        frame = box
        val r = Rect(left, top, left + w, top + h)
        rect = r
        val hd = Handle(box)
        handle = hd
        onRectChanged?.invoke(r)
        arbiter.block(BlockReason.POPUP, true)
        refreshArea(r)
        return hd
    }

    /** Closes the popup, if any. */
    fun dismiss() = dismissInternal(refresh = true)

    /** Re-draws the popup area after its content changed. */
    fun refresh() {
        rect?.let { refreshArea(it) }
    }

    /**
     * Called first for every touch that reaches the window. A pen going down outside the popup closes it and returns false
     * (the stroke is then written as usual); a finger going down outside closes it and returns true (the touch is
     * swallowed). Touches inside the popup, and everything but a first touch-down, return false without effect.
     */
    fun interceptOutside(ev: MotionEvent): Boolean {
        if (frame == null || ev.actionMasked != MotionEvent.ACTION_DOWN) return false
        if (contains(ev)) return false
        val pen = ev.getToolType(0).let { it == MotionEvent.TOOL_TYPE_STYLUS || it == MotionEvent.TOOL_TYPE_ERASER }
        dismiss()
        return !pen
    }

    /** True when the touch point of [ev] (window coordinates) is inside the popup. */
    fun contains(ev: MotionEvent): Boolean {
        val r = rect ?: return false
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        val x = (ev.x - loc[0]).toInt()
        val y = (ev.y - loc[1]).toInt()
        return r.contains(x, y)
    }

    private fun dismissInternal(refresh: Boolean) {
        val box = frame ?: return
        val r = rect
        frame = null
        rect = null
        handle?.closed = true
        handle = null
        root.removeView(box)
        onRectChanged?.invoke(null)
        arbiter.block(BlockReason.POPUP, false)
        if (refresh && r != null) refreshArea(r)
    }

    private fun refreshArea(r: Rect) {
        epd.refresh(root, EpdMode.GU, intArrayOf(r.left, r.top, r.right, r.bottom), EpdTiming.AFTER_DRAW)
    }

    private fun anchorRect(anchor: View): Rect {
        val a = IntArray(2)
        val b = IntArray(2)
        anchor.getLocationInWindow(a)
        root.getLocationInWindow(b)
        val l = a[0] - b[0]
        val t = a[1] - b[1]
        return Rect(l, t, l + anchor.width, t + anchor.height)
    }

    // --- Selection bar: a second, persistent slot. It never blocks the pen, is not closed by outside touches and does not
    // replace the popup above; its rectangle is excluded from pen input. ---

    private var bar: FrameLayout? = null
    private var barRect: Rect? = null

    /** Called when the bar appears, moves or goes away. */
    var onBarChanged: (() -> Unit)? = null

    /** The bar rectangle in root coordinates, or null. */
    fun barRect(): Rect? = barRect?.let { Rect(it) }

    /**
     * Shows or moves the bar holding [content] above [local] (pixels of [anchorView]; below it when there is no room above).
     * Passing the same [content] view again only moves it.
     */
    fun showBar(content: View, anchorView: View, local: Rect) {
        val a = anchorRect(anchorView)
        val target = Rect(a.left + local.left, a.top + local.top, a.left + local.right, a.top + local.bottom)
        val m = root.dp(MARGIN_DP)
        var box = bar
        if (box == null || box.getChildAt(0) !== content) {
            box?.let { root.removeView(it) }
            box = FrameLayout(root.context).apply {
                background = popupBackground(root.context)
                val pad = dp(2)
                setPadding(pad, pad, pad, pad)
                addView(content)
                isClickable = true
            }
            root.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
            bar = box
        }
        box.measure(
            View.MeasureSpec.makeMeasureSpec(maxOf(root.width - 2 * m, 1), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxOf(root.height - 2 * m, 1), View.MeasureSpec.AT_MOST),
        )
        val w = box.measuredWidth
        val h = box.measuredHeight
        var top = target.top - h - m
        if (top < m) top = target.bottom + m
        val left = (target.centerX() - w / 2).coerceIn(m, maxOf(m, root.width - w - m))
        top = top.coerceIn(m, maxOf(m, root.height - h - m))
        val lp = box.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = left; lp.topMargin = top
        box.layoutParams = lp
        val r = Rect(left, top, left + w, top + h)
        val changed = r != barRect
        barRect = r
        if (changed) {
            onBarChanged?.invoke()
            refreshArea(r)
        }
    }

    fun hideBar() {
        val box = bar ?: return
        val r = barRect
        bar = null
        barRect = null
        root.removeView(box)
        onBarChanged?.invoke()
        if (r != null) refreshArea(r)
    }

    /** True when the touch point of [ev] (window coordinates) is inside the bar. */
    fun barContains(ev: MotionEvent): Boolean {
        val r = barRect ?: return false
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        return r.contains((ev.x - loc[0]).toInt(), (ev.y - loc[1]).toInt())
    }

    private inner class Handle(val view: View) : PopupHandle {
        var closed = false
        override val isShowing: Boolean get() = !closed
        override fun dismiss() {
            if (!closed && frame === view) dismissInternal(refresh = true)
        }
    }

    private companion object {
        const val MARGIN_DP = 8
    }
}
