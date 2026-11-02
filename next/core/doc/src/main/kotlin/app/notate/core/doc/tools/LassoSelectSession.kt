package app.notate.core.doc.tools

import app.notate.core.doc.select.SelectionController
import app.notate.core.doc.select.SelectionGeometry
import app.notate.core.geometry.Rect
import app.notate.core.model.InkPoint
import app.notate.core.model.LassoMode
import app.notate.core.model.ToolPreset

/**
 * Lasso tool. A pen-down on an existing selection's handle or box becomes a transform of that selection; anywhere else it
 * draws a lasso (freeform, or a rectangle between first and last point) and selects what it encloses. Main thread only.
 */
class LassoSelectSession(
    private val preset: ToolPreset,
    private val ctx: ToolContext,
    private val controller: SelectionController,
) : ToolSession {
    private var transforming = false
    private var started = false
    private var path = FloatArray(64)
    private var n = 0

    override fun begin(p: InkPoint) {
        started = true
        val sel = controller.selection.value
        if (sel != null && sel.page == ctx.page) {
            val tol = ctx.settings.handleHitPx / ctx.pxPerPt
            val h = SelectionGeometry.hit(sel.bounds, p.x, p.y, tol, ctx.settings.rotateOffsetPx / ctx.pxPerPt)
            if (h != null) {
                transforming = true
                val o = controller.originOf(ctx.page)
                controller.beginTransform(h, p.x + o.x, p.y + o.y)
                return
            }
        }
        controller.clear()
        n = 0
        add(p.x, p.y)
        show()
    }

    override fun move(batch: List<InkPoint>) {
        if (!started) return
        if (transforming) {
            for (p in batch) drag(p)
            return
        }
        for (p in batch) add(p.x, p.y)
        show()
    }

    override fun end(p: InkPoint) {
        if (!started) return
        started = false
        if (transforming) {
            drag(p)
            transforming = false
            controller.endTransform()
            return
        }
        add(p.x, p.y)
        ctx.preview.clear()
        val poly = path.copyOf(2 * n)
        if (preset.lassoMode == LassoMode.RECTANGLE) {
            val r = Rect(minOf(poly[0], poly[2 * n - 2]), minOf(poly[1], poly[2 * n - 1]), maxOf(poly[0], poly[2 * n - 2]), maxOf(poly[1], poly[2 * n - 1]))
            if (r.isEmpty) controller.clear() else controller.selectByRect(ctx.page, r)
        } else {
            controller.selectByLasso(ctx.page, poly)
        }
    }

    override fun cancel() {
        if (!started) return
        started = false
        if (transforming) {
            transforming = false
            controller.clear()
        }
        ctx.preview.clear()
    }

    private fun drag(p: InkPoint) {
        val o = controller.originOf(ctx.page)
        controller.updateTransform(p.x + o.x, p.y + o.y, controller.pageAtSelectionCenter())
    }

    private fun add(x: Float, y: Float) {
        if (2 * n + 2 > path.size) path = path.copyOf(path.size * 2)
        path[2 * n] = x; path[2 * n + 1] = y
        n++
    }

    private fun show() {
        if (preset.lassoMode == LassoMode.RECTANGLE && n >= 1) {
            val x0 = path[0]; val y0 = path[1]; val x1 = path[2 * n - 2]; val y1 = path[2 * n - 1]
            ctx.preview.show(Preview.LassoPath(floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)))
        } else {
            ctx.preview.show(Preview.LassoPath(path.copyOf(2 * n)))
        }
    }
}
