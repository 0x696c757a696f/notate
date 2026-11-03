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

    private val callbacks = java.util.concurrent.atomic.AtomicInteger()
    private val pointCount = java.util.concurrent.atomic.AtomicInteger()

    @Volatile
    private var lastCallback = "-"
    private var openTries = 0
    private var openNote = "never opened"

    override fun debugStatus(): String =
        "helper=${if (helper != null) "ok" else "null"} sf=$sfRender active=$active opens=$openTries " +
            "cb=${callbacks.get()} pts=${pointCount.get()} last=$lastCallback [$openNote] now:${regionNote()}"

    /** Every SDK callback goes through here first, so `cb=0` means none of the eight methods was ever called. */
    private fun seen(name: String) {
        callbacks.incrementAndGet()
        lastCallback = "$name@${Thread.currentThread().name}"
    }

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(sideButton: Boolean, p: TouchPoint) { seen("beginDraw"); begin(p, sideButton) }
        override fun onEndRawDrawing(sideButton: Boolean, p: TouchPoint) { seen("endDraw"); end(p, sideButton) }
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) { seen("moveDraw"); move(p) }
        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) { seen("listDraw"); moveAll(list) }
        override fun onBeginRawErasing(sideButton: Boolean, p: TouchPoint) { seen("beginErase"); begin(p, true) }
        override fun onEndRawErasing(sideButton: Boolean, p: TouchPoint) { seen("endErase"); end(p, true) }
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) { seen("moveErase"); move(p) }
        override fun onRawErasingTouchPointListReceived(list: TouchPointList) { seen("listErase"); moveAll(list) }
    }

    private fun begin(p: TouchPoint, eraser: Boolean) {
        pointCount.incrementAndGet()
        lastTimestamp = p.timestamp
        enqueue(Op.Begin(point(p), eraser))
    }

    private fun move(p: TouchPoint) {
        pointCount.incrementAndGet()
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
        // Like the legacy canvas: no session while there is no surface; the pane re-opens when it is created.
        if (view is android.view.SurfaceView && !view.holder.surface.isValid) { openNote = "no surface yet"; return }
        openTries++
        val existing = helper
        if (existing != null) {
            // Legacy openDriverSession: an existing helper gets its rect and raw drawing re-applied (the surface may be new).
            try {
                OnyxSystemHelper.ignoreSystemSideButton(view)
                existing.setLimitRect(currentLimit(), currentExclude())
                existing.openRawDrawing()
                if (sfRender) existing.setSingleRegionMode()
                if (sfRender) EpdController.enterScribbleMode(view)
                openNote = "re-open ok ${view.width}x${view.height} ${regionNote()}"
            } catch (t: Throwable) {
                Log.w(TAG, "re-open failed", t)
                openNote = "re-open failed: ${t.javaClass.simpleName} ${t.message}".take(80)
            }
            return
        }
        try {
            // Same form as the legacy canvas (SFTouchRender on a SurfaceView).
            val h = if (sfRender) TouchHelper.create(view, true, callback) else TouchHelper.create(view, callback)
            helper = h
            OnyxSystemHelper.ignoreSystemSideButton(view)
            h.setLimitRect(currentLimit(), currentExclude())
            h.openRawDrawing()
            // The legacy canvas (SFTouchRender mode) needs this for fast stroke capture.
            if (sfRender) h.setSingleRegionMode()
            if (sfRender) EpdController.enterScribbleMode(view)
            openNote = "open ok ${view.width}x${view.height} ${regionNote()}"
        } catch (t: Throwable) {
            Log.e(TAG, "open failed", t)
            openNote = "open failed: ${t.javaClass.simpleName} ${t.message}".take(80)
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

    /** The TouchHelper form: the legacy canvas's SFTouchRender mode, fixed so every test run is comparable. */
    // Legacy forms: the note canvas (a SurfaceView) uses SFTouchRender + single-region mode; the web overlay (a plain View)
    // uses TouchHelper.create(view, callback) with neither, and no scribble call at open.
    val sfRender = view is android.view.SurfaceView

    override fun reopen() {
        if (helper == null) return
        val wasActive = active
        close()
        open()
        if (wasActive) setActive(true)
    }

    override fun setActive(on: Boolean) {
        if (on && helper == null) open()
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
            h.setLimitRect(currentLimit(), currentExclude())
            if (wasActive) {
                h.setRawDrawingEnabled(true)
                applyInk()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setLimits failed", t)
        }
    }

    /** Never an empty rect: the SDK logs "Empty region detected when mapping" and drops every point for one. */
    private fun currentLimit(): Rect {
        val l = limit?.takeUnless { it.isEmpty } ?: Rect().also { view.getLocalVisibleRect(it) }
        return if (l.isEmpty) Rect(0, 0, view.width, view.height) else l
    }

    /** Exclusion rects that are non-empty, inside the limit, and do not swallow it whole. */
    private fun currentExclude(): List<Rect> {
        val l = currentLimit()
        return exclude.mapNotNull { r ->
            val c = Rect(r)
            if (c.intersect(l) && !c.isEmpty && !(c.contains(l))) c else null
        }
    }

    private fun regionNote(): String = "limit=${currentLimit().flattenToString()} excl=${currentExclude().size}/${exclude.size}"

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
