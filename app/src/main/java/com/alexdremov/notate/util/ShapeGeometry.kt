package com.alexdremov.notate.util

import android.graphics.PointF
import com.alexdremov.notate.model.ShapeType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Builds the geometry of a drag shape (see [ShapeType.isDragShape]) from the pen-down and pen-up points.
 *
 * Output is a list of [Segment]s in world coordinates; each segment becomes one committed stroke, matching
 * how shape perfection in [ShapeRecognizer] commits one stroke per polygon edge. Pure math, no Android
 * graphics objects besides [PointF], so it is cheap enough to rebuild on every pen move for the live preview.
 */
object ShapeGeometry {
    /** One stroke of a shape. [widthScale] lets grid lines be drawn thinner than the axes. */
    data class Segment(
        val points: List<PointF>,
        val widthScale: Float = 1f,
    )

    /** Drags shorter than this (world units) are treated as a tap and produce nothing. */
    const val MIN_DRAG = 8f

    private const val ELLIPSE_STEPS = 72
    private const val ARROW_ANGLE_RAD = 28.0 * PI / 180.0
    private const val GRID_WIDTH_SCALE = 0.4f

    fun build(
        type: ShapeType,
        start: PointF,
        end: PointF,
        polygonSides: Int = 5,
    ): List<Segment> {
        if (!type.isDragShape) return emptyList()
        if (hypot(end.x - start.x, end.y - start.y) < MIN_DRAG) return emptyList()

        val minX = min(start.x, end.x)
        val maxX = max(start.x, end.x)
        val minY = min(start.y, end.y)
        val maxY = max(start.y, end.y)

        return when (type) {
            ShapeType.LINE -> {
                listOf(Segment(listOf(start, snapAngle(start, end))))
            }

            ShapeType.ARROW -> {
                val tip = snapAngle(start, end)
                listOf(Segment(listOf(start, tip))) + arrowHead(tip, start)
            }

            ShapeType.DOUBLE_ARROW -> {
                val tip = snapAngle(start, end)
                listOf(Segment(listOf(start, tip))) + arrowHead(tip, start) + arrowHead(start, tip)
            }

            ShapeType.RECTANGLE -> {
                closedEdges(
                    listOf(PointF(minX, minY), PointF(maxX, minY), PointF(maxX, maxY), PointF(minX, maxY)),
                )
            }

            ShapeType.ELLIPSE -> {
                listOf(Segment(ellipse(minX, minY, maxX, maxY)))
            }

            ShapeType.TRIANGLE -> {
                closedEdges(
                    listOf(PointF((minX + maxX) / 2f, minY), PointF(maxX, maxY), PointF(minX, maxY)),
                )
            }

            ShapeType.RIGHT_TRIANGLE -> {
                // Right angle sits below/above the start point, so dragging toward any corner works.
                closedEdges(listOf(PointF(start.x, start.y), PointF(start.x, end.y), PointF(end.x, end.y)))
            }

            ShapeType.POLYGON -> {
                regularPolygon(start, end, polygonSides.coerceIn(3, 12))
            }

            ShapeType.AXES -> {
                axes2d(minX, minY, maxX, maxY, (minX + maxX) / 2f, (minY + maxY) / 2f, withGrid = false)
            }

            ShapeType.AXES_QUADRANT -> {
                axes2d(minX, minY, maxX, maxY, minX, maxY, withGrid = false)
            }

            ShapeType.GRID -> {
                axes2d(minX, minY, maxX, maxY, (minX + maxX) / 2f, (minY + maxY) / 2f, withGrid = true)
            }

            ShapeType.AXES_3D -> {
                axes3d(minX, minY, maxX, maxY)
            }

            ShapeType.NUMBER_LINE -> {
                numberLine(start, snapAngle(start, end))
            }

            ShapeType.NONE, ShapeType.AUTO -> {
                emptyList()
            }
        }
    }

