package app.notate.editor.view

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import app.notate.core.doc.content.PageContent
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.doc.tools.Preview
import app.notate.core.doc.tools.PreviewSink
import app.notate.core.geometry.Rect
import app.notate.core.model.Item
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.Stroke
import app.notate.render.ink.GeometryCache
import app.notate.render.ink.InkRenderer
import app.notate.render.ink.PaintPool
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Draws what a tool session shows while a gesture runs (software ink, shape ghost, eraser circle, hidden items) above the
 * tiles. After the gesture ends the picture stays as an *overlay* until the tiles under it were re-rendered
 * ([releaseOverlay]), so there is no gap in which the stroke disappears. Main thread only.
 */
class LiveInkLayer(
    private val host: View,
    private val density: Float,
    private val content: (PageId) -> PageContent?,
) : PreviewSink {
    private val geometry = GeometryCache(maxEntries = 16)
    private val renderer = InkRenderer(geometry)
    private val paints = PaintPool()
    private val white = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val whiteStroke = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val cursorPaint = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; isAntiAlias = true }

    /** Page the gesture runs on; its frame places everything. */
    var page: PageId? = null

    /** True while the e-ink driver draws the stroke itself: the live stroke is then not drawn here. */
    var driverDraws: Boolean = false

    /**
     * Items an eraser gesture currently hides. Panes whose content is transparent (web chapters) must leave these out of their
     * own drawing and set [paintHiddenWhite] to false: white paint would blot out the text underneath.
     */
    val hiddenIds: Set<ItemId> get() = hidden.keys

    var paintHiddenWhite: Boolean = true

    var viewportProvider: () -> Viewport = { Viewport(0f, 0f, 1f, 1f, 0, 0) }
    var layoutProvider: () -> DocumentLayout? = { null }

    private class Entry(val page: PageId, val item: Item, val hide: Boolean)

    private var stroke: Stroke? = null
    private var ghost: List<Item> = emptyList()
    private val hidden = LinkedHashMap<ItemId, Item>()
    private val overlay = ArrayList<Entry>()
    private var cursorCenterX = 0f
    private var cursorCenterY = 0f
    private var cursorRadius = -1f
    private var lastBounds: Rect = Rect.EMPTY
    private var lastPageForBounds: PageId? = null

    val hasOverlay: Boolean get() = stroke != null || ghost.isNotEmpty() || hidden.isNotEmpty() || overlay.isNotEmpty()

    override fun show(p: Preview) {
        when (p) {
            is Preview.LiveStroke -> stroke = Stroke(ItemId(-1), 0, p.style, p.points, ItemBounds.strokeBounds(p.points, p.style))
            is Preview.Ghost -> ghost = p.items
            is Preview.Hidden -> {
                val c = page?.let(content)
                for (id in p.ids) if (id !in hidden) c?.get(id)?.let { hidden[id] = it }
            }
            is Preview.EraserCursor -> { cursorCenterX = p.center.x; cursorCenterY = p.center.y; cursorRadius = p.radius }
            is Preview.LassoPath -> Unit
        }
        invalidateChanged()
    }

    /** The session finished: the cursor goes, the result stays in the overlay until [releaseOverlay]. */
    override fun clear() {
        val pg = page
        if (pg != null) {
            for (h in hidden.values) overlay.add(Entry(pg, h, hide = true))
            ghost.forEach { overlay.add(Entry(pg, it, hide = false)) }
            stroke?.let { overlay.add(Entry(pg, it, hide = false)) }
        }
        hidden.clear()
        ghost = emptyList()
        stroke = null
        cursorRadius = -1f
        invalidateChanged()
    }

    /** Drops the current gesture's picture (cancelled gesture). */
    fun discardCurrent() {
        hidden.clear()
        ghost = emptyList()
        stroke = null
        cursorRadius = -1f
        invalidateChanged()
    }

    /** Drops everything at once: the tiles now show the result. */
    fun releaseOverlay() {
        overlay.clear()
        invalidateAll()
    }

    fun draw(canvas: Canvas, vp: Viewport, layout: DocumentLayout) {
        if (!hasOverlay && cursorRadius < 0f) return
        val s = vp.scale
        for (e in overlay) drawEntry(canvas, vp, layout, e.page, e.item, e.hide)
        val pg = page ?: return
        for (item in hidden.values) drawEntry(canvas, vp, layout, pg, item, true)
        for (item in ghost) drawEntry(canvas, vp, layout, pg, item, false)
        val st = stroke
        if (st != null && !driverDraws) drawEntry(canvas, vp, layout, pg, st, false)
        if (cursorRadius >= 0f) {
            val f = layout.frameOf(pg)
            canvas.save()
            canvas.scale(s, s)
            canvas.translate(f.l - vp.scrollX, f.t - vp.scrollY)
            cursorPaint.strokeWidth = density / s
            canvas.drawCircle(cursorCenterX, cursorCenterY, cursorRadius, cursorPaint)
            canvas.restore()
        }
    }

    private fun drawEntry(canvas: Canvas, vp: Viewport, layout: DocumentLayout, pg: PageId, item: Item, hide: Boolean) {
        val f = layout.frameOf(pg)
        if (f.isEmpty) return
        val s = vp.scale
        canvas.save()
        canvas.clipRect((f.l - vp.scrollX) * s, (f.t - vp.scrollY) * s, (f.r - vp.scrollX) * s, (f.b - vp.scrollY) * s)
        canvas.scale(s, s)
        canvas.translate(f.l - vp.scrollX, f.t - vp.scrollY)
        if (hide) { if (paintHiddenWhite) drawWhite(canvas, item, s) } else renderer.draw(canvas, item, paints)
        canvas.restore()
    }

    /** Hides [item] by painting its outline in paper white, one pixel wider than the ink. */
    private fun drawWhite(canvas: Canvas, item: Item, scale: Float) {
        val grow = 1f / scale
        try {
            val g = geometry.pathFor(item)
            g.fill?.let { canvas.drawPath(it, white) }
            for (part in g.strokes) {
                whiteStroke.strokeWidth = part.width + 2f * grow
                canvas.drawPath(part.path, whiteStroke)
            }
            g.dots?.let { d ->
                for (i in d.radii.indices) canvas.drawCircle(d.centers[2 * i], d.centers[2 * i + 1], d.radii[i] + grow, white)
            }
        } catch (e: IllegalArgumentException) {
            val b = item.bounds
            canvas.drawRect(b.l, b.t, b.r, b.b, white)
        }
    }

    private fun currentBounds(): Rect {
        var r = Rect.EMPTY
        stroke?.let { r = r.union(it.bounds) }
        for (g in ghost) r = r.union(g.bounds)
        for (h in hidden.values) r = r.union(h.bounds)
        if (cursorRadius >= 0f) r = r.union(Rect.around(cursorCenterX, cursorCenterY, cursorRadius + 2f))
        return r
    }

    /** Invalidates the union of the previous and the new bounds of the current gesture only (cheap on e-ink). */
    private fun invalidateChanged() {
        val pg = page
        val layout = layoutProvider()
        val now = currentBounds()
        val both = now.union(if (lastPageForBounds == pg) lastBounds else Rect.EMPTY)
        lastBounds = now
        lastPageForBounds = pg
        if (pg == null || layout == null || both.isEmpty) {
            host.invalidate()
            return
        }
        val f = layout.frameOf(pg)
        val vp = viewportProvider()
        val s = vp.scale
        host.invalidate(
            floor((f.l + both.l - vp.scrollX) * s).toInt() - 2, floor((f.t + both.t - vp.scrollY) * s).toInt() - 2,
            ceil((f.l + both.r - vp.scrollX) * s).toInt() + 2, ceil((f.t + both.b - vp.scrollY) * s).toInt() + 2,
        )
    }

    private fun invalidateAll() {
        lastBounds = Rect.EMPTY
        host.invalidate()
    }
}
