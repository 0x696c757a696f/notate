package app.notate.core.doc.tools

import app.notate.core.geometry.Affine
import app.notate.core.geometry.Vec2
import app.notate.core.ink.guide.GuidePath
import app.notate.core.ink.guide.GuideSnap
import app.notate.core.ink.guide.Guides
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
import app.notate.core.model.StrokePoints
import app.notate.core.model.Stroke
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import app.notate.core.model.edit.Edit
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pen and highlighter. Hold the pen still for `holdShapeMs` and a recognised shape replaces the stroke; further movement then
 * resizes it until pen-up. Hosts without a stationary-pen event should call [poll] from a timer. Main thread only.
 */
class PenSession(private val preset: ToolPreset, private val ctx: ToolContext) : ToolSession {
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
    private var fixed = Vec2(0f, 0f)        // polygon kinds: the vertex that stays put (the first one)
    private var holdVec = Vec2(1f, 0f)      // polygon kinds: fixed → the vertex that follows the pen, as recognised
    private var holdPen = Vec2(0f, 0f)      // pen position when the shape was recognised

    // Ruler / protractor: the geometry the whole stroke follows, and the raw pen positions it is built from.
    private var snap: GuideSnap? = null
    private var raw = FloatArray(64)
    private var rawT = IntArray(32)
    private var rawN = 0
    private var pressureSum = 0f
    private var lastT = 0
    private var firstT = 0L

    /** True once a held shape replaced the stroke: the pen now only resizes it (hosts swap the driver ink for the software ghost). */
    val isAdjusting: Boolean get() = recognised != null