    /**
     * Rotates [end] around [start] onto the nearest multiple of [stepDeg] degrees when it is within
     * [toleranceDeg], so near-horizontal/vertical/45° lines come out exact.
     */
    fun snapAngle(
        start: PointF,
        end: PointF,
        stepDeg: Double = 15.0,
        toleranceDeg: Double = 4.0,
    ): PointF {
        val dx = (end.x - start.x).toDouble()
        val dy = (end.y - start.y).toDouble()
        val len = hypot(dx, dy)
        if (len == 0.0) return PointF(end.x, end.y)
        val angle = atan2(dy, dx)
        val step = stepDeg * PI / 180.0
        val snapped = (angle / step).roundToInt() * step
        if (abs(snapped - angle) > toleranceDeg * PI / 180.0) return PointF(end.x, end.y)
        return PointF((start.x + len * cos(snapped)).toFloat(), (start.y + len * sin(snapped)).toFloat())
    }

    /** Rounds [raw] to 1, 2 or 5 times a power of ten (axis tick spacing). */
    fun niceStep(raw: Float): Float {
        if (raw <= 0f || raw.isNaN()) return 1f
        val exp = floor(log10(raw.toDouble()))
        val base = 10.0.pow(exp)
        val f = raw / base
        val nice =
            when {
                f < 1.5 -> 1.0
                f < 3.5 -> 2.0
                f < 7.5 -> 5.0
                else -> 10.0
            }
        return (nice * base).toFloat()
    }

    // --- Builders ---

    private fun closedEdges(vertices: List<PointF>): List<Segment> =
        vertices.indices.map { i -> Segment(listOf(vertices[i], vertices[(i + 1) % vertices.size])) }

    private fun ellipse(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
    ): List<PointF> {
        val cx = (minX + maxX) / 2f
        var rx = (maxX - minX) / 2f
        var ry = (maxY - minY) / 2f
        // A nearly square drag means "circle".
        if (abs(rx - ry) < max(rx, ry) * 0.06f) {
            val r = (rx + ry) / 2f
            rx = r
            ry = r
        }
        val cy = (minY + maxY) / 2f
        return (0..ELLIPSE_STEPS).map { i ->
            val a = 2.0 * PI * i / ELLIPSE_STEPS
            PointF((cx + rx * cos(a)).toFloat(), (cy + ry * sin(a)).toFloat())
        }
    }

    /** Regular polygon centered on [center] with one vertex at [vertex]. */
    private fun regularPolygon(
        center: PointF,
        vertex: PointF,
        sides: Int,
    ): List<Segment> {
        val snapped = snapAngle(center, vertex)
        val r = hypot(snapped.x - center.x, snapped.y - center.y)
        val a0 = atan2((snapped.y - center.y).toDouble(), (snapped.x - center.x).toDouble())
        val vertices =
            (0 until sides).map { i ->
                val a = a0 + i * 2.0 * PI / sides
                PointF((center.x + r * cos(a)).toFloat(), (center.y + r * sin(a)).toFloat())
            }
        return closedEdges(vertices)
    }

    private fun arrowHeadLength(shaftLength: Float): Float = (shaftLength * 0.18f).coerceIn(12f, 48f).coerceAtMost(shaftLength * 0.45f)

    /** Two short strokes forming an arrow head at [tip], pointing away from [from]. */
    private fun arrowHead(
        tip: PointF,
        from: PointF,
        length: Float = arrowHeadLength(hypot(tip.x - from.x, tip.y - from.y)),
    ): List<Segment> {
        val back = atan2((from.y - tip.y).toDouble(), (from.x - tip.x).toDouble())
        return listOf(-1, 1).map { side ->
            val a = back + side * ARROW_ANGLE_RAD
            Segment(listOf(PointF((tip.x + length * cos(a)).toFloat(), (tip.y + length * sin(a)).toFloat()), tip))
        }
    }

