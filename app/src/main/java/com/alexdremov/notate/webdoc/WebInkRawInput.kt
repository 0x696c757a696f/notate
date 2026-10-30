package com.alexdremov.notate.webdoc

import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
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
 * which keeps the persistent, document-anchored copy; shortly after the pen lifts, raw drawing is
 * toggled off for one partial refresh so the software ink replaces the driver's temporary ink.
 *
 * Raw drawing is paused whenever the page itself must update on screen: finger scrolling,
 * dialogs/menus (they would otherwise be drawn on), export, or "pen: interact" mode.
 */
class WebInkRawInput(
    private val overlay: WebInkOverlayView,
) {
    companion object {
        private const val TAG = "WebInkRawInput"
        private const val FLUSH_DELAY_MS = 350L

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
    private var strokeActive = false
    private var lastTimestamp = -1L
    private val dirty = RectF()
    private var hasDirty = false
    private val flushRunnable = Runnable { flush() }

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

    /** Re-applies the pen look (style/width/colour) for the current tool and zoom. */
    fun updateTool() {
        val h = helper ?: return
        val scale = overlay.viewportScale()
        when (overlay.tool) {
            WebInkOverlayView.Tool.PEN -> {
                h.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
                h.setStrokeColor(ColorUtils.adjustColorForHardware(overlay.penColor))
                h.setStrokeWidth(overlay.penWidthCss * scale)
                h.setRawDrawingRenderEnabled(true)
            }
            WebInkOverlayView.Tool.HIGHLIGHTER -> {
                // The panel cannot blend alpha; preview with the marker style, then the
                // translucent software stroke replaces it on flush.
                h.setStrokeStyle(TouchHelper.STROKE_STYLE_MARKER)
                h.setStrokeColor(Color.LTGRAY)
                h.setStrokeWidth(overlay.highlighterWidthCss * scale)
                h.setRawDrawingRenderEnabled(true)
            }
            WebInkOverlayView.Tool.ERASER -> h.setRawDrawingRenderEnabled(false)
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

    fun close() {
        overlay.removeCallbacks(flushRunnable)
        runCatching {
            helper?.setRawDrawingEnabled(false)
            helper?.closeRawDrawing()
        }
        helper = null
    }

    private fun applyState() {
        val h = helper ?: return
        val active = enabled && pauseCount == 0
        try {
            h.setRawDrawingEnabled(active)
            if (active) updateTool()
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to switch raw drawing", t)
        }
    }

    private fun visibleRect(): Rect = Rect().also { overlay.getLocalVisibleRect(it) }

    // Callbacks arrive on the pen service thread; all ink state lives on the main thread.

    private fun begin(
        p: TouchPoint,
        eraser: Boolean,
    ) {
        val x = p.x
        val y = p.y
        lastTimestamp = p.timestamp
        overlay.post {
            overlay.removeCallbacks(flushRunnable)
            strokeActive = true
            dirty.set(x, y, x, y)
            hasDirty = true
            overlay.beginStroke(x, y, eraser)
        }
    }

    private fun move(p: TouchPoint) {
        if (p.timestamp <= lastTimestamp) return
        lastTimestamp = p.timestamp
        val x = p.x
        val y = p.y
        overlay.post {
            if (!strokeActive) return@post
            dirty.union(x, y)
            overlay.extendStroke(x, y)
        }
    }

    private fun moveAll(list: TouchPointList) {
        for (p in list.points) move(p)
    }

    private fun end(p: TouchPoint) {
        move(p)
        overlay.post {
            if (!strokeActive) return@post
            strokeActive = false
            overlay.endStroke(commit = true)
            overlay.postDelayed(flushRunnable, FLUSH_DELAY_MS)
        }
    }

    /** Replaces the driver's temporary ink with the overlay's rendering of the same stroke. */
    private fun flush() {
        val h = helper ?: return
        if (!hasDirty) return
        hasDirty = false
        val pad = overlay.highlighterWidthCss * overlay.viewportScale() + 16f
        val r = Rect((dirty.left - pad).toInt(), (dirty.top - pad).toInt(), (dirty.right + pad).toInt(), (dirty.bottom + pad).toInt())
        try {
            val wasActive = enabled && pauseCount == 0
            if (wasActive) h.setRawDrawingEnabled(false)
            overlay.invalidate()
            EpdController.invalidate(overlay, r.left, r.top, r.right, r.bottom, UpdateMode.GC)
            if (wasActive) {
                h.setRawDrawingEnabled(true)
                updateTool()
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "Flush failed", t)
        }
    }
}
