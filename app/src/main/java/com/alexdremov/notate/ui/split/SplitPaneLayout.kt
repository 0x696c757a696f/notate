package com.alexdremov.notate.ui.split

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.alexdremov.notate.ui.tabs.SplitOrientation
import com.alexdremov.notate.ui.tabs.Workspace
import kotlin.math.abs

/**
 * Hosts one pane, or two panes with a draggable divider (left | right or top / bottom).
 *
 * Built for e-ink and the Onyx pen driver:
 * - Dragging the divider only moves a preview line; panes are resized once, on release, so the
 *   canvas surfaces are not recreated on every move.
 * - Only one pane is "active" (it owns the pen's raw-drawing layer). A stylus touch on the other
 *   pane makes it active and is swallowed as a focus tap, so it never inks by accident; a quick
 *   finger tap does too, unless palm rejection is on.
 * - The divider sits outside both panes, so it is outside every pane's raw-drawing limit rect.
 */
class SplitPaneLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : ViewGroup(context, attrs) {
        private val density = resources.displayMetrics.density
        private val dividerSize = (14 * density).toInt()

        // Fingers get a wider grab area than the visible bar.
        private val dividerSlop = (12 * density).toInt()

        var orientation: SplitOrientation = SplitOrientation.SIDE_BY_SIDE
            set(value) {
                if (field != value) {
                    field = value
                    requestLayout()
                    invalidate()
                }
            }

        var ratio: Float = 0.5f
            set(value) {
                val v = value.coerceIn(Workspace.MIN_RATIO, Workspace.MAX_RATIO)
                if (field != v) {
                    field = v
                    requestLayout()
                    invalidate()
                }
            }

        /** 0 = first pane, 1 = second pane. */
        var activeIndex: Int = 0
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        var onPaneTouched: ((index: Int) -> Unit)? = null
        var onDividerDragStateChanged: ((dragging: Boolean) -> Unit)? = null
        var onDragReleased: ((SplitDrag.Outcome) -> Unit)? = null

        private var first: View? = null
        private var second: View? = null

        val isSplit: Boolean
            get() = first != null && second != null

        private var dragging = false
        private var dragRatio = 0f
        private var swallowing = false

        private val dividerPaint = Paint().apply { color = Color.rgb(0xDD, 0xDD, 0xDD) }
        private val linePaint = Paint().apply { color = Color.BLACK }
        private val gripRect = RectF()

        init {
            setWillNotDraw(false)
        }

        fun setPanes(
            firstPane: View,
            secondPane: View?,
        ) {
            if (first === firstPane && second === secondPane) return
            listOfNotNull(first, second).forEach { if (it !== firstPane && it !== secondPane) removeView(it) }
            first = firstPane
            second = secondPane
            listOfNotNull(firstPane, secondPane).forEach { pane ->
                if (pane.parent !== this) {
                    (pane.parent as? ViewGroup)?.removeView(pane)
                    addView(pane)
                }
            }
            requestLayout()
            invalidate()
        }

        fun paneAt(
            x: Float,
            y: Float,
        ): Int {
            if (!isSplit) return 0
            val pos = if (orientation == SplitOrientation.SIDE_BY_SIDE) x else y
            return if (pos < dividerStart()) 0 else if (pos >= dividerStart() + dividerSize) 1 else -1
        }

        private fun total() = if (orientation == SplitOrientation.SIDE_BY_SIDE) width else height