    /**
     * Cartesian axes inside the box with the origin at ([ox], [oy]). Arrow heads point to +x (right) and
     * +y (up). Ticks use a 1/2/5 spacing; [withGrid] adds thin grid lines at every tick.
     */
    private fun axes2d(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        ox: Float,
        oy: Float,
        withGrid: Boolean,
    ): List<Segment> {
        val w = maxX - minX
        val h = maxY - minY
        val result = mutableListOf<Segment>()

        val head = arrowHeadLength(min(w, h).coerceAtLeast(MIN_DRAG) / 2f).coerceAtLeast(10f)

        val reference = max(min(maxX - ox, oy - minY), min(w, h) / 2f)
        val step = max(niceStep(reference / 5f), niceStep(max(w, h) / 30f))
        val tick = (step * 0.25f).coerceIn(4f, 14f)

        val xTicks = ticks(ox, minX, maxX - head * 1.2f, step)
        val yTicks = ticks(oy, minY + head * 1.2f, maxY, step)

        if (withGrid) {
            for (x in xTicks) result.add(Segment(listOf(PointF(x, minY), PointF(x, maxY)), GRID_WIDTH_SCALE))
            for (y in yTicks) result.add(Segment(listOf(PointF(minX, y), PointF(maxX, y)), GRID_WIDTH_SCALE))
        }

        if (w >= MIN_DRAG) {
            val xEnd = PointF(maxX, oy)
            result.add(Segment(listOf(PointF(minX, oy), xEnd)))
            result.addAll(arrowHead(xEnd, PointF(minX, oy), head))
        }
        if (h >= MIN_DRAG) {
            val yEnd = PointF(ox, minY)
            result.add(Segment(listOf(PointF(ox, maxY), yEnd)))
            result.addAll(arrowHead(yEnd, PointF(ox, maxY), head))
        }

        if (!withGrid) {
            if (h >= MIN_DRAG) {
                for (x in xTicks) result.add(Segment(listOf(PointF(x, oy - tick), PointF(x, oy + tick))))
            }
            if (w >= MIN_DRAG) {
                for (y in yTicks) result.add(Segment(listOf(PointF(ox - tick, y), PointF(ox + tick, y))))
            }
        }
        return result
    }

    /** Positions k*step away from [origin] (k != 0) that fall strictly inside [lo, hi]. */
    private fun ticks(
        origin: Float,
        lo: Float,
        hi: Float,
        step: Float,
    ): List<Float> {
        val out = mutableListOf<Float>()
        var v = origin + step
        while (v < hi - step * 0.05f) {
            out.add(v)
            v += step
        }
        v = origin - step
        while (v > lo + step * 0.05f) {
            out.add(v)
            v -= step
        }
        return out
    }

    /** Oblique 3D axes: z up, y right, x toward the viewer (down-left). */
    private fun axes3d(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
    ): List<Segment> {
        val w = maxX - minX
        val h = maxY - minY
        val ox = minX + w * 0.4f
        val oy = minY + h * 0.6f
        val origin = PointF(ox, oy)

        val zEnd = PointF(ox, minY)
        val yEnd = PointF(maxX, oy)
        val diag = min(ox - minX, maxY - oy)
        val xEnd = PointF(ox - diag, oy + diag)

        val head = arrowHeadLength(min(w, h) / 2f).coerceAtLeast(10f)
        val result = mutableListOf<Segment>()
        for (tip in listOf(xEnd, yEnd, zEnd)) {
            if (hypot(tip.x - ox, tip.y - oy) < MIN_DRAG) continue
            result.add(Segment(listOf(origin, tip)))
            result.addAll(arrowHead(tip, origin, head))
        }
        return result
    }

    /** Number line along start→end with an arrow at the end and a longer zero tick in the middle. */
    private fun numberLine(
        start: PointF,
        end: PointF,
    ): List<Segment> {
        val len = hypot(end.x - start.x, end.y - start.y)
        val ux = (end.x - start.x) / len
        val uy = (end.y - start.y) / len
        // Perpendicular for tick direction
        val px = -uy
        val py = ux

        val head = arrowHeadLength(len)
        val step = niceStep(len / 10f)
        val tick = (step * 0.2f).coerceIn(4f, 12f)
        val mid = len / 2f

        val result = mutableListOf(Segment(listOf(start, end)))
        result.addAll(arrowHead(end, start, head))

        fun tickAt(
            t: Float,
            size: Float,
        ) {
            val cx = start.x + ux * t
            val cy = start.y + uy * t
            result.add(Segment(listOf(PointF(cx - px * size, cy - py * size), PointF(cx + px * size, cy + py * size))))
        }

        tickAt(mid, tick * 1.8f)
        for (t in ticks(mid, step * 0.3f, len - head * 1.2f, step)) tickAt(t, tick)
        return result
    }
}
