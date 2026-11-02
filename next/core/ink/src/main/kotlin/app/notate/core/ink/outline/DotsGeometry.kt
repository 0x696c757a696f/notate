package app.notate.core.ink.outline

import app.notate.core.geometry.Geo
import app.notate.core.model.InkStyle
import app.notate.core.model.StrokePoints

/** DOTTED pen: a dot of diameter `w` every `2.5w` along the path (first and last point included). */
object DotsGeometry : OutlineGenerator {
    override fun outline(points: StrokePoints, style: InkStyle): InkGeometry {
        val w = style.widthPt
        val centers = if (points.size < 2) points.xy.copyOf() else Geo.resample(points.xy, 2.5f * w)
        return InkGeometry.Dots(centers, FloatArray(centers.size / 2) { w / 2f })
    }
}
