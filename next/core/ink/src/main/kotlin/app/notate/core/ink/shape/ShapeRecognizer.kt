package app.notate.core.ink.shape

import app.notate.core.geometry.Vec2
import app.notate.core.model.ShapeKind
import kotlin.math.*

/**
 * Recognizes geometric shapes from freehand stroke input (ported from the legacy app).
 *
 * Uses a "Competitive Error" scoring algorithm that compares how well the input
 * fits various geometric shapes (line, circle, ellipse, polygons, open polylines).
 *
 * 1. Line: path linearity ratio > 92%; near-axis/15° lines are snapped.
 * 2. Open polyline: open strokes made of 2-4 straight runs are straightened.
 * 3. Closure: start/end distance < 30% of path length.
 * 4. Closed strokes: circle/ellipse (best-fit centre), triangle, 4-corner and regular 5/6-gon candidates are scored on one
 *    scale (mean distance to the outline / radius). Corners are re-derived from least-squares lines through each side, so
 *    rounded or wobbly corners don't add vertices, and corner rounding is forgiven. Lowest error wins; no match = freehand.
 *
 * Pure function of the input, any thread. Units are whatever the caller uses (page pt); the thresholds that
 * contain absolute lengths (20 and 6) were tuned for legacy pixels and are intentionally unchanged.
 */
object ShapeRecognizer {
    private const val EPSILON = 1e-4f
    private const val ROUND_STEPS = 60

    /** Legacy classification, still used internally to pick the output [ShapeKind]. */
    private enum class RecognizedShape { LINE, CIRCLE, TRIANGLE, SQUARE, PENTAGON, HEXAGON, ELLIPSE, POLYGON, POLYLINE }

    /**
     * Recognition result. [params] follows the layout of `Shape.params` for [kind] (page coordinates);
     * [score] is 0..1, higher = closer fit.
     */
    class Recognition(val kind: ShapeKind, val params: FloatArray, val score: Float)

    /** [points] = interleaved x,y. Returns null when nothing matches (the stroke stays freehand). */
    fun recognize(points: FloatArray): Recognition? {
        val list = ArrayList<Vec2>(points.size / 2)
        var i = 0
        while (i + 1 < points.size) {
            list.add(Vec2(points[i], points[i + 1]))
            i += 2
        }
        return recognizePoints(list)
    }

