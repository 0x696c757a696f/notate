package app.notate.core.doc.tools

import app.notate.core.geometry.Affine
import app.notate.core.model.InkPoint
import app.notate.core.model.Item
import app.notate.core.model.edit.Edit

/**
 * Insert space: touch the page at a line and drag down; everything that starts below the line follows the pen, opening a gap.
 * Items that straddle the line stay where they are. On a bounded page the gap stops where the lowest moved item reaches the page
 * bottom, so nothing is pushed off the page. One gesture is one undoable edit. Main thread only.
 * Idea from the MIT-licensed xnotes-android (its Insert space tool); no code copied.
 */
class InsertSpaceSession(private val ctx: ToolContext) : ToolSession {
    private var started = false
    private var startY = 0f
    private var movers: List<Item> = emptyList()
    private var limit = 0f
    private var delta = 0f

    override fun begin(p: InkPoint) {
        val content = ctx.editor.content(ctx.page) ?: return
        started = true
        startY = p.y
        delta = 0f
        movers = content.all().filter { it.bounds.t >= startY }.toList()
        val room = ctx.pageBounds?.let { b -> b.b - (movers.maxOfOrNull { it.bounds.b } ?: startY) }
        limit = room?.coerceAtLeast(0f) ?: Float.POSITIVE_INFINITY
    }

    override fun move(batch: List<InkPoint>) {
        if (!started || batch.isEmpty()) return
        delta = (batch.last().y - startY).coerceIn(0f, limit)
        show()
    }

    override fun end(p: InkPoint) {
        if (!started) return
        started = false
        delta = (p.y - startY).coerceIn(0f, limit)
        ctx.preview.clear()
        if (delta >= MIN_DELTA_PT && movers.isNotEmpty() && ctx.editor.content(ctx.page) != null) {
            val shift = Affine.translate(0f, delta)
            ctx.editor.apply(Edit.Items(ctx.page, movers, movers.map { it.transformed(shift) }))
        }
        movers = emptyList()
    }

    override fun cancel() {
        started = false
        movers = emptyList()
        ctx.preview.clear()
    }

    private fun show() {
        val shift = Affine.translate(0f, delta)
        ctx.preview.show(Preview.Hidden(movers.map { it.id }.toSet()))
        ctx.preview.show(Preview.Ghost(movers.map { it.transformed(shift) }))
    }

    private companion object {
        /** A drag shorter than this is a tap and changes nothing. */
        const val MIN_DELTA_PT = 2f
    }
}
