package com.alexdremov.notate.util

import android.graphics.PointF
import com.alexdremov.notate.model.ShapeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShapeGeometryTest {
    private val start = PointF(100f, 100f)
    private val end = PointF(400f, 300f)

    private fun allPoints(segments: List<ShapeGeometry.Segment>) = segments.flatMap { it.points }

    @Test
    fun `tap produces no shape`() {
        for (type in ShapeType.entries) {
            assertTrue(type.name, ShapeGeometry.build(type, start, PointF(102f, 101f)).isEmpty())
        }
    }

    @Test
    fun `non drag types produce nothing`() {
        assertTrue(ShapeGeometry.build(ShapeType.NONE, start, end).isEmpty())
        assertTrue(ShapeGeometry.build(ShapeType.AUTO, start, end).isEmpty())
    }

    @Test
    fun `every drag type produces geometry`() {
        for (type in ShapeType.entries.filter { it.isDragShape }) {
            val segments = ShapeGeometry.build(type, start, end)
            assertTrue(type.name, segments.isNotEmpty())
            assertTrue(type.name, segments.all { it.points.size >= 2 })
        }
    }

    @Test
    fun `nearly horizontal line snaps to horizontal`() {
        val segments = ShapeGeometry.build(ShapeType.LINE, start, PointF(400f, 110f))
        assertEquals(1, segments.size)
        val e = segments[0].points[1]
        assertEquals(start.y, e.y, 0.01f)
        assertEquals(hypot(300f, 10f), e.x - start.x, 0.01f)
    }

    @Test
    fun `steep line is not snapped`() {
        val e = ShapeGeometry.snapAngle(start, PointF(400f, 220f))
        assertEquals(400f, e.x, 0.001f)
        assertEquals(220f, e.y, 0.001f)
    }

    @Test
    fun `arrow has shaft and two head strokes ending at tip`() {
        val segments = ShapeGeometry.build(ShapeType.ARROW, start, PointF(400f, 100f))
        assertEquals(3, segments.size)
        val tip = segments[0].points[1]
        assertEquals(400f, tip.x, 0.01f)
        // Head strokes end at the tip and sit behind it
        for (head in segments.drop(1)) {
            assertEquals(tip.x, head.points[1].x, 0.01f)
            assertTrue(head.points[0].x < tip.x)
        }
    }

    @Test
    fun `double arrow has heads at both ends`() {
        assertEquals(5, ShapeGeometry.build(ShapeType.DOUBLE_ARROW, start, end).size)
    }

    @Test
    fun `rectangle covers the drag box`() {
        val segments = ShapeGeometry.build(ShapeType.RECTANGLE, end, start) // reversed drag
        assertEquals(4, segments.size)
        val pts = allPoints(segments)
        assertEquals(100f, pts.minOf { it.x }, 0.01f)
        assertEquals(400f, pts.maxOf { it.x }, 0.01f)
        assertEquals(100f, pts.minOf { it.y }, 0.01f)
        assertEquals(300f, pts.maxOf { it.y }, 0.01f)
    }

    @Test
    fun `square drag gives a circle`() {
        val segments = ShapeGeometry.build(ShapeType.ELLIPSE, PointF(0f, 0f), PointF(200f, 205f))
        val pts = segments.single().points
        val radii = pts.map { hypot(it.x - 100f, it.y - 102.5f) }
        assertTrue(radii.max() - radii.min() < 0.5f)
    }

    @Test
    fun `ellipse fits the drag box`() {
        val pts = ShapeGeometry.build(ShapeType.ELLIPSE, start, end).single().points
        assertEquals(100f, pts.minOf { it.x }, 0.5f)
        assertEquals(400f, pts.maxOf { it.x }, 0.5f)
        assertEquals(100f, pts.minOf { it.y }, 0.5f)
        assertEquals(300f, pts.maxOf { it.y }, 0.5f)
    }

    @Test
    fun `right triangle has a right angle`() {
        val segments = ShapeGeometry.build(ShapeType.RIGHT_TRIANGLE, start, end)
        assertEquals(3, segments.size)
        val corner = segments[1].points[0]
        assertEquals(start.x, corner.x, 0.01f)
        assertEquals(end.y, corner.y, 0.01f)
    }

    @Test
    fun `polygon has requested number of equal sides`() {
        for (n in listOf(3, 5, 8)) {
            val segments = ShapeGeometry.build(ShapeType.POLYGON, start, PointF(200f, 130f), n)
            assertEquals(n, segments.size)
            val lengths = segments.map { hypot(it.points[1].x - it.points[0].x, it.points[1].y - it.points[0].y) }
            assertTrue(lengths.max() - lengths.min() < 0.01f)
        }
        // Sides are clamped
        assertEquals(12, ShapeGeometry.build(ShapeType.POLYGON, start, end, 40).size)
    }

    @Test
    fun `axes cross at the center with ticks`() {
        val segments = ShapeGeometry.build(ShapeType.AXES, PointF(0f, 0f), PointF(400f, 400f))
        val xAxis = segments.first { it.points.size == 2 && it.points[0].y == 200f && it.points[1].y == 200f && it.points[0].x == 0f }
        assertEquals(400f, xAxis.points[1].x, 0.01f)
        val yAxis = segments.first { it.points[0].x == 200f && it.points[1].x == 200f && it.points[0].y == 400f }
        assertEquals(0f, yAxis.points[1].y, 0.01f)
        // 2 axes + 4 arrow head strokes + ticks
        assertTrue(segments.size > 6)
        assertTrue(segments.all { it.widthScale == 1f })
    }

    @Test
    fun `quadrant axes start at the bottom left`() {
        val segments = ShapeGeometry.build(ShapeType.AXES_QUADRANT, PointF(0f, 0f), PointF(300f, 200f))
        val xAxis = segments.first { it.points[0].x == 0f && it.points[0].y == 200f && it.points[1].y == 200f }
        assertEquals(300f, xAxis.points[1].x, 0.01f)
    }

    @Test
    fun `grid adds thin lines`() {
        val segments = ShapeGeometry.build(ShapeType.GRID, PointF(0f, 0f), PointF(400f, 400f))
        val thin = segments.filter { it.widthScale < 1f }
        assertTrue(thin.size >= 4)
        // Grid lines span the full box
        val vertical = thin.first { it.points[0].x == it.points[1].x }
        assertEquals(0f, vertical.points[0].y, 0.01f)
        assertEquals(400f, vertical.points[1].y, 0.01f)
    }

    @Test
    fun `wide axes keep tick count bounded`() {
        val segments = ShapeGeometry.build(ShapeType.AXES, PointF(0f, 0f), PointF(5000f, 20f))
        assertTrue(segments.size < 80)
    }

    @Test
    fun `3d axes have three arrows`() {
        val segments = ShapeGeometry.build(ShapeType.AXES_3D, PointF(0f, 0f), PointF(300f, 300f))
        assertEquals(9, segments.size)
    }

    @Test
    fun `number line has evenly spaced ticks`() {
        val segments = ShapeGeometry.build(ShapeType.NUMBER_LINE, PointF(0f, 100f), PointF(500f, 100f))
        val ticks = segments.filter { abs(it.points[0].x - it.points[1].x) < 0.01f }
        assertTrue(ticks.size >= 5)
        val xs = ticks.map { it.points[0].x }.sorted()
        val gaps = xs.zipWithNext { a, b -> b - a }
        assertTrue(gaps.max() - gaps.min() < 0.01f)
    }

    @Test
    fun `nice step uses 1 2 5 spacing`() {
        assertEquals(10f, ShapeGeometry.niceStep(12f), 0.001f)
        assertEquals(20f, ShapeGeometry.niceStep(27f), 0.001f)
        assertEquals(50f, ShapeGeometry.niceStep(40f), 0.001f)
        assertEquals(100f, ShapeGeometry.niceStep(80f), 0.001f)
    }
}
