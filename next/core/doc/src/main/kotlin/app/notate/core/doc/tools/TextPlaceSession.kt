package app.notate.core.doc.tools

import app.notate.core.geometry.Affine
import app.notate.core.geometry.Rect
import app.notate.core.model.InkPoint
import app.notate.core.model.ItemBounds
import app.notate.core.model.TextBox
import app.notate.core.model.ToolPreset
import app.notate.core.model.Units
import app.notate.core.model.edit.Edit
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Text tool. A tap (pen moved < 2 mm) on an existing text box asks the pane to edit it ([ToolContext.onEditText]); a tap elsewhere
 * creates an empty box (half the page wide, one line high) at the tap and asks to edit that; a drag creates a box with the dragged
 * rectangle as frame. New boxes enter the document without an undo step: the editor adds the step when the text is committed.
 * Main thread only.
 */
class TextPlaceSession(private val preset: ToolPreset, private val ctx: ToolContext) : ToolSession {
    private var start: InkPoint? = null
    private var last: InkPoint? = null

    override fun begin(p: InkPoint) { start = p; last = p }

    override fun move(batch: List<InkPoint>) { if (batch.isNotEmpty()) last = batch.last() }

    override fun end(p: InkPoint) {
        val s = start ?: return
        start = null
        if (ctx.editor.content(ctx.page) == null) return
        val dist = hypot(p.x - s.x, p.y - s.y)
        if (dist < Units.mm(TAP_MM)) {
            val hit = hitBox(s.x, s.y)
            if (hit != null) ctx.onEditText(hit.id, false) else create(s.x, s.y, null)
        } else {
            create(min(s.x, p.x), min(s.y, p.y), Rect(min(s.x, p.x), min(s.y, p.y), max(s.x, p.x), max(s.y, p.y)))
        }
    }

    override fun cancel() { start = null }

    /** The topmost text box whose (rotated) frame contains the page point. */
    private fun hitBox(x: Float, y: Float): TextBox? {
        val content = ctx.editor.content(ctx.page) ?: return null
        val probe = Rect(x - 1f, y - 1f, x + 1f, y + 1f)
        return content.query(probe).filterIsInstance<TextBox>().filter { contains(it, x, y) }.maxByOrNull { it.order }
    }

    private fun contains(t: TextBox, x: Float, y: Float): Boolean {
        if (t.rotationDeg % 360f == 0f) return t.frame.contains(x, y)
        val c = t.frame.center
        val q = Affine.rotate(-t.rotationDeg, c.x, c.y).map(x, y)
        return t.frame.contains(q.x, q.y)
    }

    private fun create(x: Float, y: Float, dragged: Rect?) {
        val size = (preset.widthPt ?: DEFAULT_SIZE_PT).coerceAtLeast(MIN_SIZE_PT)
        val bold = preset.bold
        val pageW = ctx.pageBounds?.width
        var w = dragged?.width ?: ((pageW ?: DEFAULT_WIDTH_PT * 2f) * 0.5f)
        w = w.coerceAtLeast(size * 2f)
        var l = dragged?.l ?: x
        if (pageW != null) { w = min(w, pageW); l = l.coerceIn(0f, pageW - w) }
        var t = y
        ctx.pageBounds?.height?.let { h -> t = t.coerceIn(0f, max(0f, h - size * TextLayoutMetrics.LINE_HEIGHT)) }
        val oneLine = ctx.textMetrics.measure("", size, bold, w).coerceAtLeast(size * TextLayoutMetrics.LINE_HEIGHT)
        val h = max(dragged?.height ?: 0f, oneLine)
        val frame = Rect(l, t, l + w, t + h)
        val box = TextBox(
            id = ctx.editor.newItemId(), order = ctx.editor.nextOrder(ctx.page), frame = frame, rotationDeg = 0f, text = "",
            fontSizePt = size, color = preset.color ?: 0xFF000000.toInt(), bold = bold, align = preset.align,
            bounds = ItemBounds.frameBounds(frame, 0f),
        )
        ctx.editor.apply(Edit.Items(ctx.page, emptyList(), listOf(box)), undoable = false)
        ctx.onEditText(box.id, true)
    }

    private companion object {
        const val TAP_MM = 2f
        const val DEFAULT_SIZE_PT = 14f
        const val MIN_SIZE_PT = 4f
        const val DEFAULT_WIDTH_PT = 300f
    }
}
