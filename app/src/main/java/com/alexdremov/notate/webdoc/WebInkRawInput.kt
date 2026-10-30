package com.alexdremov.notate.webdoc

import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.util.ColorUtils
import com.alexdremov.notate.util.Logger
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Onyx native pen channel for [WebInkOverlayView].
 *
 * The EPD driver draws the stroke directly into the panel while the pen moves (same low-latency
 * path as the main canvas, `TouchHelper` + raw drawing). The points are forwarded to the overlay,
 * which keeps the persistent, document-anchored copy. The driver's temporary ink is only swapped for
 * the overlay's rendering once the pen has left the screen for a moment, so writing a word is never
 * interrupted by a refresh between strokes; a stroke that became a shape is swapped right away.
 *
 * Raw drawing is paused whenever the page itself must update on screen: finger scrolling,
 * dialogs/menus (they would otherwise be drawn on), export, "pen: interact" mode, and tools that
 * need a live software preview (drag shapes, ruler/protractor).
 */
class WebInkRawInput(
    private val overlay: WebInkOverlayView,
) {
    companion object {
        private const val TAG = "WebInkRawInput"

        /** Clean-up after the pen leaves hover range. */
        private const val HOVER_EXIT_FLUSH_MS = 400L

        /** Clean-up when no hover events arrive (pen put down beside the screen, devices without hover). */
        private const val IDLE_FLUSH_MS = 1500L

        /** A stroke replaced by a shape is shown quickly. */
        private const val RESHAPED_FLUSH_MS = 120L

        /** Toggling raw drawing mid-stroke makes the driver restart the stroke; ignore that restart. */
        private const val RESTART_IGNORE_MS = 400L

        private const val OP_BEGIN = 0f
        private const val OP_BEGIN_ERASER = 1f
        private const val OP_MOVE = 2f
        private const val OP_END = 3f

        /** Creates the native channel, or null on devices without the Onyx pen service. */
        fun createOrNull(overlay: WebInkOverlayView): WebInkRawInput? =
            try {
                WebInkRawInput(overlay).also { it.open() }
            } catch (t: Throwable) {
                Logger.w(TAG, "Onyx raw drawing unavailable, using software ink", t)
                null
            }
    }

    private var helper: TouchHelper? = null
    private var pauseCount = 0
    private var enabled = true
    private var active = false
    private var strokeActive = false
    private var ignoringRestart = false
    private var ignoreBeginUntil = 0L
    private var hovering = false

    @Volatile
    private var lastTimestamp = -1L
    private val dirty = RectF()
    private var hasDirty = false
    private val flushRunnable = Runnable { flush() }

    // Pen-thread → main-thread hand-off: (op, x, y) triples drained by one posted runnable per batch,
    // instead of one Runnable per point, so long fast strokes don't flood the main looper.
    private val queueLock = Any()
    private var queue = FloatArray(3 * 256)
    private var queueSize = 0
    private var drainPosted = false
    private var drainBuffer = FloatArray(0)
    private val drainRunnable = Runnable { drain() }

    private val callback =
        object : RawInputCallback() {
            override fun onBeginRawDrawing(
                sideButton: Boolean,
                p: TouchPoint,
            ) = begin(p, eraser = sideButton)

            override fun onEndRawDrawing(
                sideButton: Boolean,
                p: TouchPoint,
            ) = end(p)

            override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) = move(p)

            override fun onRawDrawingTouchPointListReceived(list: TouchPointList) = moveAll(list)

            override fun onBeginRawErasing(
                sideButton: Boolean,
                p: TouchPoint,
            ) = begin(p, eraser = true)

            override fun onEndRawErasing(
                sideButton: Boolean,
                p: TouchPoint,
            ) = end(p)

            override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) = move(p)

            override fun onRawErasingTouchPointListReceived(list: TouchPointList) = moveAll(list)
        }

    private fun open() {
        val h = TouchHelper.create(overlay, callback)
        helper = h
        h.setLimitRect(visibleRect(), ArrayList<Rect>())
        h.openRawDrawing()
        applyState()
    }

    /** Re-applies the pen look (style/width/colour) for the current tool and zoom, and whether the driver draws at all. */
    fun updateTool() {
        if (active != wantsActive()) {
            applyState()
            return
        }
        val h = helper ?: return
        if (!active) return
        val t = overlay.tool
        val scale = overlay.viewportScale()
        when {
            t.type == ToolType.ERASER -> {
                h.setRawDrawingRenderEnabled(false)
            }
            t.strokeType == StrokeType.HIGHLIGHTER -> {
                // The panel cannot blend alpha; preview with the marker style, then the
                // translucent software stroke replaces it on flush.
                h.setStrokeStyle(TouchHelper.STROKE_STYLE_MARKER)
                h.setStrokeColor(Color.LTGRAY)
                h.setStrokeWidth(overlay.widthCss(t) * scale)
                h.setRawDrawingRenderEnabled(true)
            }
            else -> {
                h.setStrokeStyle(t.strokeType.touchHelperStyle)
                h.setStrokeColor(ColorUtils.adjustColorForHardware(t.displayColor))
                h.setStrokeWidth(overlay.widthCss(t) * scale)
                h.setRawDrawingRenderEnabled(true)
            }
        }
        runCatching { Device.currentDevice().setEraserRawDrawingEnabled(false, TouchHelper.STROKE_STYLE_DASH) }
    }

    /** Write mode on/off (off = the pen operates the page). */
    fun setEnabled(value: Boolean) {
        enabled = value
        applyState()
    }

    /** Nested pause, e.g. while a finger scrolls or a dialog is open. */
    fun pause() {
        pauseCount++
        if (pauseCount == 1) {
            overlay.removeCallbacks(flushRunnable)
            applyState()
        }
    }

    fun resume() {
        if (pauseCount == 0) return
        pauseCount--
        if (pauseCount == 0) applyState()
    }

    /** Call after layout changes so the driver only captures over the page area. */
    fun updateLimitRect() {
        helper?.setLimitRect(visibleRect(), ArrayList<Rect>())
    }

    /** Stylus hover over the page: keep the driver's ink while the pen is about to write again. */
    fun onHover(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (!hovering) {
                    hovering = true
                    if (!pendingReshape) overlay.removeCallbacks(flushRunnable)
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hovering = false
                if (hasDirty && !strokeActive) scheduleFlush(HOVER_EXIT_FLUSH_MS)
            }
        }
    }

    /** Shows the overlay's version of the current stroke now (e.g. it was snapped to a shape mid-stroke). */
    fun showOverlayNow() {
        if (strokeActive) ignoreBeginUntil = SystemClock.uptimeMillis() + RESTART_IGNORE_MS
        flush()
    }

    fun close() {
        overlay.removeCallbacks(flushRunnable)
        overlay.removeCallbacks(drainRunnable)
        runCatching {
            helper?.setRawDrawingEnabled(false)
            helper?.closeRawDrawing()
        }
        helper = null
    }

    private fun wantsActive() = enabled && pauseCount == 0 && !overlay.needsSoftwareInput

    private fun applyState() {
        val h = helper ?: return
        val want = wantsActive()
        try {
            h.setRawDrawingEnabled(want)
            active = want
            if (want) updateTool() else if (hasDirty) overlay.invalidate()
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to switch raw drawing", t)
        }
        if (!want && strokeActive) {
            // The driver may never report the end of a stroke it was cut off from.
            strokeActive = false
            overlay.endStroke(commit = true)
        }
    }

    private fun visibleRect(): Rect = Rect().also { overlay.getLocalVisibleRect(it) }

    // Callbacks arrive on the pen service thread; all ink state lives on the main thread.

    private fun begin(
        p: TouchPoint,
        eraser: Boolean,
    ) {
        lastTimestamp = p.timestamp
        enqueue(if (eraser) OP_BEGIN_ERASER else OP_BEGIN, p.x, p.y)
    }

    private fun move(p: TouchPoint) {
        if (p.timestamp <= lastTimestamp) return
        lastTimestamp = p.timestamp
        enqueue(OP_MOVE, p.x, p.y)
    }

    private fun moveAll(list: TouchPointList) {
        for (p in list.points) move(p)
    }

    private fun end(p: TouchPoint) {
        move(p)
        enqueue(OP_END, p.x, p.y)
    }

    private fun enqueue(
        op: Float,
        x: Float,
        y: Float,
    ) {
        synchronized(queueLock) {
            if (queueSize + 3 > queue.size) queue = queue.copyOf(queue.size * 2)
            queue[queueSize++] = op
            queue[queueSize++] = x
            queue[queueSize++] = y
            if (!drainPosted) {
                drainPosted = true
                overlay.post(drainRunnable)
            }
        }
    }

    private fun drain() {
        val n: Int
        synchronized(queueLock) {
            n = queueSize
            if (drainBuffer.size < n) drainBuffer = FloatArray(queue.size)
            System.arraycopy(queue, 0, drainBuffer, 0, n)
            queueSize = 0
            drainPosted = false
        }
        var i = 0
        while (i + 2 < n) {
            val op = drainBuffer[i]
            val x = drainBuffer[i + 1]
            val y = drainBuffer[i + 2]
            i += 3
            when (op) {
                OP_BEGIN, OP_BEGIN_ERASER -> {
                    if (SystemClock.uptimeMillis() < ignoreBeginUntil) {
                        ignoringRestart = true
                        continue
                    }
                    overlay.removeCallbacks(flushRunnable)
                    pendingReshape = false
                    strokeActive = true
                    if (!hasDirty) dirty.set(x, y, x, y) else dirty.union(x, y)
                    hasDirty = true
                    overlay.beginStroke(x, y, op == OP_BEGIN_ERASER, native = true)
                }
                OP_MOVE -> {
                    if (!strokeActive || ignoringRestart) continue
                    dirty.union(x, y)
                    overlay.extendStroke(x, y)
                }
                OP_END -> {
                    if (ignoringRestart) {
                        ignoringRestart = false
                        continue
                    }
                    if (!strokeActive) continue
                    strokeActive = false
                    overlay.endStroke(commit = true)
                    if (overlay.lastCommitReshaped) {
                        pendingReshape = true
                        scheduleFlush(RESHAPED_FLUSH_MS)
                    } else if (!hovering) {
                        scheduleFlush(IDLE_FLUSH_MS)
                    }
                }
            }
        }
    }

    private var pendingReshape = false

    private fun scheduleFlush(delayMs: Long) {
        overlay.removeCallbacks(flushRunnable)
        overlay.postDelayed(flushRunnable, delayMs)
    }

    /** Replaces the driver's temporary ink with the overlay's rendering of the same strokes. */
    private fun flush() {
        val h = helper ?: return
        pendingReshape = false
        if (!hasDirty) return
        hasDirty = false
        val pad = overlay.widthCss(overlay.tool) * overlay.viewportScale() + 48f
        val r = Rect((dirty.left - pad).toInt(), (dirty.top - pad).toInt(), (dirty.right + pad).toInt(), (dirty.bottom + pad).toInt())
        try {
            val wasActive = active
            if (wasActive) h.setRawDrawingEnabled(false)
            overlay.invalidate()
            // GU: high quality without the black flash of GC, so the page doesn't blink after writing.
            EpdController.invalidate(overlay, r.left, r.top, r.right, r.bottom, UpdateMode.GU)
            if (wasActive) {
                h.setRawDrawingEnabled(true)
                updateTool()
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "Flush failed", t)
        }
    }
}
