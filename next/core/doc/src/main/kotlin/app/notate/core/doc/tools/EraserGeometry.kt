package app.notate.core.doc.tools

import app.notate.core.geometry.Geo
import app.notate.core.geometry.Rect
import app.notate.core.ink.shape.ShapeOutline
import app.notate.core.model.Item
import app.notate.core.model.Layer
import app.notate.core.model.Shape
import app.notate.core.model.Stroke
import app.notate.core.model.ToolPreset

/** Shared hit-testing for the erasers. Pure. */
internal object EraserGeometry {
    /** Only strokes and shapes can be erased; text, images and links are removed with the lasso. */
    fun erasable(item: Item, preset: ToolPreset): Boolean {
        if (item !is Stroke && item !is Shape) return false
        return !preset.eraseHighlighterOnly || item.layer == Layer.HIGHLIGHT
    }

    /** Distance between segments a–b and c–d (0 when they cross). */
    fun segmentDistance(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float {
        if (Geo.segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        return minOf(
            Geo.distPointSegment(ax, ay, cx, cy, dx, dy), Geo.distPointSegment(bx, by, cx, cy, dx, dy),
            Geo.distPointSegment(cx, cy, ax, ay, bx, by), Geo.distPointSegment(dx, dy, ax, ay, bx, by),
        )
    }

    /** Distance from segment a–b to the polyline [xy] (point distance when it has one point). */
    fun segmentToPolyline(ax: Float, ay: Float, bx: Float, by: Float, xy: FloatArray): Float {
        val n = xy.size / 2
        if (n == 0) return Float.POSITIVE_INFINITY
        if (n == 1) return Geo.distPointSegment(xy[0], xy[1], ax, ay, bx, by)
        var best = Float.POSITIVE_INFINITY
        for (i in 0 until n - 1) {
            val d = segmentDistance(ax, ay, bx, by, xy[2 * i], xy[2 * i + 1], xy[2 * i + 2], xy[2 * i + 3])
            if (d < best) best = d
        }
        return best
    }

    /** True when the eraser circle of radius [r] swept along a–b touches [item] (stroke width counted). */
    fun hit(item: Item, ax: Float, ay: Float, bx: Float, by: Float, r: Float): Boolean = when (item) {
        is Stroke -> segmentToPolyline(ax, ay, bx, by, item.points.xy) <= r + item.style.widthPt / 2f
        is Shape -> {
            val reach = r + item.style.widthPt / 2f
            ShapeOutline.of(item.kind, item.params, item.style.widthPt).any { segmentToPolyline(ax, ay, bx, by, it.points) <= reach }
        }
        else -> false
    }

    fun sweepBounds(ax: Float, ay: Float, bx: Float, by: Float, r: Float): Rect =
        Rect(minOf(ax, bx) - r, minOf(ay, by) - r, maxOf(ax, bx) + r, maxOf(ay, by) + r)
}