    override fun begin(p: InkPoint) {
        started = true
        snap = findSnap(p.x, p.y)
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
        if (!started || recognised != null || snap != null) return
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
            val g = snap
            val pts = if (g != null) guided(g) else sampler.finish()
            if (pts.size >= 1 && g == null && scribbleErased()) {
                // A scribble-out: the covered items go in one undoable edit and the scribble itself is not kept.
            } else if (pts.size >= 1) {
                commit(Stroke(editor.newItemId(), editor.nextOrder(ctx.page), style, pts, ItemBounds.strokeBounds(pts, style)))
                if (g is GuideSnap.Ray && rawN > 0) ctx.guides.onRay(Guides.rayAngleDeg(g, raw[2 * rawN - 2], raw[2 * rawN - 1]))
            }
        }
        ctx.preview.clear()
    }

    override fun cancel() {
        started = false
        ctx.preview.clear()
    }

    private fun findSnap(x: Float, y: Float): GuideSnap? {
        val g = ctx.guides
        val tol = g.snapTolPt
        return g.ruler?.let { Guides.rulerSnap(it, x, y, tol) } ?: g.protractor?.let { Guides.protractorSnap(it, x, y, tol) }
    }

    /** The stroke projected onto the guide geometry; pressure is the average of the pen's. */
    private fun guided(g: GuideSnap): StrokePoints {
        val xy = GuidePath.build(g, raw.copyOf(2 * rawN))
        val n = xy.size / 2
        val pr = if (rawN > 0) pressureSum / rawN else 0.5f
        return StrokePoints(xy, FloatArray(n) { pr }, IntArray(n) { if (n <= 1) 0 else (lastT.toLong() * it / (n - 1)).toInt() })
    }

    private fun feed(p: InkPoint) {
        // Raw pen positions: the ruler/protractor build from them, and scribble detection needs them unsmoothed.
        if (rawN == 0) firstT = p.tMs
        if (2 * rawN + 2 > raw.size) raw = raw.copyOf(raw.size * 2)
        if (2 * rawN + 2 > rawT.size * 2) rawT = rawT.copyOf(rawT.size * 2)
        raw[2 * rawN] = p.x; raw[2 * rawN + 1] = p.y
        rawT[rawN] = (p.tMs - firstT).toInt().coerceAtLeast(0)
        rawN++
        pressureSum += p.pressure.coerceIn(0f, 1f)
        lastT = (p.tMs - firstT).toInt().coerceAtLeast(0)
        sampler.add(if (pressureGain == 1f) p else p.copy(pressure = (0.5f + (p.pressure - 0.5f) * pressureGain).coerceIn(0f, 1f)))
        dwell.onPoint(p.x, p.y, ctx.clock.now())
    }

    private fun preview() = ctx.preview.show(Preview.LiveStroke(snap?.let { guided(it) } ?: sampler.points(), style))

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
        holdPen = last
        if (usesVertexAnchor(shape.kind)) {
            // Pivot on the first vertex; the vertex farthest from it follows the pen, so the shape scales and rotates around the pivot.
            fixed = Vec2(params[0], params[1])
            var far = Vec2(params[0], params[1]); var best = -1f
            for (i in 1 until params.size / 2) {
                val d = hypot(params[2 * i] - fixed.x, params[2 * i + 1] - fixed.y)
                if (d > best) { best = d; far = Vec2(params[2 * i], params[2 * i + 1]) }
            }
            holdVec = Vec2(far.x - fixed.x, far.y - fixed.y).let { if (hypot(it.x, it.y) < MIN_DIST) Vec2(MIN_DIST, 0f) else it }
        }
        ctx.preview.show(Preview.Ghost(listOf(shape)))
    }

    private fun adjust(p: InkPoint) {
        val base = recognised ?: return
        val next: Shape = if (isLineKind(base.kind)) {
            val params = base.params.copyOf().also { it[2] = p.x; it[3] = p.y }
            base.copy(params = params, bounds = ItemBounds.shapeBounds(base.kind, params, base.style))
        } else if (usesVertexAnchor(base.kind)) {
            val v1x = holdVec.x + (p.x - holdPen.x)
            val v1y = holdVec.y + (p.y - holdPen.y)
            val len0 = hypot(holdVec.x, holdVec.y)
            val s = (hypot(v1x, v1y) / len0).coerceIn(MIN_SCALE, MAX_SCALE)
            val rot = Math.toDegrees((atan2(v1y, v1x) - atan2(holdVec.y, holdVec.x)).toDouble()).toFloat()
            base.transformed(Affine.rotate(rot, fixed.x, fixed.y) * Affine.scale(s, s, fixed.x, fixed.y)) as Shape
        } else {
            val s = (hypot(p.x - anchor.x, p.y - anchor.y) / holdDist).coerceIn(MIN_SCALE, MAX_SCALE)
            base.transformed(Affine.scale(s, s, anchor.x, anchor.y)) as Shape
        }
        // Resizing must not thicken the line: keep the pen width.
        val fixedNext = if (next.style == base.style) next else next.copy(style = base.style, bounds = ItemBounds.shapeBounds(next.kind, next.params, base.style))
        ghost = fixedNext
        ctx.preview.show(Preview.Ghost(listOf(fixedNext)))
    }

    private fun usesVertexAnchor(k: ShapeKind) = k == ShapeKind.RECT || k == ShapeKind.TRIANGLE || k == ShapeKind.POLYGON || k == ShapeKind.POLYLINE

    private fun isLineKind(k: ShapeKind) = k == ShapeKind.LINE || k == ShapeKind.ARROW || k == ShapeKind.DOUBLE_ARROW

    /** Erases what the raw stroke scribbles out (pen only, not the highlighter); true when it did. */
    private fun scribbleErased(): Boolean {
        if (!ctx.settings.scribbleErase || style.pen == PenType.HIGHLIGHTER) return false
        val content = editor.content(ctx.page) ?: return false
        val hit = ScribbleEraser.targets(raw.copyOf(2 * rawN), rawT.copyOf(rawN), content::query, preset, ctx.pxPerPt)
        if (hit.isEmpty()) return false
        editor.apply(Edit.Items(ctx.page, hit, emptyList()))
        return true
    }

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
