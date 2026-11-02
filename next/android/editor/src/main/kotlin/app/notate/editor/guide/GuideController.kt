package app.notate.editor.guide

import android.graphics.Canvas
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.doc.tools.GuideState
import app.notate.core.geometry.Vec2
import app.notate.core.ink.guide.GuideBody
import app.notate.core.ink.guide.GuideKind
import app.notate.core.ink.guide.ProtractorState
import app.notate.core.ink.guide.RulerState
import app.notate.core.input.FingerGesture
import app.notate.core.model.PageId
import app.notate.core.model.Units

/**
 * The ruler or protractor of one pane: where it lies (on a page, in page coordinates), the finger gestures that move it, the
 * [GuideState] a pen stroke snaps to and the overlay drawing. It is view state: nothing here is saved with the document.
 * Main thread only.
 */
class GuideController(
    private val density: Float,
    private val xdpi: Float,
    private val viewport: () -> Viewport,
    private val layout: () -> DocumentLayout,
    private val currentPage: () -> PageId?,
    /** The picture changed: redraw. */
    private val invalidate: () -> Unit,
    /** A guide appeared or went away: the pane switches the e-ink driver off / on. */
    private val onActiveChanged: () -> Unit,
    /** A finger drag of the guide starts / ends (fast refresh mode, settle). */
    private val onDrag: (Boolean) -> Unit,
) {
    private enum class Drag { NONE, MOVE, HANDLE, TWIST }

    private val layer = GuideLayer(density)
    private var body: GuideBody? = null
    private var page: PageId? = null
    private val rays = ArrayList<Float>()
    private var drag = Drag.NONE
    private var fx = 0f
    private var fy = 0f

    val kind: GuideKind? get() = body?.kind
    val active: Boolean get() = body != null

    /** Shows the guide in the middle of the page in view, at 0°; null hides it. */
    fun show(kind: GuideKind?) {
        if (kind == null) {
            if (body == null) return
            body = null
            page = null
            rays.clear()
            endDrag()
            invalidate()
            onActiveChanged()
            return
        }
        val p = currentPage() ?: return
        val vp = viewport()
        val frame = layout().frameOf(p)
        var cx = vp.scrollX + vp.widthPx / vp.scale / 2f - frame.l
        var cy = vp.scrollY + vp.heightPx / vp.scale / 2f - frame.t
        if (kind == GuideKind.PROTRACTOR) cy += Units.mm(30f)
        val had = body != null
        body = GuideBody(kind, cx, cy)
        page = p
        rays.clear()
        endDrag()
        invalidate()
        if (!had) onActiveChanged()
    }

    /** What a stroke that starts on [p] snaps to; [vp] is the viewport at pen-down. */
    fun stateFor(p: PageId, vp: Viewport): GuideState {
        val b = body
        if (b == null || p != page) return GuideState.NONE
        val tol = 6f * xdpi / 25.4f / vp.scale
        return object : GuideState {
            override val ruler: RulerState? = if (b.kind == GuideKind.RULER) b.ruler else null
            override val protractor: ProtractorState? = if (b.kind == GuideKind.PROTRACTOR) b.protractor else null
            override val snapTolPt: Float = tol
            override fun onRay(absAngleDeg: Float) {
                rays.add(absAngleDeg)
                while (rays.size > 2) rays.removeAt(0)
                invalidate()
            }
        }
    }

    private fun pagePoint(lx: Float, ly: Float): Vec2? {
        val p = page ?: return null
        val l = layout()
        if (l.indexOf(p) < 0) return null
        val vp = viewport()
        val f = l.frameOf(p)
        return Vec2(vp.scrollX + lx / vp.scale - f.l, vp.scrollY + ly / vp.scale - f.t)
    }

    private fun slackPt() = 3f * xdpi / 25.4f / viewport().scale
    private fun handlePt() = 8f * xdpi / 25.4f / viewport().scale

    /**
     * Offers a finger gesture to the guide; true = it is the guide's (the page must not scroll). Gestures that start on the guide
     * move it (one finger) or move and turn it (two fingers); all others are left to the page. [lx]/[ly] convert window
     * coordinates to pane-local px.
     */
    fun onGesture(g: FingerGesture, lx: (Float) -> Float, ly: (Float) -> Float): Boolean {
        val b = body ?: return false
        val vp = viewport()
        return when (g) {
            is FingerGesture.PanStart -> {
                fx = lx(g.x)
                fy = ly(g.y)
                val p = pagePoint(fx, fy) ?: return false
                drag = when {
                    b.nearHandle(p.x, p.y, handlePt()) -> { b.beginHandleDrag(p.x, p.y); Drag.HANDLE }
                    b.contains(p.x, p.y, slackPt()) -> Drag.MOVE
                    else -> return false
                }
                startDrag()
                true
            }
            is FingerGesture.Pan -> {
                if (drag != Drag.MOVE && drag != Drag.HANDLE) return false
                fx += g.dx
                fy += g.dy
                if (drag == Drag.MOVE) {
                    b.moveBy(g.dx / vp.scale, g.dy / vp.scale)
                } else {
                    pagePoint(fx, fy)?.let { b.dragHandle(it.x, it.y) }
                }
                rays.clear()
                invalidate()
                true
            }
            is FingerGesture.PanEnd -> endDrag(Drag.MOVE, Drag.HANDLE)
            is FingerGesture.PinchStart -> {
                val p = pagePoint(lx(g.fx), ly(g.fy)) ?: return false
                if (!b.contains(p.x, p.y, slackPt())) return false
                drag = Drag.TWIST
                startDrag()
                true
            }
            is FingerGesture.Pinch -> {
                if (drag != Drag.TWIST) return false
                val p = pagePoint(lx(g.fx), ly(g.fy)) ?: return true
                b.rotateAbout(p.x - g.dx / vp.scale, p.y - g.dy / vp.scale, g.rotationDeg)
                b.moveBy(g.dx / vp.scale, g.dy / vp.scale)
                b.resizeBy(g.scale)
                rays.clear()
                invalidate()
                true
            }
            FingerGesture.PinchEnd -> endDrag(Drag.TWIST)
            FingerGesture.Cancel -> endDrag(Drag.MOVE, Drag.HANDLE, Drag.TWIST)
            is FingerGesture.Tap -> g.fingers == 1 && onGuide(lx(g.x), ly(g.y))
            is FingerGesture.LongPress -> onGuide(lx(g.x), ly(g.y))
        }
    }

    private fun onGuide(x: Float, y: Float): Boolean {
        val p = pagePoint(x, y) ?: return false
        return body?.contains(p.x, p.y, slackPt()) == true
    }

    private fun startDrag() = onDrag(true)

    private fun endDrag(vararg modes: Drag): Boolean {
        if (drag == Drag.NONE || (modes.isNotEmpty() && drag !in modes)) return false
        drag = Drag.NONE
        onDrag(false)
        return true
    }

    fun draw(canvas: Canvas) {
        val b = body ?: return
        val p = page ?: return
        val l = layout()
        if (l.indexOf(p) < 0) return
        layer.draw(canvas, viewport(), l.frameOf(p), b, rays)
    }
}
