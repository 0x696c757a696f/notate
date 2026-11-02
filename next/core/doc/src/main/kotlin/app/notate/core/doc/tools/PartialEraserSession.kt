package app.notate.core.doc.tools

import app.notate.core.geometry.PolylineCutter
import app.notate.core.geometry.Geo
import app.notate.core.geometry.Rect
import app.notate.core.geometry.Vec2
import app.notate.core.model.InkPoint
import app.notate.core.model.Item
import app.notate.core.model.ItemBounds
import app.notate.core.model.Shape
import app.notate.core.model.Stroke
import app.notate.core.model.StrokePoints
import app.notate.core.model.ToolPreset
import app.notate.core.model.edit.Edit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Partial eraser: collects swept circles while the pen moves (only the cursor is previewed) and cuts every touched stroke
 * once at gesture end. A touched shape is removed whole. Main thread only.
 */
class PartialEraserSession(private val preset: ToolPreset, private val ctx: ToolContext) : ToolSession {
    private val radius = ctx.settings.eraserRadiusPx / ctx.pxPerPt
    private val centers = ArrayList<Float>()   // x,y pairs, interpolated so neighbours are at most radius/2 apart
    private var started = false

    override fun begin(p: InkPoint) {
        started = true
        centers.clear()
        centers.add(p.x); centers.add(p.y)
        cursor(p.x, p.y)
    }

    override fun move(batch: List<InkPoint>) {
        if (!started) return
        for (p in batch) addCenter(p.x, p.y)
        batch.lastOrNull()?.let { cursor(it.x, it.y) }
    }

    override fun end(p: InkPoint) {
        if (!started) return
        addCenter(p.x, p.y)
        started = false
        ctx.preview.clear()
        commit()
        centers.clear()
    }

    override fun cancel() {
        started = false
        centers.clear()
        ctx.preview.clear()
    }

    private fun addCenter(x: Float, y: Float) {
        val lx = centers[centers.size - 2]; val ly = centers[centers.size - 1]
        val d = hypot(x - lx, y - ly)
        val step = radius / 2f
        if (d > step) {
            val n = ceil(d / step).toInt()
            for (i in 1 until n) { val t = i / n.toFloat(); centers.add(lx + (x - lx) * t); centers.add(ly + (y - ly) * t) }
        }
        centers.add(x); centers.add(y)
    }

    private fun cursor(x: Float, y: Float) = ctx.preview.show(Preview.EraserCursor(Vec2(x, y), radius))

    private fun commit() {
        val content = ctx.editor.content(ctx.page) ?: return
        val circles = FloatArray(centers.size / 2 * 3)
        for (i in 0 until centers.size / 2) { circles[3 * i] = centers[2 * i]; circles[3 * i + 1] = centers[2 * i + 1]; circles[3 * i + 2] = radius }
        val area = Rect.ofPoints(centers.toFloatArray()).inset(-radius).let { if (it.isEmpty) Rect.around(centers[0], centers[1], radius) else it }
        val removed = ArrayList<Item>()
        val added = ArrayList<Item>()
        for (item in content.query(area)) {
            if (!EraserGeometry.erasable(item, preset)) continue
            when (item) {
                is Stroke -> cutStroke(item, circles, removed, added)
                is Shape -> if (touches(item)) removed.add(item)
                else -> Unit
            }
        }
        if (removed.isNotEmpty() || added.isNotEmpty()) ctx.editor.apply(Edit.Items(ctx.page, removed, added))
    }

    private fun touches(item: Item): Boolean {
        if (centers.size == 2) return EraserGeometry.hit(item, centers[0], centers[1], centers[0], centers[1], radius)
        for (i in 0 until centers.size / 2 - 1) {
            if (EraserGeometry.hit(item, centers[2 * i], centers[2 * i + 1], centers[2 * i + 2], centers[2 * i + 3], radius)) return true
        }
        return false
    }

    private fun cutStroke(s: Stroke, circles: FloatArray, removed: MutableList<Item>, added: MutableList<Item>) {
        val n = s.points.size
        val pieces = PolylineCutter.cut(s.points.xy, s.style.widthPt / 2f, circles)
        val total = (n - 1).toFloat()
        if (pieces.size == 1 && pieces[0].fromT <= UNCHANGED && pieces[0].toT >= total - UNCHANGED) return
        removed.add(s)
        for (piece in pieces) {
            val pts = slice(s.points, piece.fromT, piece.toT)
            if (pts.size < 1 || (pts.size > 1 && Geo.polylineLength(pts.xy) < MIN_PIECE_PT)) continue
            added.add(Stroke(ctx.editor.newItemId(), s.order, s.style, pts, ItemBounds.strokeBounds(pts, s.style)))
        }
    }

    /** Points of [p] between parameters [from] and [to] (segment index + fraction), ends interpolated. */
    private fun slice(p: StrokePoints, from: Float, to: Float): StrokePoints {
        if (p.size == 1) return p
        val xs = ArrayList<Float>(); val ps = ArrayList<Float>(); val ts = ArrayList<Int>()
        fun at(t: Float) {
            val i = floor(t).toInt().coerceIn(0, p.size - 2)
            val f = t - i
            xs.add(lerp(p.xy[2 * i], p.xy[2 * i + 2], f)); xs.add(lerp(p.xy[2 * i + 1], p.xy[2 * i + 3], f))
            p.pressure?.let { ps.add(lerp(it[i], it[i + 1], f)) }
            p.tMs?.let { ts.add(lerp(it[i].toFloat(), it[i + 1].toFloat(), f).toInt()) }
        }
        fun vertex(i: Int) {
            xs.add(p.xy[2 * i]); xs.add(p.xy[2 * i + 1])
            p.pressure?.let { ps.add(it[i]) }
            p.tMs?.let { ts.add(it[i]) }
        }
        at(from)
        for (i in (floor(from).toInt() + 1)..(ceil(to).toInt() - 1)) if (i > from && i < to) vertex(i)
        at(to)
        val t0 = ts.firstOrNull() ?: 0
        return StrokePoints(xs.toFloatArray(), p.pressure?.let { ps.toFloatArray() }, p.tMs?.let { IntArray(ts.size) { k -> ts[k] - t0 } })
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private companion object {
        const val UNCHANGED = 1e-3f
        const val MIN_PIECE_PT = 0.5f
    }
}