    private fun recognizePoints(simplePoints: List<Vec2>): Recognition? {
        if (simplePoints.size < 10) return null

        val start = simplePoints.first()
        val end = simplePoints.last()

        // --- Line Detection ---
        val distStartEnd = hypot(end.x - start.x, end.y - start.y)
        var pathLength = 0f
        for (i in 0 until simplePoints.size - 1) {
            pathLength += hypot(simplePoints[i + 1].x - simplePoints[i].x, simplePoints[i + 1].y - simplePoints[i].y)
        }

        // Linearity check: If straight distance is > 92% of path length
        if (distStartEnd > pathLength * 0.92) {
            val snappedEnd = ShapeGeometry.snapAngle(start, end)
            val score = (1f - (pathLength - distStartEnd) / max(pathLength, EPSILON) * 6f).coerceIn(0f, 1f)
            return Recognition(ShapeKind.LINE, floatArrayOf(start.x, start.y, snappedEnd.x, snappedEnd.y), score)
        }

        // --- Closed Shape Check ---
        if (pathLength < 20f) return null

        if (distStartEnd > pathLength * 0.30) return recognizePolyline(simplePoints, pathLength)

        // Close the loop for calculation
        val closedPoints = simplePoints.toMutableList()
        val closeX = (start.x + end.x) / 2
        val closeY = (start.y + end.y) / 2
        closedPoints[0] = Vec2(closeX, closeY)
        closedPoints[closedPoints.size - 1] = Vec2(closeX, closeY)

        // --- Metrics Calculation ---
        // Every candidate is scored as (mean distance from the stroke to the ideal shape's outline) / avgRadius,
        // so round, rectangular and triangular fits compete on the same scale. The lowest error wins and the
        // output is always the *regular* version (circle, upright rectangle, clean triangle), never a wobbly polygon.

        var cx = 0f
        var cy = 0f
        for (p in closedPoints) {
            cx += p.x
            cy += p.y
        }
        cx /= closedPoints.size
        cy /= closedPoints.size

        val radii = closedPoints.map { hypot(it.x - cx, it.y - cy) }
        var avgRadius = radii.average().toFloat()
        if (avgRadius < EPSILON) return null
        var circleError = radii.map { abs(it - avgRadius) }.average().toFloat()
        // The centroid is biased when the loop overshoots or is sampled unevenly: try the algebraic best-fit centre too.
        fitCircleCentre(closedPoints)?.let { (kx, ky) ->
            val kr = closedPoints.map { hypot(it.x - kx, it.y - ky) }
            val kAvg = kr.average().toFloat()
            val kErr = kr.map { abs(it - kAvg) }.average().toFloat()
            if (kErr < circleError) {
                circleError = kErr
                cx = kx
                cy = ky
                avgRadius = kAvg
            }
        }

        val ellipse = fitEllipse(closedPoints)
        val isElongated = ellipse != null && ellipse.b < ellipse.a * 0.85f
        val roundError = (if (isElongated) ellipse!!.error else circleError) / avgRadius


        // Polygon candidates: Douglas-Peucker at growing tolerance, so rounded corners and wobble don't add vertices.
        var triangle: List<Vec2>? = null
        var quad: List<Vec2>? = null
        var regular: Recognition? = null
        var regularError = Float.MAX_VALUE
        for (k in DP_TOLERANCES) {
            val simplified = douglasPeucker(closedPoints, avgRadius * k)
            val verts = uniqueVertices(simplified)
            val err = calculatePolygonError(closedPoints, simplified) / avgRadius
            when (verts.size) {
                3 -> if (triangle == null) triangle = refineCorners(closedPoints, verts, avgRadius) ?: verts
                4 -> if (quad == null) quad = refineCorners(closedPoints, verts, avgRadius) ?: verts
                5, 6 -> if (regular == null && isNearlyRegular(verts)) {
                    val fit = fitRegularPolygon(closedPoints, cx, cy, avgRadius, verts.size)
                    regular = fit.first
                    regularError = fit.second / avgRadius
                }
            }
        }

        data class Candidate(val error: Float, val build: () -> Recognition)
        val candidates = ArrayList<Candidate>()
        candidates.add(Candidate(roundError) { if (isElongated) createEllipseResult(ellipse!!) else createCircleResult(cx, cy, avgRadius) })
        triangle?.let { t ->
            val tri = ShapeBeautifier.triangle(t)
            candidates.add(Candidate(polygonFitError(closedPoints, tri) / avgRadius) { createPolygonResult(RecognizedShape.TRIANGLE, tri) })
        }
        quad?.let { q ->
            // A 4-corner shape with (nearly) right angles is a rectangle, otherwise a parallelogram/trapezoid/quad.
            val err = polygonFitError(closedPoints, q) / avgRadius
            if (isNearlyRectangular(q)) {
                candidates.add(Candidate(err) { createRectangleResult(q) })
            } else {
                candidates.add(Candidate(err) { createPolygonResult(RecognizedShape.POLYGON, ShapeBeautifier.quadrilateral(q)) })
            }
        }
        if (regular != null && regularError < roundError) candidates.add(Candidate(regularError) { regular })

        val best = candidates.minByOrNull { it.error } ?: return null
        if (best.error > ACCEPT_ERROR) return null
        val r = best.build()
        return Recognition(r.kind, r.params, (1f - best.error / ACCEPT_ERROR).coerceIn(0f, 1f))
    }

    private val DP_TOLERANCES = floatArrayOf(0.18f, 0.24f, 0.30f, 0.38f)
    private const val ACCEPT_ERROR = 0.15f

    /**
     * Distance from the stroke to a closed polygon's outline. Hands round corners off, so the middle of each
     * side counts 80% and the full stroke (corners included) only 20%.
     */
    private fun polygonFitError(
        points: List<Vec2>,
        verts: List<Vec2>,
    ): Float {
        val n = verts.size
        var mid = 0.0
        var midN = 0
        var all = 0.0
        for (p in points) {
            var bestD = Float.MAX_VALUE
            var bi = 0
            for (i in 0 until n) {
                val d = pointSegmentDist(p, verts[i], verts[(i + 1) % n])
                if (d < bestD) { bestD = d; bi = i }
            }
            all += bestD
            val a = verts[bi]
            val b = verts[(bi + 1) % n]
            val l2 = (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)
            val t = if (l2 > 0f) ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / l2 else 0.5f
            if (t in 0.25f..0.75f) { mid += bestD; midN++ }
        }
        val full = (all / points.size).toFloat()
        val middle = if (midN > 0) (mid / midN).toFloat() else full
        return 0.8f * middle + 0.2f * full
    }

