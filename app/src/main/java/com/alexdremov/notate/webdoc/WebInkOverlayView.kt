package com.alexdremov.notate.webdoc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.util.IdentityHashMap

/**
 * Transparent layer above the WebView that turns stylus input into ink.
 *
 * Input routing (so writing and page interaction do not fight):
 *  - finger: never consumed here, always reaches the page (tap, drag, pinch-zoom, scroll);
 *  - stylus in write mode: draws / erases; the page never sees it;
 *  - stylus in interact mode: passed through, so the pen can press buttons, drag sliders, fill forms.
 *
 * Ink is stored in document CSS px; [viewport] converts using the WebView's current scroll and zoom.
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

    enum class Tool { PEN, HIGHLIGHTER, ERASER }

    var page: WebInkPage? = null
        set(value) {
            field = value
            pathCache.clear()
            invalidate()
        }

    var writeEnabled = true
    var tool = Tool.PEN
    var penColor = Color.BLACK
    var penWidthCss = 2f
    var highlighterColor = Color.argb(0x60, 0xFF, 0xD5, 0x00)
    var highlighterWidthCss = 14f
    var eraserRadiusCss = 10f

    var onStrokeStart: (() -> Unit)? = null
    var onInkChanged: (() -> Unit)? = null

    /** True while the pen is down on the overlay; the host drops finger events meanwhile (palm rejection). */
    var isDrawing = false
        private set

    private val pathCache = IdentityHashMap<WebInkStroke, Pair<Path, RectF>>()
    private val livePoints = FloatArrayBuilder()
    private var liveTool = Tool.PEN
    private var erasing = false
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
        if (!isDrawing && (!writeEnabled || !isPen || page == null)) return false

        val x = toDocX(event.x)
        val y = toDocY(event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                erasing =
                    tool == Tool.ERASER ||
                    toolType == MotionEvent.TOOL_TYPE_ERASER ||
                    (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                liveTool = tool
                livePoints.clear()
                onStrokeStart?.invoke()
                if (erasing) eraseAt(x, y) else livePoints.add(x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (erasing) {
                    for (h in 0 until event.historySize) eraseAt(toDocX(event.getHistoricalX(h)), toDocY(event.getHistoricalY(h)))
                    eraseAt(x, y)
                } else {
                    for (h in 0 until event.historySize) livePoints.add(toDocX(event.getHistoricalX(h)), toDocY(event.getHistoricalY(h)))
                    livePoints.add(x, y)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!erasing && event.actionMasked == MotionEvent.ACTION_UP) commitLiveStroke()
                livePoints.clear()
                isDrawing = false
                erasing = false
                invalidate()
                onInkChanged?.invoke()
            }
        }
        return true
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
        val pts = livePoints.toArray()
        if (pts.size < 2) return
        val stroke =
            if (liveTool == Tool.HIGHLIGHTER) {
                WebInkStroke(WebInkTool.HIGHLIGHTER, highlighterColor, highlighterWidthCss, pts)
            } else {
                WebInkStroke(WebInkTool.PEN, penColor, penWidthCss, pts)
            }
        p.add(stroke)
    }

    fun notifyPageChanged() {
        pathCache.keys.retainAll(page?.strokes?.toSet() ?: emptySet())
        invalidate()
    }

    private fun toDocX(viewX: Float) = (viewX + viewport.scrollXPx) / viewport.scale

    private fun toDocY(viewY: Float) = (viewY + viewport.scrollYPx) / viewport.scale

    override fun onDraw(canvas: Canvas) {
        val p = page ?: return
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
                drawStroke(canvas, path, s.tool, s.color, s.width)
            }
        }
        if (isDrawing && !erasing && livePoints.size >= 2) {
            val live = buildPath(livePoints.toArray()).first
            if (liveTool == Tool.HIGHLIGHTER) {
                drawStroke(canvas, live, WebInkTool.HIGHLIGHTER, highlighterColor, highlighterWidthCss)
            } else {
                drawStroke(canvas, live, WebInkTool.PEN, penColor, penWidthCss)
            }
        }
        canvas.restore()
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
    ) {
        paint.color = color
        paint.strokeWidth = width
        paint.strokeCap = if (tool == WebInkTool.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
        canvas.drawPath(path, paint)
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

    /** Growable float buffer that avoids boxing during a stroke. */
    private class FloatArrayBuilder {
        private var data = FloatArray(256)
        var size = 0
            private set

        fun add(
            x: Float,
            y: Float,
        ) {
            if (size >= 2 && data[size - 2] == x && data[size - 1] == y) return
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x
            data[size++] = y
        }

        fun clear() {
            size = 0
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
