package app.notate.core.model

import app.notate.core.geometry.Affine
import app.notate.core.geometry.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Bounds computation for items. Pure; any thread. */
object ItemBounds {
    private const val AA_MARGIN = 0.5f
    /** Arrow head length = this × line width (also the padding applied to arrow/axes bounds). */
    const val ARROW_HEAD_PER_WIDTH = 6f

    /** Largest rendered width for a pen at full pressure (all pen formulas peak at the nominal width). */
    fun maxWidth(style: InkStyle): Float = style.widthPt

    /** Bounds of a stroke: point bounding box grown by `maxWidth / 2 + 0.5`. */
    fun strokeBounds(points: StrokePoints, style: InkStyle): Rect {
        if (points.size == 0) return Rect.EMPTY
        return Rect.ofPoints(points.xy).inset(-(maxWidth(style) / 2f + AA_MARGIN))
    }

    /**
     * Bounds of a shape. Rotation angles in params are degrees, clockwise on the y-down page. For AXES_* the local frame has
     * x to the right and y up; AXES_2D spans ±xLen/±yLen around the origin, AXES_Q1 spans 0..xLen and 0..yLen.
     */
    fun shapeBounds(kind: ShapeKind, params: FloatArray, style: InkStyle): Rect {
        if (params.isEmpty()) return Rect.EMPTY
        val grow = maxWidth(style) / 2f + AA_MARGIN
        val head = ARROW_HEAD_PER_WIDTH * style.widthPt
        return when (kind) {
            ShapeKind.LINE, ShapeKind.RECT, ShapeKind.TRIANGLE, ShapeKind.POLYGON, ShapeKind.POLYLINE ->
                Rect.ofPoints(params).inset(-grow)
            ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW -> Rect.ofPoints(params).inset(-(grow + head))
            ShapeKind.ELLIPSE -> {
                if (params.size < 5) return Rect.EMPTY
                val rad = Math.toRadians(params[4].toDouble())
                val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
                val rx = params[2]; val ry = params[3]
                val hx = sqrt(rx * rx * c * c + ry * ry * s * s)
                val hy = sqrt(rx * rx * s * s + ry * ry * c * c)
                Rect(params[0] - hx, params[1] - hy, params[0] + hx, params[1] + hy).inset(-grow)
            }
            ShapeKind.AXES_2D, ShapeKind.AXES_Q1 -> {
                if (params.size < 5) return Rect.EMPTY
                val (ox, oy, xl, yl, ang) = params
                val x0 = if (kind == ShapeKind.AXES_2D) -abs(xl) else 0f
                val y0 = if (kind == ShapeKind.AXES_2D) -abs(yl) else 0f
                // Local y points up, i.e. page -y; build local corners then rotate around the origin.
                val local = floatArrayOf(x0, y0, abs(xl), y0, abs(xl), abs(yl), x0, abs(yl))
                val pts = FloatArray(8)
                for (i in 0 until 4) { pts[2 * i] = ox + local[2 * i]; pts[2 * i + 1] = oy - local[2 * i + 1] }
                Affine.rotate(ang, ox, oy).mapRect(Rect.ofPoints(pts)).inset(-(grow + head))
            }
        }
    }

    /** Bounds of a possibly rotated frame (rotation around the frame centre). */
    fun frameBounds(frame: Rect, rotationDeg: Float): Rect {
        if (rotationDeg % 360f == 0f) return frame
        val c = frame.center
        return Affine.rotate(rotationDeg, c.x, c.y).mapRect(frame)
    }
}