    /**
     * Re-derives polygon corners by fitting a line through each side's points and intersecting neighbours. This
     * puts a corner where the *sides* meet even when the hand rounded it off. Null if any side is degenerate.
     */
    private fun refineCorners(
        points: List<Vec2>,
        verts: List<Vec2>,
        scale: Float,
    ): List<Vec2>? {
        val n = verts.size
        val sx = DoubleArray(n)
        val sy = DoubleArray(n)
        val cnt = IntArray(n)
        val owner = IntArray(points.size)
        val frac = FloatArray(points.size)
        for ((pi, p) in points.withIndex()) {
            var bestD = Float.MAX_VALUE
            var bi = 0
            for (i in 0 until n) {
                val d = pointSegmentDist(p, verts[i], verts[(i + 1) % n])
                if (d < bestD) { bestD = d; bi = i }
            }
            val a = verts[bi]
            val b = verts[(bi + 1) % n]
            val l2 = (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)
            owner[pi] = bi
            frac[pi] = if (l2 > 0f) ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / l2 else 0.5f
        }
        // Total-least-squares line per side, using only the middle of each side (corners are where the hand rounds off).
        val lx = FloatArray(n)
        val ly = FloatArray(n)
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        for (i in 0 until n) {
            var mx = 0.0
            var my = 0.0
            var c = 0
            for (pi in points.indices) if (owner[pi] == i && frac[pi] in 0.2f..0.8f) { mx += points[pi].x; my += points[pi].y; c++ }
            if (c < 3) return null
            mx /= c; my /= c
            var xx = 0.0
            var yy = 0.0
            var xy = 0.0
            for (pi in points.indices) if (owner[pi] == i && frac[pi] in 0.2f..0.8f) {
                val ex = points[pi].x - mx
                val ey = points[pi].y - my
                xx += ex * ex; yy += ey * ey; xy += ex * ey
            }
            val th = 0.5 * atan2(2 * xy, xx - yy)
            lx[i] = mx.toFloat(); ly[i] = my.toFloat()
            dx[i] = cos(th).toFloat(); dy[i] = sin(th).toFloat()
        }
        val out = ArrayList<Vec2>(n)
        for (i in 0 until n) {
            val j = (i + n - 1) % n // side j ends at vertex i, side i starts there
            val cr = dx[j] * dy[i] - dy[j] * dx[i]
            if (abs(cr) < 0.25f) return null
            val t = ((lx[i] - lx[j]) * dy[i] - (ly[i] - ly[j]) * dx[i]) / cr
            val v = Vec2(lx[j] + dx[j] * t, ly[j] + dy[j] * t)
            if (hypot(v.x - verts[i].x, v.y - verts[i].y) > scale * 0.8f) return null
            out.add(v)
        }
        return out
    }

    /** Regular n-gon centred on (cx, cy): sweeps the rotation and keeps the one closest to the stroke. */
    private fun fitRegularPolygon(
        points: List<Vec2>,
        cx: Float,
        cy: Float,
        avgRadius: Float,
        n: Int,
    ): Pair<Recognition, Float> {
        val r = avgRadius * 2f / (1f + cos(PI / n).toFloat())
        var bestErr = Float.MAX_VALUE
        var bestVerts: List<Vec2> = emptyList()
        val steps = 72
        for (k in 0 until steps) {
            val rot = 2 * PI / n * k / steps
            val v = (0 until n).map { Vec2((cx + r * cos(rot + 2 * PI * it / n)).toFloat(), (cy + r * sin(rot + 2 * PI * it / n)).toFloat()) }
            val e = polygonFitError(points, v)
            if (e < bestErr) { bestErr = e; bestVerts = v }
        }
        return Recognition(ShapeKind.POLYGON, flat(ShapeBeautifier.level(bestVerts)), 1f) to bestErr
    }

    /** Kasa algebraic circle fit (centre only). Null for degenerate input. */
    private fun fitCircleCentre(points: List<Vec2>): Pair<Float, Float>? {
        val n = points.size
        var mx = 0.0
        var my = 0.0
        for (p in points) { mx += p.x; my += p.y }
        mx /= n; my /= n
        var suu = 0.0; var svv = 0.0; var suv = 0.0; var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
        for (p in points) {
            val u = p.x - mx
            val v = p.y - my
            suu += u * u; svv += v * v; suv += u * v
            suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u
        }
        val det = suu * svv - suv * suv
        if (abs(det) < 1e-9) return null
        val b1 = 0.5 * (suuu + suvv)
        val b2 = 0.5 * (svvv + svuu)
        val uc = (b1 * svv - b2 * suv) / det
        val vc = (b2 * suu - b1 * suv) / det
        return (mx + uc).toFloat() to (my + vc).toFloat()
    }

    private fun flatToVerts(f: FloatArray): List<Vec2> = (0 until f.size / 2).map { Vec2(f[2 * it], f[2 * it + 1]) }

    // --- Open polylines ---

