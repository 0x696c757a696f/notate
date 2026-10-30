package com.alexdremov.notate.util

import android.graphics.Path
import android.graphics.PointF
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.*

/**
 * Recognizes geometric shapes from freehand stroke input.
 *
 * Uses a "Competitive Error" scoring algorithm that compares how well the input
 * fits various geometric shapes (line, circle, ellipse, polygons, open polylines).
 *
 * ## Algorithm Overview
 * 1. **Line Detection**: Checks if path linearity ratio > 92%; near-axis/45° lines are snapped
 * 2. **Open Polyline**: Open strokes made of 2-4 straight runs (e.g. an "L" or a zig-zag) are straightened
 * 3. **Closure Check**: Determines if shape is closed (start/end distance < 30% of path length)
 * 4. **Round Metrics**: Circle (mean absolute deviation from average radius) or, for elongated input,
 *    a principal-axis ellipse fit
 * 5. **Polygon Metrics**: Uses Douglas-Peucker simplification to find vertices
 * 6. **Competitive Scoring**: Shape with lowest error score wins. Polygons are regularized only when the
 *    drawing is close to regular; otherwise the drawn corners are kept.
 *
 * ## Usage
 * ```kotlin
 * val result = ShapeRecognizer.recognize(touchPoints)
 * when (result?.shape) {
 *     RecognizedShape.CIRCLE -> // Handle circle
 *     RecognizedShape.SQUARE -> // Handle square
 *     // ...
 * }
 * ```
 *
 * @see RecognizedShape for supported shape types
 * @see RecognitionResult for the output structure
 */
object ShapeRecognizer {
    private const val EPSILON = 1e-4f
    private const val ROUND_STEPS = 60

    enum class RecognizedShape {
        NONE,
        LINE,
        CIRCLE,
        TRIANGLE,
        SQUARE,
        PENTAGON,
        HEXAGON,
        ELLIPSE,
        POLYGON, // Irregular closed polygon, drawn corners kept
        POLYLINE, // Open chain of straight segments
    }

    data class RecognitionResult(
        val shape: RecognizedShape,
        val segments: List<List<PointF>>, // Segments for separate strokes
    ) {
        /** Combined path for preview. */
        val path: Path by lazy {
            Path().apply {
                for (segment in segments) {
                    if (segment.isEmpty()) continue
                    moveTo(segment[0].x, segment[0].y)
                    for (i in 1 until segment.size) lineTo(segment[i].x, segment[i].y)
                }
            }
        }
    }

    fun recognize(points: List<TouchPoint>): RecognitionResult? = recognizePoints(points.map { PointF(it.x, it.y) })

