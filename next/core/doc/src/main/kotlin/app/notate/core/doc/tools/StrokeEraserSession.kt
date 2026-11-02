package app.notate.core.doc.tools

import app.notate.core.geometry.Vec2
import app.notate.core.model.InkPoint
import app.notate.core.model.Item
import app.notate.core.model.ItemId
import app.notate.core.model.ToolPreset
import app.notate.core.model.edit.Edit

/** Whole-stroke eraser: everything touched by the swept circle is removed in one [Edit.Items] at gesture end. Main thread only. */
class StrokeEraserSession(private val preset: ToolPreset, private val ctx: ToolContext) : ToolSession {
    private val radius = ctx.settings.eraserRadiusPx / ctx.pxPerPt
    private val hit = LinkedHashMap<ItemId, Item>()
    private var lastX = 0f
    private var lastY = 0f
    private var started = false

    override fun begin(p: InkPoint) {
        started = true
        lastX = p.x; lastY = p.y
        sweep(p.x, p.y, p.x, p.y)
        show()
    }

    override fun move(batch: List<InkPoint>) {
        if (!started) return
        for (p in batch) { sweep(lastX, lastY, p.x, p.y); lastX = p.x; lastY = p.y }
        show()
    }

    override fun end(p: InkPoint) {
        if (!started) return
        sweep(lastX, lastY, p.x, p.y)
        ctx.preview.clear()
        started = false
        if (hit.isNotEmpty() && ctx.editor.content(ctx.page) != null) {
            ctx.editor.apply(Edit.Items(ctx.page, hit.values.toList(), emptyList()))
        }
        hit.clear()
    }

    override fun cancel() {
        started = false
        hit.clear()
        ctx.preview.clear()
    }

    private fun sweep(ax: Float, ay: Float, bx: Float, by: Float) {
        val content = ctx.editor.content(ctx.page) ?: return
        for (item in content.query(EraserGeometry.sweepBounds(ax, ay, bx, by, radius))) {
            if (item.id in hit || !EraserGeometry.erasable(item, preset)) continue
            if (EraserGeometry.hit(item, ax, ay, bx, by, radius)) hit[item.id] = item
        }
    }

    private fun show() {
        ctx.preview.show(Preview.Hidden(hit.keys.toSet()))
        ctx.preview.show(Preview.EraserCursor(Vec2(lastX, lastY), radius))
    }
}