    /**
     * Straightens an open stroke made of 2-4 clearly straight runs (an "L", a "V", a zig-zag, an axis
     * drawn in one stroke). Curved strokes are rejected by the fit-error check and stay freehand.
     */
    private fun recognizePolyline(
        points: List<Vec2>,
        pathLength: Float,
    ): Recognition? {
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
        if (meanError > pathLength * 0.012f) return null

        val score = (1f - meanError / (pathLength * 0.012f)).coerceIn(0f, 1f)
        return Recognition(ShapeKind.POLYLINE, flat(simplified), score)
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
    private fun fitEllipse(points: List<Vec2>): EllipseFit? {
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

    private fun createEllipseResult(fit: EllipseFit): Recognition =
        Recognition(ShapeKind.ELLIPSE, floatArrayOf(fit.cx, fit.cy, fit.a, fit.b, Math.toDegrees(fit.theta.toDouble()).toFloat()), 1f)

    // --- Regularity checks ---

    private fun uniqueVertices(points: List<Vec2>): List<Vec2> =
        if (points.size > 1 && hypot(points.first().x - points.last().x, points.first().y - points.last().y) < EPSILON) {
            points.dropLast(1)
        } else {
            points
        }

    /** Interior angles of a closed polygon, in degrees. */
    private fun cornerAngles(vertices: List<Vec2>): List<Double> =
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

    private fun isNearlyRectangular(points: List<Vec2>): Boolean {
        val v = uniqueVertices(points)
        if (v.size != 4) return false
        return cornerAngles(v).all { abs(it - 90.0) < 20.0 }
    }

    /** True when side lengths and corner angles are close enough to regular to snap to a regular polygon. */
    private fun isNearlyRegular(points: List<Vec2>): Boolean {
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
        points: List<Vec2>,
    ): Recognition {
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

        val vertices = mutableListOf<Vec2>()
        for (i in 0 until sides) {
            val angle = startAngle + i * (2 * PI / sides)
            val px = (cx + avgRadius * cos(angle)).toFloat()
            val py = (cy + avgRadius * sin(angle)).toFloat()
            vertices.add(Vec2(px, py))
        }

        return Recognition(ShapeKind.POLYGON, flat(ShapeBeautifier.level(vertices)), 1f)
    }

    private fun createRectangleResult(points: List<Vec2>): Recognition {
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
        val vertices = mutableListOf<Vec2>()
        val localOffsets =
            arrayOf(
                Vec2(-avgW / 2, -avgH / 2),
                Vec2(avgW / 2, -avgH / 2),
                Vec2(avgW / 2, avgH / 2),
                Vec2(-avgW / 2, avgH / 2),
            )

        for (lp in localOffsets) {
            val rx = lp.x * cosA - lp.y * sinA
            val ry = lp.x * sinA + lp.y * cosA
            vertices.add(Vec2(cx + rx, cy + ry))
        }

        return Recognition(ShapeKind.RECT, flat(vertices), 1f)
    }

    private fun createPolygonResult(
        type: RecognizedShape,
        points: List<Vec2>,
    ): Recognition {
        // Ensure unique points (Douglas Peucker sometimes leaves dupe start/end)
        val kind = when (type) {
            RecognizedShape.TRIANGLE -> ShapeKind.TRIANGLE
            RecognizedShape.SQUARE -> ShapeKind.RECT
            else -> ShapeKind.POLYGON
        }
        return Recognition(kind, flat(uniqueVertices(points)), 1f)
    }

    private fun flat(vertices: List<Vec2>): FloatArray {
        val out = FloatArray(vertices.size * 2)
        for (i in vertices.indices) {
            out[i * 2] = vertices[i].x
            out[i * 2 + 1] = vertices[i].y
        }
        return out
    }

    /** A circle is an ellipse with rx = ry. */
    private fun createCircleResult(cx: Float, cy: Float, radius: Float): Recognition =
        Recognition(ShapeKind.ELLIPSE, floatArrayOf(cx, cy, radius, radius, 0f), 1f)

    private fun calculatePolygonError(
        original: List<Vec2>,
        simplified: List<Vec2>,
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
        p: Vec2,
        a: Vec2,
        b: Vec2,
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
        points: List<Vec2>,
        epsilon: Float,
    ): List<Vec2> {
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
        p: Vec2,
        a: Vec2,
        b: Vec2,
    ): Float {
        val num = abs((b.y - a.y) * p.x - (b.x - a.x) * p.y + b.x * a.y - b.y * a.x)
        val den = hypot(b.y - a.y, b.x - a.x)
        if (den == 0f) {
            return hypot(p.x - a.x, p.y - a.y)
        }
        return num / den
    }
}
