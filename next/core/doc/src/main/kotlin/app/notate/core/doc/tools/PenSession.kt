package app.notate.core.doc.tools

import app.notate.core.geometry.Affine
import app.notate.core.geometry.Vec2
import app.notate.core.ink.sample.StrokeSampler
import app.notate.core.ink.shape.DwellDetector
import app.notate.core.ink.shape.ShapeRecognizer
import app.notate.core.model.InkPoint
import app.notate.core.model.InkStyle
import app.notate.core.model.Item
import app.notate.core.model.ItemBounds
import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.ShapeKind
import app.notate.core.model.Stroke
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import app.notate.core.model.edit.Edit
import kotlin.math.hypot

/**
 * Pen and highlighter. Hold the pen still for `holdShapeMs` and a recognised shape replaces the stroke; further movement then
 * resizes it until pen-up. Hosts without a stationary-pen event should call [poll] from a timer. Main thread only.
 */
class PenSession(preset: ToolPreset, private val ctx: ToolContext) : ToolSession {
    private val editor = ctx.editor
    private val style = InkStyle(
        if (preset.kind == ToolKind.HIGHLIGHTER) PenType.HIGHLIGHTER else preset.pen ?: PenType.BALLPOINT,
        preset.color ?: 0xFF000000.toInt(), preset.widthPt ?: 1.134f,
    )
    private val pressureGain = preset.pressure / 0.7f
    private val sampler = StrokeSampler()
    private val dwell = DwellDetector()
    private var started = false
    private var ghost: Shape? = null
    private var anchor = Vec2(0f, 0f)       // shape centre (line kinds: the fixed start point)
    private var holdDist = 1f               // pen distance from the centre when the shape was recognised
    private var recognised: Shape? = null   // shape as recognised, before any resizing

    override fun begin(p: InkPoint) {
        started = true
        feed(p)
        preview()
    }

    override fun move(batch: List<InkPoint>) {
        if (!started) return
        for (p in batch) {
            if (recognised != null) adjust(p) else feed(p)
        }
        if (recognised == null) { preview(); poll() }
    }

    /** Checks the dwell timer; call when no pen movement arrives. */
    fun poll() {
        val hold = ctx.settings.holdShapeMs ?: return
        if (!started || recognised != null) return
        if (dwell.isDwelling(ctx.clock.now(), hold)) tryRecognise()
    }

    override fun end(p: InkPoint) {
        if (!started) return
        started = false
        val shape = recognised
        if (shape != null) {
            adjust(p)
            commit(ghost ?: shape)
        } else {
            feed(p)
            val pts = sampler.finish()
            if (pts.size >= 1) {
                commit(Stroke(editor.newItemId(), editor.nextOrder(ctx.page), style, pts, ItemBounds.strokeBounds(pts, style)))
            }
        }
        ctx.preview.clear()
    }

    override fun cancel() {
        started = false
        ctx.preview.clear()
    }

    private fun feed(p: InkPoint) {
        sampler.add(if (pressureGain == 1f) p else p.copy(pressure = (0.5f + (p.pressure - 0.5f) * pressureGain).coerceIn(0f, 1f)))
        dwell.onPoint(p.x, p.y, ctx.clock.now())
    }

    private fun preview() = ctx.preview.show(Preview.LiveStroke(sampler.points(), style))

    private fun tryRecognise() {
        val rec = ShapeRecognizer.recognize(sampler.points().xy)
        if (rec == null) { dwell.reset(); return }   // recognition failed: keep the stroke, do not retry on the same still run
        val params = rec.params
        val bounds = ItemBounds.shapeBounds(rec.kind, params, style)
        val shape = Shape(editor.newItemId(), editor.nextOrder(ctx.page), style, rec.kind, params, null, bounds)
        recognised = shape
        ghost = shape
        val last = sampler.points().let { Vec2(it.xy[it.xy.size - 2], it.xy[it.xy.size - 1]) }
        anchor = if (isLineKind(shape.kind)) Vec2(params[0], params[1]) else bounds.center
        holdDist = hypot(last.x - anchor.x, last.y - anchor.y).coerceAtLeast(MIN_DIST)
        ctx.preview.show(Preview.Ghost(listOf(shape)))
    }

    private fun adjust(p: InkPoint) {
        val base = recognised ?: return
        val next: Shape = if (isLineKind(base.kind)) {
            val params = base.params.copyOf().also { it[2] = p.x; it[3] = p.y }
            base.copy(params = params, bounds = ItemBounds.shapeBounds(base.kind, params, base.style))
        } else {
            val s = (hypot(p.x - anchor.x, p.y - anchor.y) / holdDist).coerceIn(MIN_SCALE, MAX_SCALE)
            base.transformed(Affine.scale(s, s, anchor.x, anchor.y)) as Shape
        }
        ghost = next
        ctx.preview.show(Preview.Ghost(listOf(next)))
    }

    private fun isLineKind(k: ShapeKind) = k == ShapeKind.LINE || k == ShapeKind.ARROW || k == ShapeKind.DOUBLE_ARROW

    private fun commit(item: Item) {
        if (editor.content(ctx.page) == null) return
        editor.apply(Edit.Items(ctx.page, emptyList(), listOf(item)))
    }

    private companion object {
        const val MIN_DIST = 1f
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 20f
    }
}