        private fun dividerStart(r: Float = ratio) = ((total() - dividerSize) * r).toInt()

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val a = first ?: return
            val b = second
            if (b == null) {
                a.measure(exactly(w), exactly(h))
                return
            }
            val side = orientation == SplitOrientation.SIDE_BY_SIDE
            val space = (if (side) w else h) - dividerSize
            val firstSize = (space * ratio).toInt()
            val secondSize = space - firstSize
            if (side) {
                a.measure(exactly(firstSize), exactly(h))
                b.measure(exactly(secondSize), exactly(h))
            } else {
                a.measure(exactly(w), exactly(firstSize))
                b.measure(exactly(w), exactly(secondSize))
            }
        }

        private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)

        override fun onLayout(
            changed: Boolean,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ) {
            val a = first ?: return
            val bPane = second
            a.layout(0, 0, a.measuredWidth, a.measuredHeight)
            if (bPane == null) return
            if (orientation == SplitOrientation.SIDE_BY_SIDE) {
                val start = a.measuredWidth + dividerSize
                bPane.layout(start, 0, start + bPane.measuredWidth, bPane.measuredHeight)
            } else {
                val start = a.measuredHeight + dividerSize
                bPane.layout(0, start, bPane.measuredWidth, start + bPane.measuredHeight)
            }
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (!isSplit) return
            val side = orientation == SplitOrientation.SIDE_BY_SIDE
            val start = dividerStart().toFloat()
            val end = start + dividerSize
            if (side) canvas.drawRect(start, 0f, end, height.toFloat(), dividerPaint) else canvas.drawRect(0f, start, width.toFloat(), end, dividerPaint)

            // A black edge on the active pane's side of the divider shows where the pen writes.
            val edge = 3 * density
            if (side) {
                if (activeIndex == 0) canvas.drawRect(start, 0f, start + edge, height.toFloat(), linePaint)
                else canvas.drawRect(end - edge, 0f, end, height.toFloat(), linePaint)
            } else {
                if (activeIndex == 0) canvas.drawRect(0f, start, width.toFloat(), start + edge, linePaint)
                else canvas.drawRect(0f, end - edge, width.toFloat(), end, linePaint)
            }

            // Grip in the middle of the bar.
            val gripLong = 48 * density
            val gripShort = 4 * density
            val mid = (start + end) / 2f
            if (side) {
                gripRect.set(mid - gripShort / 2, height / 2f - gripLong / 2, mid + gripShort / 2, height / 2f + gripLong / 2)
            } else {
                gripRect.set(width / 2f - gripLong / 2, mid - gripShort / 2, width / 2f + gripLong / 2, mid + gripShort / 2)
            }
            canvas.drawRoundRect(gripRect, gripShort, gripShort, linePaint)

            if (dragging) {
                val pos = dividerStart(dragRatio) + dividerSize / 2f
                val half = 2 * density
                if (side) canvas.drawRect(pos - half, 0f, pos + half, height.toFloat(), linePaint)
                else canvas.drawRect(0f, pos - half, width.toFloat(), pos + half, linePaint)
            }
        }

        private fun onDivider(
            x: Float,
            y: Float,
        ): Boolean {
            val pos = if (orientation == SplitOrientation.SIDE_BY_SIDE) x else y
            val start = dividerStart()
            return pos >= start - dividerSlop && pos <= start + dividerSize + dividerSlop
        }

        /**
         * When false (palm rejection on), only the stylus moves focus to the other pane; a resting
         * palm must never steal it. Even when true, a finger only moves focus with a quick tap.
         */
        var fingerTapActivates = true

        private var fingerDownIndex = -1
        private var fingerDownTime = 0L
        private var fingerDownX = 0f
        private var fingerDownY = 0f
        private val tapSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!isSplit) return false
            val stylus = ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fingerDownIndex = -1
                    val index = paneAt(ev.x, ev.y)
                    if (index == -1 || (!stylus && onDivider(ev.x, ev.y))) {
                        startDrag()
                        return true
                    }
                    if (index != activeIndex) {
                        if (stylus) {
                            // Focus tap: swallow it so it never writes or selects.
                            onPaneTouched?.invoke(index)
                            swallowing = true
                            return true
                        }
                        if (fingerTapActivates) {
                            fingerDownIndex = index
                            fingerDownTime = ev.eventTime
                            fingerDownX = ev.x
                            fingerDownY = ev.y
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> fingerDownIndex = -1

                MotionEvent.ACTION_MOVE -> {
                    if (fingerDownIndex >= 0 && (abs(ev.x - fingerDownX) > tapSlop || abs(ev.y - fingerDownY) > tapSlop)) {
                        fingerDownIndex = -1
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val index = fingerDownIndex
                    fingerDownIndex = -1
                    if (index >= 0 && ev.eventTime - fingerDownTime < android.view.ViewConfiguration.getLongPressTimeout()) {
                        onPaneTouched?.invoke(index)
                    }
                }
            }
            return dragging || swallowing
        }

        private fun startDrag() {
            dragging = true
            dragRatio = ratio
            parent?.requestDisallowInterceptTouchEvent(true)
            onDividerDragStateChanged?.invoke(true)
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (swallowing) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    swallowing = false
                }
                return true
            }
            if (!dragging) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val pos = (if (orientation == SplitOrientation.SIDE_BY_SIDE) event.x else event.y) - dividerSize / 2f
                    val space = (total() - dividerSize).coerceAtLeast(1)
                    dragRatio = (pos / space).coerceIn(0f, 1f)
                    invalidate()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    val outcome =
                        if (event.actionMasked == MotionEvent.ACTION_UP) SplitDrag.resolve(dragRatio) else SplitDrag.Outcome.Resize(ratio)
                    if (outcome is SplitDrag.Outcome.Resize) ratio = outcome.ratio
                    invalidate()
                    // Hand the pen back only after the panes have their new size.
                    post { onDividerDragStateChanged?.invoke(false) }
                    onDragReleased?.invoke(outcome)
                }
            }
            return true
        }
    }
