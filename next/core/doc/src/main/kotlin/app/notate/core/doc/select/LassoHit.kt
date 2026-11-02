package app.notate.core.doc.select

import app.notate.core.geometry.Geo
import app.notate.core.geometry.Rect
import app.notate.core.ink.shape.ShapeOutline
import app.notate.core.model.ImageItem
import app.notate.core.model.Item
import app.notate.core.model.Shape
import app.notate.core.model.Stroke

/** Lasso inclusion rules shared by the select and erase tools. Pure. */
internal object LassoHit {
    /** Fraction of the item's sample points inside [polygon]: stroke points, shape outline points, else the bounds centre. */
    fun fractionInside(item: Item, polygon: FloatArray): Float = when (item) {
        is Stroke -> Geo.fractionInside(item.points.xy, polygon)
        is Shape -> {
            val outline = ShapeOutline.of(item.kind, item.params, item.style.widthPt)
            val pts = if (outline.isEmpty()) item.params else outline.fold(FloatArray(0)) { acc, o -> acc + o.points }
            Geo.fractionInside(pts, polygon)
        }
        else -> item.bounds.center.let { if (Geo.pointInPolygon(it.x, it.y, polygon)) 1f else 0f }
    }

    /** Locked images are part of the page and can be neither selected nor erased with the lasso. */
    fun selectable(item: Item): Boolean = !(item is ImageItem && item.locked)

    fun polygonBounds(polygon: FloatArray): Rect = Rect.ofPoints(polygon)

    fun rectPolygon(r: Rect): FloatArray = floatArrayOf(r.l, r.t, r.r, r.t, r.r, r.b, r.l, r.b)
}
