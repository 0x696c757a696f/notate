package com.alexdremov.notate.webdoc

import android.graphics.PointF
import com.alexdremov.notate.model.ShapeType
import com.alexdremov.notate.util.ShapeGeometry
import com.alexdremov.notate.util.ShapeRecognizer

/**
 * Shape tools for web-document ink. Ink lives in CSS px, but [ShapeGeometry] and [ShapeRecognizer]
 * use size thresholds tuned for screen pixels, so geometry is built at the current zoom ([scale] =
 * device px per CSS px) and mapped back. That way a shape behaves the same as on a note page.
 */
object WebInkShapes {
    /** Geometry of a drag shape from [start] to [end] (CSS px). Empty for a tap. */
    fun dragShape(
        type: ShapeType,
        polygonSides: Int,
        start: PointF,
        end: PointF,
        scale: Float,
    ): List<ShapeGeometry.Segment> {
        val s = scale.coerceAtLeast(0.01f)
        return ShapeGeometry
            .build(type, PointF(start.x * s, start.y * s), PointF(end.x * s, end.y * s), polygonSides)
            .map { seg -> seg.copy(points = seg.points.map { PointF(it.x / s, it.y / s) }) }
    }

    /** The perfected shape for a freehand stroke ([points] interleaved CSS px), or null if none is recognized. */
    fun recognize(
        points: FloatArray,
        scale: Float,
    ): List<ShapeGeometry.Segment>? {
        val s = scale.coerceAtLeast(0.01f)
        val screen = ArrayList<PointF>(points.size / 2)
        var i = 0
        while (i + 1 < points.size) {
            screen.add(PointF(points[i] * s, points[i + 1] * s))
            i += 2
        }
        val result = ShapeRecognizer.recognizePoints(screen) ?: return null
        if (result.shape == ShapeRecognizer.RecognizedShape.NONE) return null
        return result.segments.map { seg -> ShapeGeometry.Segment(seg.map { PointF(it.x / s, it.y / s) }) }
    }

    fun toFloatArray(points: List<PointF>): FloatArray {
        val out = FloatArray(points.size * 2)
        for ((i, p) in points.withIndex()) {
            out[i * 2] = p.x
            out[i * 2 + 1] = p.y
        }
        return out
    }
}
