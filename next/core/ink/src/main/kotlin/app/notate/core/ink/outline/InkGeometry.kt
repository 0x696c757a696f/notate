package app.notate.core.ink.outline

import app.notate.core.model.InkStyle
import app.notate.core.model.StrokePoints

/** Drawable geometry of one ink item, in page coordinates (pt). Independent of zoom. */
sealed interface InkGeometry {
    /** Closed polygon x0,y0,x1,y1,… to fill with the winding rule (self-intersections allowed). */
    class Fill(val polygon: FloatArray) : InkGeometry

    /** Open or closed polyline stroked with [width] (round caps and joins unless the pen says otherwise). */
    class Polyline(val points: FloatArray, val width: Float, val dash: FloatArray?) : InkGeometry

    /** Filled circles. */
    class Dots(val centers: FloatArray, val radii: FloatArray) : InkGeometry
}

/** Converts stroke samples to drawable geometry. Pure; any thread. */
interface OutlineGenerator {
    fun outline(points: StrokePoints, style: InkStyle): InkGeometry
}
