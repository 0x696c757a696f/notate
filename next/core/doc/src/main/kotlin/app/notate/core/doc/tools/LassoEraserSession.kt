package app.notate.core.doc.tools

import app.notate.core.doc.select.LassoHit
import app.notate.core.model.InkPoint
import app.notate.core.model.Layer
import app.notate.core.model.ToolPreset
import app.notate.core.model.edit.Edit

/** Deletes every object that lies (≥ 95 % of its points) inside the closed lasso, in one [Edit.Items]. Main thread only. */
class LassoEraserSession(private val preset: ToolPreset, private val ctx: ToolContext) : ToolSession {
    private var started = false
    private var path = FloatArray(64)
    private var n = 0

    override fun begin(p: InkPoint) {
        started = true
        n = 0
        add(p.x, p.y)
        show()
    }

    override fun move(batch: List<InkPoint>) {
        if (!started) return
        for (p in batch) add(p.x, p.y)
        show()
    }

    override fun end(p: InkPoint) {
        if (!started) return
        started = false
        add(p.x, p.y)
        ctx.preview.clear()
        val poly = path.copyOf(2 * n)
        val content = ctx.editor.content(ctx.page) ?: return
        if (n < 3) return
        val hit = content.query(LassoHit.polygonBounds(poly)).filter {
            LassoHit.selectable(it) && (!preset.eraseHighlighterOnly || it.layer == Layer.HIGHLIGHT) &&
                LassoHit.fractionInside(it, poly) >= FULLY_INSIDE
        }
        if (hit.isNotEmpty()) ctx.editor.apply(Edit.Items(ctx.page, hit, emptyList()))
    }

    override fun cancel() {
        started = false
        ctx.preview.clear()
    }

    private fun add(x: Float, y: Float) {
        if (2 * n + 2 > path.size) path = path.copyOf(path.size * 2)
        path[2 * n] = x; path[2 * n + 1] = y
        n++
    }

    private fun show() = ctx.preview.show(Preview.LassoPath(path.copyOf(2 * n)))

    private companion object { const val FULLY_INSIDE = 0.95f }
}
