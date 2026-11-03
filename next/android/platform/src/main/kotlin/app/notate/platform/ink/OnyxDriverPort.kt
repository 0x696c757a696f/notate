package app.notate.platform.ink

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import app.notate.core.ink.driver.DriverInk
import app.notate.core.ink.driver.DriverStroke
import app.notate.platform.device.DeviceInfo
import app.notate.platform.device.OnyxSystemHelper
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.pen.EpdPenManager
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * One Onyx raw-pen session over one [view]. The only file that switches raw drawing on or off (CI guard).
 * Public methods: main thread only. The driver's callbacks arrive on the pen service thread; they are copied
 * into one ordered queue that a single posted runnable drains on the main thread into [sink].
 */
class OnyxDriverPort(
    private val view: View,
    private val sink: RawPenSink,
    private val device: DeviceInfo,
) : ConfigurableDriverPort {
    private val main = Handler(Looper.getMainLooper())
    private var helper: TouchHelper? = null
    private var ink: DriverInk? = null
    private var active = false
    private var limit: Rect? = null
    private var exclude: List<Rect> = emptyList()

    /** True while an eraser stroke is in progress: the driver must not draw ink for it. */
    private var erasing = false

    // --- pen thread -> main thread hand-off -------------------------------------------------------------
    private sealed interface Op {
        class Begin(val p: RawPoint, val eraser: Boolean) : Op
        class Move(val p: RawPoint) : Op
        class End(val p: RawPoint, val eraser: Boolean) : Op
    }

    private val lock = Any()
    private var queue = ArrayList<Op>()
    private var drainPosted = false
    private val drainRunnable = Runnable { drain() }

    @Volatile
    private var lastTimestamp = -1L

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(sideButton: Boolean, p: TouchPoint) = begin(p, sideButton)
        override fun onEndRawDrawing(sideButton: Boolean, p: TouchPoint) = end(p, sideButton)
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) = move(p)
        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) = moveAll(list)
        override fun onBeginRawErasing(sideButton: Boolean, p: TouchPoint) = begin(p, true)
        override fun onEndRawErasing(sideButton: Boolean, p: TouchPoint) = end(p, true)
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) = move(p)
        override fun onRawErasingTouchPointListReceived(list: TouchPointList) = moveAll(list)
    }

    private fun begin(p: TouchPoint, eraser: Boolean) {
        lastTimestamp = p.timestamp
        enqueue(Op.Begin(point(p), eraser))
    }

    private fun move(p: TouchPoint) {
        if (p.timestamp <= lastTimestamp) return
        lastTimestamp = p.timestamp
        enqueue(Op.Move(point(p)))
    }

    private fun moveAll(list: TouchPointList) {
        for (p in list.points) move(p)
    }

    private fun end(p: TouchPoint, eraser: Boolean) {
        move(p)
        enqueue(Op.End(point(p), eraser))
    }

    /** Some firmwares report raw digitizer levels, others 0..1. */
    private fun point(p: TouchPoint): RawPoint {
        val pressure = if (p.pressure <= 1f) p.pressure.coerceAtLeast(0f) else (p.pressure / device.maxPressure).coerceIn(0f, 1f)
        return RawPoint(p.x, p.y, pressure, p.tiltX.toFloat(), p.tiltY.toFloat(), p.timestamp)
    }

    private fun enqueue(op: Op) {
        val post: Boolean
        synchronized(lock) {
            queue.add(op)
            post = !drainPosted
            drainPosted = true
        }
        if (post) main.post(drainRunnable)
    }

    private fun drain() {
        val ops: ArrayList<Op>
        synchronized(lock) {
            ops = queue
            queue = ArrayList()
            drainPosted = false
        }
        var batch = ArrayList<RawPoint>()
        fun flushMoves() {
            if (batch.isEmpty()) return
            val out = batch
            batch = ArrayList()
            sink.onRawMove(out)
        }
        for (op in ops) {
            when (op) {
                is Op.Move -> batch.add(op.p)
                is Op.Begin -> {
                    flushMoves()
                    erasing = op.eraser
                    if (op.eraser) helper?.setRawDrawingRenderEnabled(false)
                    sink.onRawBegin(op.p, op.eraser)
                }
                is Op.End -> {
                    flushMoves()
                    sink.onRawEnd(op.p, op.eraser)
                    if (erasing) {
                        erasing = false
                        applyInk()
                    }
                }
            }
        }
        flushMoves()
    }

    // --- DriverPort -------------------------------------------------------------------------------------
    override fun open() {
        if (helper != null) return
        // Like the legacy canvas: no session while there is no surface; the pane re-opens when it is created.
        if (view is android.view.SurfaceView && !view.holder.surface.isValid) return
        try {
            // The editor surface is a plain View, so use the plain form that the legacy web overlay (also a plain View)
            // has always used. The SurfaceView-only "SFTouchRender" mode (create(view, true, ...)) delivered no points here.
            val h = if (sfRender) TouchHelper.create(view, true, callback) else TouchHelper.create(view, callback)
            helper = h
            OnyxSystemHelper.ignoreSystemSideButton(view)
            h.setLimitRect(currentLimit(), exclude)
            h.openRawDrawing()
            // The legacy canvas (SFTouchRender mode) needs this for fast stroke capture.
            if (sfRender) h.setSingleRegionMode()
            EpdController.enterScribbleMode(view)
        } catch (t: Throwable) {
            Log.e(TAG, "open failed", t)
            helper = null
        }
    }

    override fun close() {
        val h = helper ?: return
        helper = null
        active = false
        erasing = false
        try {
            h.setRawDrawingRenderEnabled(false)
            h.setRawDrawingEnabled(false)
            h.closeRawDrawing()
            EpdController.setScreenHandWritingPenState(view, EpdPenManager.PEN_PAUSE)
            EpdController.leaveScribbleMode(view)
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
        synchronized(lock) { queue.clear() }
    }

    /** Which TouchHelper form the session uses; [reopen] alternates it so a deaf session tries the other one. */
    var sfRender = true
        private set

    override fun reopen() {
        if (helper == null) return
        val wasActive = active
        sfRender = !sfRender
        Log.i(TAG, "reopen: sfRender=$sfRender")
        close()
        open()
        if (wasActive) setActive(true)
    }

    override fun setActive(on: Boolean) {
        val h = helper ?: return
        active = on
        try {
            if (on) {
                h.setRawDrawingEnabled(true)
                h.setRawDrawingRenderEnabled(ink != null && !erasing)
                // Legacy WebDriverPort does this on every switch-on: another pane may have left the pen out of scribble
                // mode, and the call made in open() can come before the view is attached.
                runCatching { EpdController.enterScribbleMode(view) }
                EpdController.setScreenHandWritingPenState(view, EpdPenManager.PEN_DRAWING)
                applyInk()
            } else {
                h.setRawDrawingRenderEnabled(false)
                h.setRawDrawingEnabled(false)
                EpdController.setScreenHandWritingPenState(view, EpdPenManager.PEN_PAUSE)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setActive($on) failed", t)
        }
    }

    // --- configuration ----------------------------------------------------------------------------------
    override fun setInk(ink: DriverInk?) {
        this.ink = ink
        applyInk()
    }

    override fun setLimits(limit: Rect, exclude: List<Rect>) {
        this.limit = Rect(limit)
        this.exclude = exclude.map { Rect(it) }
        val h = helper ?: return
        try {
            // The driver only picks up a new limit/exclude set when raw drawing restarts. Restart it the way the legacy
            // arbiter does (switch off, set the rect, switch on). closeRawDrawing/openRawDrawing is avoided: it can leave
            // the session deaf, which is what the first device test saw (driver=0 strokes).
            val wasActive = active
            if (wasActive) h.setRawDrawingEnabled(false)
            h.setLimitRect(currentLimit(), this.exclude)
            if (wasActive) {
                h.setRawDrawingEnabled(true)
                applyInk()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setLimits failed", t)
        }
    }

    private fun currentLimit(): Rect = limit ?: Rect().also { view.getLocalVisibleRect(it) }

    private fun applyInk() {
        val h = helper ?: return
        if (!active) return
        val i = ink
        try {
            if (i == null || erasing) {
                h.setRawDrawingRenderEnabled(false)
            } else {
                h.setStrokeStyle(styleOf(i.stroke))
                h.setStrokeWidth(i.widthPx)
                h.setStrokeColor(i.color)
                h.setRawDrawingRenderEnabled(true)
            }
            // Pen look only: make sure the dashed eraser/lasso channel is off.
            runCatching { Device.currentDevice().setEraserRawDrawingEnabled(false, TouchHelper.STROKE_STYLE_DASH) }
        } catch (t: Throwable) {
            Log.w(TAG, "applyInk failed", t)
        }
    }

    private fun styleOf(s: DriverStroke): Int = when (s) {
        DriverStroke.PENCIL -> TouchHelper.STROKE_STYLE_PENCIL
        DriverStroke.FOUNTAIN -> TouchHelper.STROKE_STYLE_FOUNTAIN
        DriverStroke.MARKER -> TouchHelper.STROKE_STYLE_MARKER
        DriverStroke.DASH -> TouchHelper.STROKE_STYLE_DASH
    }

    private companion object {
        const val TAG = "OnyxDriverPort"
    }
}