    fun recognizePoints(simplePoints: List<PointF>): RecognitionResult? {
        if (simplePoints.size < 10) {
            Logger.d("ShapeRecognizer", "Recognition aborted: too few points (${simplePoints.size})")
            return null
        }

        val start = simplePoints.first()
        val end = simplePoints.last()

        // --- Line Detection ---
        val distStartEnd = hypot(end.x - start.x, end.y - start.y)
        var pathLength = 0f
        for (i in 0 until simplePoints.size - 1) {
            pathLength += hypot(simplePoints[i + 1].x - simplePoints[i].x, simplePoints[i + 1].y - simplePoints[i].y)
        }

        val linearityRatio = if (pathLength > 0) distStartEnd / pathLength else 0f
        Logger.d("ShapeRecognizer", "Linearity check: ratio=$linearityRatio (threshold=0.92), pathLength=$pathLength")

        // Linearity check: If straight distance is > 92% of path length
        if (distStartEnd > pathLength * 0.92) {
            Logger.d("ShapeRecognizer", "Recognized as LINE")
            val snappedEnd = ShapeGeometry.snapAngle(start, end)
            return RecognitionResult(RecognizedShape.LINE, listOf(listOf(start, snappedEnd)))
        }

        // --- Closed Shape Check ---
        if (pathLength < 20f) {
            Logger.d("ShapeRecognizer", "Recognition aborted: path too short ($pathLength)")
            return null
        }

        val closureRatio = if (pathLength > 0) distStartEnd / pathLength else 1.0f
        Logger.d("ShapeRecognizer", "Closure check: ratio=$closureRatio (threshold=0.30)")

        if (distStartEnd > pathLength * 0.30) {
            val polyline = recognizePolyline(simplePoints, pathLength)
            if (polyline != null) return polyline
            Logger.d("ShapeRecognizer", "Open shape detected, ratio $closureRatio > 0.30. Not a line or closed shape.")
            return null
        }

        // Close the loop for calculation
        val closedPoints = simplePoints.toMutableList()
        val closeX = (start.x + end.x) / 2
        val closeY = (start.y + end.y) / 2
        closedPoints[0] = PointF(closeX, closeY)
        closedPoints[closedPoints.size - 1] = PointF(closeX, closeY)

        // --- Metrics Calculation ---

        // 1. Circle Metrics
        var cx = 0f
        var cy = 0f
        for (p in closedPoints) {
            cx += p.x
            cy += p.y
        }
        cx /= closedPoints.size
        cy /= closedPoints.size

        val radii = closedPoints.map { hypot(it.x - cx, it.y - cy) }
        val avgRadius = radii.average().toFloat()

        // Mean Absolute Deviation (Circle Error)
        val circleError = radii.map { abs(it - avgRadius) }.average().toFloat()

        // 1b. Ellipse Metrics: elongated round shapes are ellipses, not circles
        val ellipse = fitEllipse(closedPoints)
        val isElongated = ellipse != null && ellipse.b < ellipse.a * 0.85f
        val roundError = if (isElongated) ellipse!!.error else circleError
        val roundScale = if (isElongated) (ellipse!!.a + ellipse.b) / 2f else avgRadius

        fun roundResult(): RecognitionResult =
            if (isElongated) {
                createEllipseResult(ellipse!!)
            } else {
                createCircleResult(cx, cy, avgRadius)
            }

        // 2. Polygon Metrics (Douglas-Peucker)
        // Use 18% of radius as tolerance (tunable)
        val tolerance = avgRadius * 0.18f
        val simplified = douglasPeucker(closedPoints, tolerance)

        // Calculate true vertex count (remove duplicate end point)
        var vertexCount = simplified.size
        if (simplified.size > 1 &&
            hypot(simplified.first().x - simplified.last().x, simplified.first().y - simplified.last().y) < EPSILON
        ) {
            vertexCount--
        }

        // Polygon Error: Average distance from original points to simplified edges
        val polyError = calculatePolygonError(closedPoints, simplified)

        Logger.d(
            "ShapeRecognizer",
            "Scores - VertexCount: $vertexCount, CircleErr: $circleError, EllipseErr: ${ellipse?.error}, PolyErr: $polyError, AvgRad: $avgRadius",
        )

        // --- Decision Logic ---

        val polyScore = polyError
        val roundScore = roundError

        if (vertexCount == 3) {
            // It's a triangle candidate.
            if (polyScore < roundScore) {
                Logger.d("ShapeRecognizer", "Recognized as TRIANGLE (Score: $polyScore vs $roundScore)")
                return createPolygonResult(RecognizedShape.TRIANGLE, ShapeBeautifier.triangle(uniqueVertices(simplified)))
            }
        } else if (vertexCount == 4) {
            // Quadrilateral vs Round.
            if (polyScore < roundScore) {
                return if (isNearlyRectangular(simplified)) {
                    Logger.d("ShapeRecognizer", "Recognized as SQUARE (Score: $polyScore vs $roundScore)")
                    createRectangleResult(simplified)
                } else {
                    Logger.d("ShapeRecognizer", "Recognized as quadrilateral POLYGON (Score: $polyScore vs $roundScore)")
                    createPolygonResult(RecognizedShape.POLYGON, ShapeBeautifier.quadrilateral(uniqueVertices(simplified)))
                }
            } else {
                Logger.d("ShapeRecognizer", "Recognized as round (Override 4-vertex) (Score: $roundScore vs $polyScore)")
                return roundResult()
            }
        } else if (vertexCount == 5 || vertexCount == 6) {
            // Pentagon/Hexagon vs Round
            if (polyScore < roundScore) {
                val type = if (vertexCount == 5) RecognizedShape.PENTAGON else RecognizedShape.HEXAGON
                return if (isNearlyRegular(simplified)) {
                    Logger.d("ShapeRecognizer", "Recognized as $type (Score: $polyScore vs $roundScore)")
                    createRegularPolygonResult(type, simplified)
                } else {
                    Logger.d("ShapeRecognizer", "Recognized as irregular POLYGON ($vertexCount)")
                    createPolygonResult(RecognizedShape.POLYGON, ShapeBeautifier.level(uniqueVertices(simplified)))
                }
            } else {
                Logger.d("ShapeRecognizer", "Recognized as round (Override $vertexCount-vertex) (Score: $roundScore vs $polyScore)")
                return roundResult()
            }
        } else {
            // Many vertices -> Likely a Circle/Ellipse or complex shape
            if (roundError < roundScale * 0.20) {
                Logger.d("ShapeRecognizer", "Recognized as round (Vertices: $vertexCount) (Err: $roundError)")
                return roundResult()
            }
        }

        Logger.d("ShapeRecognizer", "Recognition failed: No matching shape found for vertexCount=$vertexCount")
        return null
    }

