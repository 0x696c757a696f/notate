package app.notate.editor.pane

import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.geometry.Rect
import app.notate.core.geometry.Vec2
import app.notate.core.model.PageId

/** A point on a page, in page coordinates (pt). */
class PagePoint(val page: PageId, val x: Float, val y: Float)

/** Screen (view-local px) ⇄ document plane ⇄ page coordinate arithmetic. Pure. */
object PaneCoords {
    fun toDoc(vp: Viewport, x: Float, y: Float): Vec2 = Vec2(vp.scrollX + x / vp.scale, vp.scrollY + y / vp.scale)

    /** The page under the screen point, or null over the gap between pages / outside every page. */
    fun toPage(vp: Viewport, layout: DocumentLayout, x: Float, y: Float): PagePoint? {
        val d = toDoc(vp, x, y)
        val page = layout.pageAt(d.x, d.y) ?: return null
        val f = layout.frameOf(page)
        return PagePoint(page, d.x - f.l, d.y - f.t)
    }

    /** Page coordinates of a screen point relative to a fixed page [frame] (used while a stroke is in progress). */
    fun toPageIn(vp: Viewport, frame: Rect, x: Float, y: Float): Vec2 {
        val d = toDoc(vp, x, y)
        return Vec2(d.x - frame.l, d.y - frame.t)
    }

    /** Screen px of a page-coordinate point on a page whose frame is [frame]. */
    fun toScreen(vp: Viewport, frame: Rect, px: Float, py: Float): Vec2 =
        Vec2((frame.l + px - vp.scrollX) * vp.scale, (frame.t + py - vp.scrollY) * vp.scale)
}
