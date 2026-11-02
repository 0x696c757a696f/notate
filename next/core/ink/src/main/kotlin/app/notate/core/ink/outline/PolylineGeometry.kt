package app.notate.core.ink.outline

import app.notate.core.model.InkStyle
import app.notate.core.model.PenType
import app.notate.core.model.StrokePoints

/** Constant-width pens: FINELINER, HIGHLIGHTER (square caps are the renderer's job) and DASHED (`dash = [3w, 2w]`). */
class PolylineGeometry(private val pen: PenType) : OutlineGenerator {
    override fun outline(points: StrokePoints, style: InkStyle): InkGeometry {
        val w = style.widthPt
        // A single point becomes a zero-length segment so round caps draw a dot.
        val xy = if (points.size == 1) floatArrayOf(points.xy[0], points.xy[1], points.xy[0], points.xy[1]) else points.xy
        return InkGeometry.Polyline(xy, w, if (pen == PenType.DASHED) floatArrayOf(3f * w, 2f * w) else null)
    }
}