    // --- Open polylines ---

    /**
     * Straightens an open stroke made of 2-4 clearly straight runs (an "L", a "V", a zig-zag, an axis
     * drawn in one stroke). Curved strokes are rejected by the fit-error check and stay freehand.
     */
    private fun recognizePolyline(
        points: List<PointF>,
        pathLength: Float,
    ): RecognitionResult? {
        val simplified = douglasPeucker(points, max(pathLength * 0.04f, 6f))
        val segmentCount = simplified.size - 1
        if (segmentCount !in 2..4) return null

        for (i in 0 until segmentCount) {
            val a = simplified[i]
            val b = simplified[i + 1]
            if (hypot(b.x - a.x, b.y - a.y) < pathLength * 0.08f) return null
        }

        var totalError = 0f
        for (p in points) {
            var minD = Float.MAX_VALUE
            for (i in 0 until segmentCount) {
                minD = min(minD, pointSegmentDist(p, simplified[i], simplified[i + 1]))
            }
            totalError += minD
        }
        val meanError = totalError / points.size
        Logger.d("ShapeRecognizer", "Polyline check: segments=$segmentCount, meanErr=$meanError")
        if (meanError > pathLength * 0.012f) return null

        val segments = (0 until segmentCount).map { listOf(simplified[it], simplified[it + 1]) }
        Logger.d("ShapeRecognizer", "Recognized as POLYLINE ($segmentCount segments)")
        return RecognitionResult(RecognizedShape.POLYLINE, segments)
    }

    // --- Ellipse fitting ---

    private data class EllipseFit(
        val cx: Float,
        val cy: Float,
        val a: Float, // semi-major
        val b: Float, // semi-minor
        val theta: Float, // rotation of the major axis
        val error: Float, // mean absolute radial deviation, in world units
    )

