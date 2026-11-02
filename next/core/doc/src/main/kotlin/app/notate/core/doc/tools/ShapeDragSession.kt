package app.notate.core.doc.tools

import app.notate.core.geometry.Vec2
import app.notate.core.model.InkPoint
import app.notate.core.model.InkStyle
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.ShapeKind
import app.notate.core.model.ToolPreset
import app.notate.core.model.edit.Edit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shape tool: drag from P0 to P1 and a shape follows as a ghost; pen-up commits it (nothing if the drag was shorter than 2 pt).
 * With [ToolContext.constraint] held: lines snap to 15° steps, rectangles become squares, ellipses circles. With the preset's
 * snapToGrid both ends snap to the page grid. Main thread only.
 */
class ShapeDragSession(private val preset: ToolPreset, private val ctx: ToolContext) : ToolSession {
    private val editor = ctx.editor
    private val kind = preset.shapeKind ?: ShapeKind.LINE
    private val sides = (preset.polygonSides).coerceIn(3, 8)
    private val style = InkStyle(
        preset.pen.takeIf { it != null && it != PenType.HIGHLIGHTER } ?: PenType.BALLPOINT,
        preset.color ?: 0xFF000000.toInt(), preset.widthPt ?: 1.134f,
    )
    private val snapper = if (preset.snapToGrid) GridSnapper(ctx.background) else null
    private val tick = GridSnapper.tickSpacing(ctx.background)
    private var started = false
    private var p0 = Vec2(0f, 0f)
    private var p1 = Vec2(0f, 0f)
    private var raw = Vec2(0f, 0f)
    private var id: ItemId? = null
    private var order = 0L

    override fun begin(p: InkPoint) {
        started = true
        p0 = snap(p.x, p.y)
        p1 = p0
        id = editor.newItemId()
        order = editor.nextOrder(ctx.page)
    }

    override fun move(batch: List<InkPoint>) {
        if (!started || batch.isEmpty()) return
        val l = batch.last()
        follow(l.x, l.y)
    }

    /** Call when [ToolContext.constraint] changed while the pen is still: redraws the ghost for the last pen position. */
    fun reapplyConstraint() {
        if (started) follow(raw.x, raw.y)
    }

    private fun follow(x: Float, y: Float) {
        raw = Vec2(x, y)
        p1 = target(x, y)
        if (hypot(p1.x - p0.x, p1.y - p0.y) >= MIN_DRAG_PT) shape()?.let { ctx.preview.show(Preview.Ghost(listOf(it))) }
    }

    override fun end(p: InkPoint) {
        if (!started) return
        started = false
        raw = Vec2(p.x, p.y)
        p1 = target(p.x, p.y)
        val s = if (hypot(p1.x - p0.x, p1.y - p0.y) < MIN_DRAG_PT) null else shape()
        if (s != null && editor.content(ctx.page) != null) editor.apply(Edit.Items(ctx.page, emptyList(), listOf(s)))
        ctx.preview.clear()
    }

    override fun cancel() {
        started = false
        ctx.preview.clear()
    }

    private fun snap(x: Float, y: Float): Vec2 = snapper?.snap(x, y) ?: Vec2(x, y)

    /** The dragged end after grid snapping and the constraint. */
    private fun target(x: Float, y: Float): Vec2 {
        val raw = if (ctx.constraint && (kind == ShapeKind.LINE || kind == ShapeKind.ARROW)) Vec2(x, y) else snap(x, y)
        if (!ctx.constraint) return raw
        val dx = raw.x - p0.x
        val dy = raw.y - p0.y
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> {
                val len = hypot(dx, dy)
                val a = Math.toRadians((Math.toDegrees(atan2(dy, dx).toDouble()) / ANGLE_STEP).roundToInt() * ANGLE_STEP)
                Vec2(p0.x + len * cos(a).toFloat(), p0.y + len * sin(a).toFloat())
            }
            ShapeKind.RECT, ShapeKind.ELLIPSE -> {
                val m = max(abs(dx), abs(dy))
                Vec2(p0.x + if (dx < 0) -m else m, p0.y + if (dy < 0) -m else m)
            }
            else -> raw
        }
    }

    /** Geometry for p0 → p1 (page coordinates); see the params table in 03-data-model §2. */
    private fun shape(): Shape? {
        val id = id ?: return null
        val (x0, y0) = p0
        val (x1, y1) = p1
        val params: FloatArray = when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW -> floatArrayOf(x0, y0, x1, y1)
            ShapeKind.RECT -> floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
            ShapeKind.ELLIPSE -> floatArrayOf(
                (x0 + x1) / 2f, (y0 + y1) / 2f, max(abs(x1 - x0) / 2f, MIN_RADIUS), max(abs(y1 - y0) / 2f, MIN_RADIUS), 0f,
            )
            ShapeKind.POLYGON -> regularPolygon(x0, y0, hypot(x1 - x0, y1 - y0), sides)
            ShapeKind.AXES_2D, ShapeKind.AXES_Q1 -> {
                val len = hypot(x1 - x0, y1 - y0)
                floatArrayOf(x0, y0, len, len, 0f, tick)
            }
            ShapeKind.TRIANGLE -> floatArrayOf(x0, y0, x1, y1, x0 - (x1 - x0), y1)
            ShapeKind.POLYLINE -> floatArrayOf(x0, y0, x1, y1)
        }
        return Shape(id, order, style, kind, params, null, ItemBounds.shapeBounds(kind, params, style))
    }

    private companion object {
        const val MIN_DRAG_PT = 2f
        const val MIN_RADIUS = 0.5f
        const val ANGLE_STEP = 15.0

        /** Regular polygon with one vertex straight up (page y points down). */
        fun regularPolygon(cx: Float, cy: Float, r: Float, n: Int): FloatArray {
            val out = FloatArray(2 * n)
            for (k in 0 until n) {
                val a = -Math.PI / 2 + 2 * Math.PI * k / n
                out[2 * k] = cx + r * cos(a).toFloat()
                out[2 * k + 1] = cy + r * sin(a).toFloat()
            }
            return out
        }
    }
}
