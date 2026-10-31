package com.alexdremov.notate.webdoc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathEffect
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.ShapeType
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.ui.guide.GuideLayer
import com.alexdremov.notate.ui.guide.GuidePath
import com.alexdremov.notate.ui.guide.GuideSnap
import com.alexdremov.notate.util.ShapeGeometry
import com.alexdremov.notate.util.StrokeRenderer
import com.onyx.android.sdk.data.note.TouchPoint
import java.util.IdentityHashMap
import kotlin.math.hypot

/**
 * Transparent layer above the WebView that turns stylus input into ink.
 *
 * Input routing (so writing and page interaction do not fight):
 *  - finger: reaches the page (tap, drag, pinch-zoom, scroll), except on a ruler/protractor, which it moves;
 *  - stylus in write mode: draws / erases / draws shapes with the toolbar's active [tool]; the page never sees it;
 *  - stylus in interact mode: passed through, so the pen can press buttons, drag sliders, fill forms.
 *
 * Ink is stored in document CSS px; [viewport] converts using the WebView's current scroll and zoom.
 * Tools are the same [PenTool]s as the note toolbar: widths are in screen px at 100% and become CSS px
 * through the display density, like a note page at its default zoom.
 */
@SuppressLint("ViewConstructor")
class WebInkOverlayView(
    context: Context,
    private val viewport: Viewport,
) : View(context) {
    interface Viewport {
        val scrollXPx: Int
        val scrollYPx: Int

        /** Device px per CSS px. */
        val scale: Float
    }

    var page: WebInkPage? = null
        set(value) {
            field = value
            pathCache.clear()
            invalidate()
        }

    var writeEnabled = true

    /** The toolbar's active tool: a pen, highlighter, shape/line pen or eraser. */
    var tool: PenTool = PenTool.defaultPens()[0]

    /** Eraser used by the stylus eraser end or side button. */
    var sideEraser: PenTool? = null

    /** Ruler and protractor, in view pixels (they float over the page like physical guides). */
    val guides = GuideLayer(resources.displayMetrics)

    /** A stroke starts; [native]: the pen driver is drawing it. */
    var onStrokeStart: ((native: Boolean) -> Unit)? = null
    var onInkChanged: (() -> Unit)? = null

    /** A held stroke was snapped to a shape mid-stroke; the host should show it now. */
    var onShapeSnapped: (() -> Unit)? = null

    /** A finger moved a guide ([ended] once it is released). */
    var onGuidesMoved: ((ended: Boolean) -> Unit)? = null

    /** Stylus hover, used to postpone screen clean-ups while the pen is about to write. */
    var onPenHover: ((MotionEvent) -> Unit)? = null

    /** True while a stroke is being built. */
    var isDrawing = false
        private set

    /** True while the pen touches the overlay; the host drops finger events meanwhile (palm rejection). */
    val isPenActive get() = penDown || isDrawing
    private var penDown = false

    /**
     * When the Onyx native channel ([WebInkRawInput]) supplies the points, MotionEvents from the pen
     * are only swallowed here (so the page never sees them) and used for palm rejection.
     */
    var nativeInput = false

    /**
     * True while the Onyx driver is really inking (raw drawing enabled). When it is not (finger/palm
     * on the screen, a tool needing a software preview), pen MotionEvents must draw and record the
     * stroke themselves, otherwise the stroke would be lost.
     */
    var driverLive = false

    /**
     * True while a popup, dialog, divider drag or export owns the screen: pen events are swallowed
     * so the pen taps the popup instead of inking (or clicking the page) underneath it.
     */
    var inkSuspended = false

    /** True when pen strokes come from the native driver, so MotionEvents from the pen are only swallowed. */
    val penUsesDriver: Boolean get() = writeEnabled && nativeInput && driverLive && !needsSoftwareInput

    /**
     * Tools whose stroke is not the pen path need a live software preview, which the panel cannot show
     * while the driver draws: drag shapes, and any stroke that may follow a ruler/protractor.
     */
    val needsSoftwareInput: Boolean get() = writeEnabled && (tool.isDragShapeTool || guides.hasVisibleGuide)

    private val usesMotionInput get() = !nativeInput || !driverLive || needsSoftwareInput

    /** Decided at pen-down and kept for the whole stroke, so a driver switch mid-stroke cannot split it. */
    private var strokeFromMotion = false

    /** True if the last committed stroke differs from the pen path the driver drew (a shape or guided line). */
    var lastCommitReshaped = false
        private set

    private val density = resources.displayMetrics.density
    private val pathCache = IdentityHashMap<WebInkStroke, Pair<Path, RectF>>()

    /** Pressure-recorded pen strokes, drawn by the note canvas renderer so each pen looks the same as on notes. */
    private val pressureStrokeCache = IdentityHashMap<WebInkStroke, Stroke>()
    private val pressurePaint = Paint()

    // Current stroke
    private val livePoints = FloatArrayBuilder()
    private val livePressures = FloatArrayBuilder()
    private val livePath = Path()
    private var liveTool: PenTool = tool
    private var erasing = false
    private var eraserRadiusCss = 10f
    private var shapeStart: PointF? = null
    private var shapeEnd = PointF()
    private var guide: GuideSnap? = null
    private var guideStartX = 0f
    private var guideStartY = 0f
    private var guideArcStart = 0.0
    private var guideArcLast = 0.0
    private var guideArcSweep = 0.0
    private var guideScreenPoints: List<PointF> = emptyList()
    private var snappedShape: List<ShapeGeometry.Segment>? = null

    // Hold-to-snap: holding the pen still turns the stroke drawn so far into a perfect shape.
    private var dwellEnabled = false
    private var dwellDelayMs = 600L
    private var dwellX = 0f
    private var dwellY = 0f
    private var dwellPointCount = 0
    private val dwellRunnable = Runnable { onDwell() }

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val visible = RectF()

    init {
        setWillNotDraw(false)
        isClickable = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        val isPen = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        if (!isPen && !penDown) {
            if (!guides.onTouchEvent(event)) return false
            val ended = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
            invalidate()
            onGuidesMoved?.invoke(ended)
            return true
        }
        if (isPen && inkSuspended && !penDown) return true
        if (!penDown && (!writeEnabled || !isPen || page == null)) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                penDown = true
                strokeFromMotion = usesMotionInput
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> penDown = false
        }
        if (!strokeFromMotion) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val eraser =
                    toolType == MotionEvent.TOOL_TYPE_ERASER ||
                        (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                beginStroke(event.x, event.y, eraser, native = false, pressure = event.pressure)
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) {
                    extendStroke(event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalPressure(h))
                }
                extendStroke(event.x, event.y, event.pressure)
            }
            MotionEvent.ACTION_UP -> endStroke(commit = true)
            MotionEvent.ACTION_CANCEL -> endStroke(commit = false)
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        onPenHover?.invoke(event)
        return super.onHoverEvent(event)
    }

    /**
     * Starts a stroke at view coordinates. [forceEraser]: stylus eraser end or side button.
     * [native]: points come from the Onyx driver, which already shows the live pen path.
     */
    fun beginStroke(
        viewX: Float,
        viewY: Float,
        forceEraser: Boolean,
        native: Boolean,
        pressure: Float = DEFAULT_PRESSURE,
    ) {
        if (page == null) return
        isDrawing = true
        liveNative = native
        liveTool = tool
        erasing = forceEraser || tool.type == ToolType.ERASER
        val eraserTool = if (tool.type == ToolType.ERASER) tool else sideEraser
        eraserRadiusCss = ((eraserTool?.width ?: 30f) / 2f / density).coerceAtLeast(3f)
        livePoints.clear()
        livePressures.clear()
        livePath.reset()
        shapeStart = null
        guide = null
        snappedShape = null
        lastCommitReshaped = false
        removeCallbacks(dwellRunnable)
        onStrokeStart?.invoke(native)

        val x = toDocX(viewX)
        val y = toDocY(viewY)
        when {
            erasing -> {
                eraseAt(x, y)
            }
            liveTool.isDragShapeTool -> {
                shapeStart = PointF(x, y)
                shapeEnd.set(x, y)
            }
            else -> {
                if (!liveTool.isShapeTool) guide = guides.snapAt(viewX, viewY)
                guide?.let { startGuide(it, viewX, viewY) }
                livePoints.add(x, y)
                livePressures.add(pressure)
                livePath.moveTo(x, y)
                dwellEnabled = guide == null && PreferencesManager.isShapePerfectionEnabled(context)
                if (dwellEnabled) {
                    dwellDelayMs = PreferencesManager.getShapePerfectionDelay(context)
                    dwellX = viewX
                    dwellY = viewY
                    dwellPointCount = 1
                    postDelayed(dwellRunnable, dwellDelayMs)
                }
            }
        }
        if (!native) invalidate()
    }

    private var liveNative = false

    fun extendStroke(
        viewX: Float,
        viewY: Float,
        pressure: Float = DEFAULT_PRESSURE,
    ) {
        if (!isDrawing) return
        val x = toDocX(viewX)
        val y = toDocY(viewY)
        when {
            erasing -> {
                eraseAt(x, y)
            }
            shapeStart != null -> {
                shapeEnd.set(x, y)
                invalidate()
            }
            guide != null -> {
                updateGuide(viewX, viewY)
                invalidate()
            }
            snappedShape != null -> {
                return // Snapped mid-stroke: the rest of the pen path is ignored.
            }
            else -> {
                if (!livePoints.add(x, y)) return
                livePressures.add(pressure)
                val n = livePoints.size
                if (n >= 4) {
                    // Incremental quadratic smoothing through segment midpoints (no per-frame rebuild).
                    val px = livePoints[n - 4]
                    val py = livePoints[n - 3]
                    livePath.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
                }
                if (dwellEnabled && hypot(viewX - dwellX, viewY - dwellY) > DWELL_MOVE_PX) {
                    dwellX = viewX
                    dwellY = viewY
                    dwellPointCount = n / 2
                    removeCallbacks(dwellRunnable)
                    postDelayed(dwellRunnable, dwellDelayMs)
                }
                // With the native channel the panel already shows the live stroke.
                if (!liveNative) invalidate()
            }
        }
    }

    fun endStroke(commit: Boolean) {
        if (!isDrawing) return
        removeCallbacks(dwellRunnable)
        if (commit && !erasing) commitLiveStroke()
        livePoints.clear()
        livePressures.clear()
        livePath.reset()
        shapeStart = null
        guide = null
        snappedShape = null
        isDrawing = false
        erasing = false
        invalidate()
        onInkChanged?.invoke()
    }

    /** Device px per CSS px at the current zoom. */
    fun viewportScale(): Float = viewport.scale

    /** CSS px width of [t]'s stroke. */
    fun widthCss(t: PenTool): Float = (t.width / density).coerceAtLeast(0.5f)

    private fun onDwell() {
        if (!isDrawing || erasing || guide != null || snappedShape != null || shapeStart != null) return
        val pts = livePoints.prefix(dwellPointCount * 2)
        val shape = WebInkShapes.recognize(pts, viewport.scale) ?: return
        snappedShape = shape
        invalidate()
        onShapeSnapped?.invoke()
    }

    private fun eraseAt(
        x: Float,
        y: Float,
    ) {
        val p = page ?: return
        if (p.eraseAt(x, y, eraserRadiusCss)) invalidate()
    }

    private fun commitLiveStroke() {
        val p = page ?: return
        val t = liveTool
        val scale = viewport.scale
        val start = shapeStart
        val segments: List<ShapeGeometry.Segment>? =
            when {
                start != null -> {
                    WebInkShapes.dragShape(t.shapeType, t.polygonSides, start, shapeEnd, scale).ifEmpty { return }
                }
                guide != null -> {
                    if (guideScreenPoints.size < 2) return
                    listOf(ShapeGeometry.Segment(guideScreenPoints.map { PointF(toDocX(it.x), toDocY(it.y)) }))
                }
                snappedShape != null -> {
                    snappedShape
                }
                t.shapeType == ShapeType.AUTO -> {
                    WebInkShapes.recognize(livePoints.toArray(), scale)
                }
                else -> {
                    null
                }
            }
        if (segments != null) {
            lastCommitReshaped = true
            p.addAll(segments.map { strokeOf(t, WebInkShapes.toFloatArray(it.points), it.widthScale) })
        } else {
            val pts = livePoints.toArray()
            if (pts.size < 2) return
            val pressures = livePressures.toArray().takeIf { it.size * 2 == pts.size }
            p.add(strokeOf(t, pts, 1f, pressures))
        }
    }

    private fun strokeOf(
        t: PenTool,
        points: FloatArray,
        widthScale: Float,
        pressures: FloatArray? = null,
    ): WebInkStroke {
        val highlighter = t.strokeType == StrokeType.HIGHLIGHTER
        return WebInkStroke(
            tool = if (highlighter) WebInkTool.HIGHLIGHTER else WebInkTool.PEN,
            color = t.displayColor,
            width = widthCss(t) * widthScale,
            points = points,
            style = t.strokeType,
            pressures = if (highlighter) null else pressures,
        )
    }

    private fun startGuide(
        g: GuideSnap,
        x: Float,
        y: Float,
    ) {
        guideStartX = x
        guideStartY = y
        guideScreenPoints = emptyList()
        if (g is GuideSnap.Arc) {
            guideArcStart = GuidePath.angleOf(g, x, y)
            guideArcLast = guideArcStart
            guideArcSweep = 0.0
        }
    }

    private fun updateGuide(
        x: Float,
        y: Float,
    ) {
        val g = guide ?: return
        guideScreenPoints =
            when (g) {
                is GuideSnap.Line -> GuidePath.line(g, guideStartX, guideStartY, x, y)
                is GuideSnap.Ray -> GuidePath.ray(g, x, y)
                is GuideSnap.Arc -> {
                    val a = GuidePath.angleOf(g, x, y)
                    guideArcSweep += GuidePath.angleDelta(guideArcLast, a)
                    guideArcLast = a
                    GuidePath.arc(g, guideArcStart, guideArcSweep)
                }
            }
    }

    fun notifyPageChanged() {
        val alive = page?.strokes?.toSet() ?: emptySet()
        pathCache.keys.retainAll(alive)
        pressureStrokeCache.keys.retainAll(alive)
        invalidate()
    }

    private fun toDocX(viewX: Float) = (viewX + viewport.scrollXPx) / viewport.scale

    private fun toDocY(viewY: Float) = (viewY + viewport.scrollYPx) / viewport.scale

    override fun onDraw(canvas: Canvas) {
        val p = page
        if (p != null) {
            val scale = viewport.scale
            canvas.save()
            canvas.translate(-viewport.scrollXPx.toFloat(), -viewport.scrollYPx.toFloat())
            canvas.scale(scale, scale)
            visible.set(
                viewport.scrollXPx / scale,
                viewport.scrollYPx / scale,
                (viewport.scrollXPx + width) / scale,
                (viewport.scrollYPx + height) / scale,
            )
            // Highlighter first so pen ink stays crisp on top of it.
            for (pass in 0..1) {
                for (s in p.strokes) {
                    if ((s.tool == WebInkTool.HIGHLIGHTER) != (pass == 0)) continue
                    val (path, bounds) = pathCache.getOrPut(s) { buildPath(s.points) }
                    if (!RectF.intersects(visible, inflate(bounds, s.width))) continue
                    if (usesPressure(s)) {
                        val stroke = pressureStrokeCache.getOrPut(s) { toPressureStroke(s, path, bounds) }
                        StrokeRenderer.drawStroke(canvas, pressurePaint, stroke)
                    } else {
                        drawStroke(canvas, path, s.tool, s.color, s.width, s.style)
                    }
                }
            }
            if (isDrawing && !erasing) drawLive(canvas)
            canvas.restore()
        }
        guides.draw(canvas)
    }

    private fun drawLive(canvas: Canvas) {
        val t = liveTool
        val kind = if (t.strokeType == StrokeType.HIGHLIGHTER) WebInkTool.HIGHLIGHTER else WebInkTool.PEN
        val width = widthCss(t)
        val segments =
            when {
                shapeStart != null -> WebInkShapes.dragShape(t.shapeType, t.polygonSides, shapeStart!!, shapeEnd, viewport.scale)
                guide != null -> listOf(ShapeGeometry.Segment(guideScreenPoints.map { PointF(toDocX(it.x), toDocY(it.y)) }))
                else -> snappedShape
            }
        if (segments != null) {
            for (seg in segments) {
                if (seg.points.size < 2) continue
                val path = Path()
                path.moveTo(seg.points[0].x, seg.points[0].y)
                for (i in 1 until seg.points.size) path.lineTo(seg.points[i].x, seg.points[i].y)
                drawStroke(canvas, path, kind, t.displayColor, width * seg.widthScale, t.strokeType)
            }
        } else if (livePoints.size >= 2) {
            if (livePoints.size == 2) {
                val path = Path()
                path.moveTo(livePoints[0], livePoints[1])
                path.lineTo(livePoints[0] + 0.01f, livePoints[1])
                drawStroke(canvas, path, kind, t.displayColor, width, t.strokeType)
            } else {
                drawStroke(canvas, livePath, kind, t.displayColor, width, t.strokeType)
            }
        }
    }

    private val tmpRect = RectF()

    private fun inflate(
        r: RectF,
        by: Float,
    ): RectF {
        tmpRect.set(r.left - by, r.top - by, r.right + by, r.bottom + by)
        return tmpRect
    }

    private fun drawStroke(
        canvas: Canvas,
        path: Path,
        tool: WebInkTool,
        color: Int,
        width: Float,
        style: StrokeType,
    ) {
        paint.color = color
        paint.strokeWidth = width
        paint.strokeCap = if (tool == WebInkTool.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
        paint.pathEffect = dashEffect(style, width)
        canvas.drawPath(path, paint)
        paint.pathEffect = null
    }

    private fun usesPressure(s: WebInkStroke): Boolean =
        s.tool == WebInkTool.PEN &&
            s.pressures != null &&
            s.pressures.size * 2 == s.points.size &&
            s.style != StrokeType.DASH &&
            s.style != StrokeType.DOT

    private fun toPressureStroke(
        s: WebInkStroke,
        path: Path,
        bounds: RectF,
    ): Stroke {
        val pressures = s.pressures!!
        val points = ArrayList<TouchPoint>(pressures.size)
        for (i in pressures.indices) {
            // No timestamps are stored: a fixed step keeps the fountain pen's speed thinning neutral.
            points.add(TouchPoint(s.points[i * 2], s.points[i * 2 + 1], pressures[i], 1f, 0, 0, i * 8L))
        }
        val strokeBounds = RectF(bounds).apply { inset(-s.width * 1.5f, -s.width * 1.5f) }
        return Stroke(path = path, points = points, color = s.color, width = s.width, style = s.style, bounds = strokeBounds)
    }

    private fun dashEffect(
        style: StrokeType,
        width: Float,
    ): PathEffect? =
        // Same proportions as note pages (StrokeRenderer).
        when (style) {
            StrokeType.DASH -> DashPathEffect(floatArrayOf(width * 2, width * 4), 0f)
            StrokeType.DOT -> DashPathEffect(floatArrayOf(0.01f, width * 2.5f), 0f)
            else -> null
        }

    private fun buildPath(pts: FloatArray): Pair<Path, RectF> {
        val path = Path()
        val bounds = RectF(pts[0], pts[1], pts[0], pts[1])
        path.moveTo(pts[0], pts[1])
        if (pts.size == 2) {
            path.lineTo(pts[0] + 0.01f, pts[1])
        } else {
            // Quadratic smoothing through segment midpoints.
            var i = 2
            while (i + 1 < pts.size) {
                val x = pts[i]
                val y = pts[i + 1]
                if (i + 3 < pts.size) {
                    path.quadTo(x, y, (x + pts[i + 2]) / 2f, (y + pts[i + 3]) / 2f)
                } else {
                    path.lineTo(x, y)
                }
                bounds.union(x, y)
                i += 2
            }
        }
        return path to bounds
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(dwellRunnable)
        guides.release()
        super.onDetachedFromWindow()
    }

    /** Growable float buffer that avoids boxing during a stroke. */
    private class FloatArrayBuilder {
        private var data = FloatArray(256)
        var size = 0
            private set

        operator fun get(i: Int) = data[i]

        /** Returns false if the point repeats the previous one. */
        fun add(
            x: Float,
            y: Float,
        ): Boolean {
            if (size >= 2 && data[size - 2] == x && data[size - 1] == y) return false
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x
            data[size++] = y
            return true
        }

        /** Appends a single value (e.g. one pressure sample per point). */
        fun add(v: Float) {
            if (size + 1 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun clear() {
            size = 0
        }

        fun toArray(): FloatArray = data.copyOf(size)

        fun prefix(n: Int): FloatArray = data.copyOf(n.coerceIn(0, size))
    }

    companion object {
        /** Pressure for points whose source reports none. */
        const val DEFAULT_PRESSURE = 0.5f

        /** Pen movement (px) that restarts the hold-to-snap timer; matches the note canvas. */
        private const val DWELL_MOVE_PX = 20f
    }
}