    /**
     * Fits an ellipse along the principal axes of the point cloud, sizing it from the extents along each
     * axis. Good enough for hand-drawn ovals, and far cheaper than an algebraic least-squares fit.
     */
    private fun fitEllipse(points: List<PointF>): EllipseFit? {
        val n = points.size
        if (n < 5) return null
        var mx = 0.0
        var my = 0.0
        for (p in points) {
            mx += p.x
            my += p.y
        }
        mx /= n
        my /= n
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (p in points) {
            val dx = p.x - mx
            val dy = p.y - my
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        var theta = 0.5 * atan2(2 * sxy, sxx - syy)

        // Snap near-axis ellipses to the axis
        val snap = 10.0 * PI / 180.0
        val quarter = PI / 2
        val nearest = (theta / quarter).roundToInt() * quarter
        if (abs(theta - nearest) < snap) theta = nearest

        val c = cos(theta)
        val s = sin(theta)
        var minU = Double.MAX_VALUE
        var maxU = -Double.MAX_VALUE
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        for (p in points) {
            val dx = p.x - mx
            val dy = p.y - my
            val u = dx * c + dy * s
            val v = -dx * s + dy * c
            minU = min(minU, u)
            maxU = max(maxU, u)
            minV = min(minV, v)
            maxV = max(maxV, v)
        }
        var a = (maxU - minU) / 2
        var b = (maxV - minV) / 2
        if (a < EPSILON || b < EPSILON) return null

        // Re-center on the box midpoint (the centroid is biased by uneven sampling)
        val du = (maxU + minU) / 2
        val dv = (maxV + minV) / 2
        val cx = mx + du * c - dv * s
        val cy = my + du * s + dv * c

        if (b > a) {
            val t = a
            a = b
            b = t
            theta += quarter
        }

        val ct = cos(theta)
        val st = sin(theta)
        var err = 0.0
        for (p in points) {
            val dx = p.x - cx
            val dy = p.y - cy
            val u = dx * ct + dy * st
            val v = -dx * st + dy * ct
            val r = sqrt((u / a) * (u / a) + (v / b) * (v / b))
            err += abs(r - 1.0)
        }
        err = err / n * (a + b) / 2
        return EllipseFit(cx.toFloat(), cy.toFloat(), a.toFloat(), b.toFloat(), theta.toFloat(), err.toFloat())
    }

    private fun createEllipseResult(fit: EllipseFit): RecognitionResult {
        val c = cos(fit.theta)
        val s = sin(fit.theta)
        val points =
            (0..ROUND_STEPS).map { i ->
                val t = 2.0 * PI * i / ROUND_STEPS
                val u = fit.a * cos(t)
                val v = fit.b * sin(t)
                PointF((fit.cx + u * c - v * s).toFloat(), (fit.cy + u * s + v * c).toFloat())
            }
        return RecognitionResult(RecognizedShape.ELLIPSE, listOf(points))
    }

    // --- Regularity checks ---

    private fun uniqueVertices(points: List<PointF>): List<PointF> =
        if (points.size > 1 && hypot(points.first().x - points.last().x, points.first().y - points.last().y) < EPSILON) {
            points.dropLast(1)
        } else {
            points
        }

    /** Interior angles of a closed polygon, in degrees. */
    private fun cornerAngles(vertices: List<PointF>): List<Double> =
        vertices.indices.map { i ->
            val prev = vertices[(i - 1 + vertices.size) % vertices.size]
            val cur = vertices[i]
            val next = vertices[(i + 1) % vertices.size]
            val a1 = atan2((prev.y - cur.y).toDouble(), (prev.x - cur.x).toDouble())
            val a2 = atan2((next.y - cur.y).toDouble(), (next.x - cur.x).toDouble())
            var d = abs(a1 - a2)
            if (d > PI) d = 2 * PI - d
            d * 180.0 / PI
        }

    private fun isNearlyRectangular(points: List<PointF>): Boolean {
        val v = uniqueVertices(points)
        if (v.size != 4) return false
        return cornerAngles(v).all { abs(it - 90.0) < 20.0 }
    }

    /** True when side lengths and corner angles are close enough to regular to snap to a regular polygon. */
    private fun isNearlyRegular(points: List<PointF>): Boolean {
        val v = uniqueVertices(points)
        if (v.size < 3) return false
        val sides = v.indices.map { i -> hypot(v[(i + 1) % v.size].x - v[i].x, v[(i + 1) % v.size].y - v[i].y) }
        val mean = sides.average()
        if (mean <= 0) return false
        val cv = sqrt(sides.map { (it - mean) * (it - mean) }.average()) / mean
        val ideal = 180.0 * (v.size - 2) / v.size
        val anglesOk = cornerAngles(v).all { abs(it - ideal) < 25.0 }
        return cv < 0.25 && anglesOk
    }

    private fun createRegularPolygonResult(
        type: RecognizedShape,
        points: List<PointF>,
    ): RecognitionResult {
        // Remove duplicate end point
        val uniquePoints = uniqueVertices(points)

        val sides = if (type == RecognizedShape.PENTAGON) 5 else 6

        // Calculate Centroid
        var cx = 0f
        var cy = 0f
        for (p in uniquePoints) {
            cx += p.x
            cy += p.y
        }
        cx /= uniquePoints.size
        cy /= uniquePoints.size

        // Calculate average radius
        var avgRadius = 0f
        for (p in uniquePoints) {
            avgRadius += hypot(p.x - cx, p.y - cy)
        }
        avgRadius /= uniquePoints.size

        // Determine rotation based on the first vertex
        val firstPoint = uniquePoints[0]
        val startAngle = atan2(firstPoint.y - cy, firstPoint.x - cx)

        val vertices = mutableListOf<PointF>()
        for (i in 0 until sides) {
            val angle = startAngle + i * (2 * PI / sides)
            val px = (cx + avgRadius * cos(angle)).toFloat()
            val py = (cy + avgRadius * sin(angle)).toFloat()
            vertices.add(PointF(px, py))
        }

        return RecognitionResult(type, closedSegments(ShapeBeautifier.level(vertices)))
    }

    private fun createRectangleResult(points: List<PointF>): RecognitionResult {
        val uniquePoints = uniqueVertices(points)

        if (uniquePoints.size != 4) return createPolygonResult(RecognizedShape.SQUARE, points)

        // Centroid
        var cx = 0f
        var cy = 0f
        for (p in uniquePoints) {
            cx += p.x
            cy += p.y
        }
        cx /= 4f
        cy /= 4f

        // Robust Orientation Calculation: Quadruple Angle Vector Sum
        // Maps 0, 90, 180, 270 degrees to the same vector direction.
        var sumCos4 = 0f
        var sumSin4 = 0f

        for (i in 0 until 4) {
            val p1 = uniquePoints[i]
            val p2 = uniquePoints[(i + 1) % 4]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val length = hypot(dx, dy)
            val angle = atan2(dy, dx)

            // Weight by length so longer sides dominate the decision
            sumCos4 += length * cos(4 * angle)
            sumSin4 += length * sin(4 * angle)
        }

        val avg4Angle = atan2(sumSin4, sumCos4)
        var baseAngle = avg4Angle / 4f
        // baseAngle is now in range (-PI/4, PI/4], i.e., (-45, 45] degrees

        // Snap to axis (0 degrees) if within the beautifier's leveling tolerance
        val snapThreshold = ShapeBeautifier.LEVEL_TOLERANCE_DEG * PI / 180.0
        if (abs(baseAngle) < snapThreshold) {
            baseAngle = 0f
        }

        val cosA = cos(baseAngle)
        val sinA = sin(baseAngle)

        // Dimensions: Average width and height relative to the baseAngle
        // We project the sides onto the baseAngle vector (Width) and perpendicular (Height)
        var sumW = 0f
        var sumH = 0f
        for (i in 0 until 4) {
            val p1 = uniquePoints[i]
            val p2 = uniquePoints[(i + 1) % 4]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y

            // Rotate the side vector back by baseAngle to align with axes
            val rx = dx * cosA + dy * sinA
            val ry = -dx * sinA + dy * cosA

            sumW += abs(rx)
            sumH += abs(ry)
        }

        // Each dimension is contributed to by 2 sides (approx)
        var avgW = sumW / 2f
        var avgH = sumH / 2f
        // Nearly equal sides: a square
        if (abs(avgW - avgH) / max(max(avgW, avgH), EPSILON) < ShapeBeautifier.SIDE_TOLERANCE) {
            avgW = (avgW + avgH) / 2f
            avgH = avgW
        }

        // Construct 4 vertices for a rectangle centered at cx, cy
        val vertices = mutableListOf<PointF>()
        val localOffsets =
            arrayOf(
                PointF(-avgW / 2, -avgH / 2),
                PointF(avgW / 2, -avgH / 2),
                PointF(avgW / 2, avgH / 2),
                PointF(-avgW / 2, avgH / 2),
            )

        for (lp in localOffsets) {
            val rx = lp.x * cosA - lp.y * sinA
            val ry = lp.x * sinA + lp.y * cosA
            vertices.add(PointF(cx + rx, cy + ry))
        }

        return RecognitionResult(RecognizedShape.SQUARE, closedSegments(vertices))
    }

    private fun createPolygonResult(
        type: RecognizedShape,
        points: List<PointF>,
    ): RecognitionResult {
        // Ensure unique points (Douglas Peucker sometimes leaves dupe start/end)
        return RecognitionResult(type, closedSegments(uniqueVertices(points)))
    }

    private fun closedSegments(vertices: List<PointF>): List<List<PointF>> =
        vertices.indices.map { i -> listOf(vertices[i], vertices[(i + 1) % vertices.size]) }

    private fun createCircleResult(
        cx: Float,
        cy: Float,
        radius: Float,
    ): RecognitionResult {
        val circlePoints = mutableListOf<PointF>()
        for (i in 0..ROUND_STEPS) {
            val angle = 2.0 * PI * i / ROUND_STEPS
            circlePoints.add(
                PointF((cx + radius * cos(angle)).toFloat(), (cy + radius * sin(angle)).toFloat()),
            )
        }
        // Circle is a single continuous segment
        return RecognitionResult(RecognizedShape.CIRCLE, listOf(circlePoints))
    }

    private fun calculatePolygonError(
        original: List<PointF>,
        simplified: List<PointF>,
    ): Float {
        if (simplified.size < 2) return Float.MAX_VALUE
        var totalError = 0f

        for (p in original) {
            var minD = Float.MAX_VALUE
            for (i in 0 until simplified.size - 1) {
                val d = pointSegmentDist(p, simplified[i], simplified[i + 1])
                if (d < minD) minD = d
            }
            // Check closing segment if implies closed
            if (hypot(simplified.first().x - simplified.last().x, simplified.first().y - simplified.last().y) < EPSILON) {
                // already handled by loop
            } else {
                val d = pointSegmentDist(p, simplified.last(), simplified.first())
                if (d < minD) minD = d
            }
            totalError += minD
        }
        return totalError / original.size
    }

    private fun pointSegmentDist(
        p: PointF,
        a: PointF,
        b: PointF,
    ): Float {
        val l2 = (a.x - b.x).pow(2) + (a.y - b.y).pow(2)
        if (l2 == 0f) return hypot(p.x - a.x, p.y - a.y)

        var t = ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / l2
        t = t.coerceIn(0f, 1f)

        val projX = a.x + t * (b.x - a.x)
        val projY = a.y + t * (b.y - a.y)
        return hypot(p.x - projX, p.y - projY)
    }

    // Simple Ramer-Douglas-Peucker implementation
    private fun douglasPeucker(
        points: List<PointF>,
        epsilon: Float,
    ): List<PointF> {
        if (points.size < 3) return points

        var dmax = 0f
        var index = 0
        val end = points.size - 1

        for (i in 1 until end) {
            val d = pointLineDist(points[i], points[0], points[end])
            if (d > dmax) {
                index = i
                dmax = d
            }
        }

        if (dmax > epsilon) {
            val res1 = douglasPeucker(points.subList(0, index + 1), epsilon)
            val res2 = douglasPeucker(points.subList(index, points.size), epsilon)
            return res1.dropLast(1) + res2
        } else {
            return listOf(points[0], points[end])
        }
    }

    private fun pointLineDist(
        p: PointF,
        a: PointF,
        b: PointF,
    ): Float {
        val num = abs((b.y - a.y) * p.x - (b.x - a.x) * p.y + b.x * a.y - b.y * a.x)
        val den = hypot(b.y - a.y, b.x - a.x)
        if (den == 0f) {
            return hypot(p.x - a.x, p.y - a.y)
        }
        return num / den
    }
}
