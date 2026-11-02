package app.notate.core.doc.select

import app.notate.core.geometry.Rect
import app.notate.core.geometry.Vec2
import app.notate.core.model.ItemId
import app.notate.core.model.PageId

/**
 * The current selection. [bounds] is the axis-aligned box in page coordinates of [page]; while a transform is running it
 * follows the ghost and [rotationDeg] is the live rotation angle (0 otherwise).
 */
data class Selection(val page: PageId, val ids: Set<ItemId>, val bounds: Rect, val rotationDeg: Float = 0f)

/** Transform handles of the selection box. */
enum class Handle { MOVE, N, S, E, W, NE, NW, SE, SW, ROTATE }

/** Where the handles sit; shared by hit-testing in core and drawing in the Android layer. Pure. */
object SelectionGeometry {
    /** Position of [h] on [b]; MOVE is the centre, ROTATE sits [rotateOffset] above the top edge. */
    fun handlePosition(b: Rect, h: Handle, rotateOffset: Float): Vec2 {
        val c = b.center
        return when (h) {
            Handle.MOVE -> c
            Handle.N -> Vec2(c.x, b.t)
            Handle.S -> Vec2(c.x, b.b)
            Handle.E -> Vec2(b.r, c.y)
            Handle.W -> Vec2(b.l, c.y)
            Handle.NE -> Vec2(b.r, b.t)
            Handle.NW -> Vec2(b.l, b.t)
            Handle.SE -> Vec2(b.r, b.b)
            Handle.SW -> Vec2(b.l, b.b)
            Handle.ROTATE -> Vec2(c.x, b.t - rotateOffset)
        }
    }

    /**
     * The handle under ([x], [y]) within [tolerance] (all in pt), or MOVE when inside the box, or null. Rotation and corner
     * handles win over edge handles, which win over the box interior.
     */
    fun hit(b: Rect, x: Float, y: Float, tolerance: Float, rotateOffset: Float): Handle? {
        val order = listOf(Handle.ROTATE, Handle.NE, Handle.NW, Handle.SE, Handle.SW, Handle.N, Handle.S, Handle.E, Handle.W)
        for (h in order) {
            val p = handlePosition(b, h, rotateOffset)
            if (kotlin.math.abs(p.x - x) <= tolerance && kotlin.math.abs(p.y - y) <= tolerance) return h
        }
        return if (b.contains(x, y)) Handle.MOVE else null
    }
}
