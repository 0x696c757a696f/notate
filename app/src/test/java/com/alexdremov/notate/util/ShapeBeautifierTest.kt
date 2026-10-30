package com.alexdremov.notate.util

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShapeBeautifierTest {
    private fun sides(v: List<PointF>) = v.indices.map { i -> hypot(v[(i + 1) % v.size].x - v[i].x, v[(i + 1) % v.size].y - v[i].y) }

    private fun hasLevelSide(v: List<PointF>) =
        v.indices.any { i ->
            val a = v[i]
            val b = v[(i + 1) % v.size]
            abs(b.y - a.y) < 0.01f || abs(b.x - a.x) < 0.01f
        }

    private fun abs(f: Float) = kotlin.math.abs(f)

    @Test
    fun `rough equilateral becomes equilateral with a level side`() {
        val v = ShapeBeautifier.triangle(listOf(PointF(150f, 5f), PointF(290f, 262f), PointF(8f, 255f)))
        val s = sides(v)
        assertEquals(s[0], s[1], 0.5f)
        assertEquals(s[1], s[2], 0.5f)
        assertEquals(true, hasLevelSide(v))
    }

    @Test
    fun `near right angle becomes exactly 90 degrees with axis aligned legs`() {
        val v = ShapeBeautifier.triangle(listOf(PointF(0f, 0f), PointF(12f, 300f), PointF(400f, 290f)))
        val angles = ShapeBeautifier.angles(v)
        assertEquals(90.0, angles.maxOrNull()!!, 0.01)
        assertEquals(true, hasLevelSide(v))
    }

    @Test
    fun `near isosceles gets two equal sides`() {
        // Tall triangle, apex slightly off-centre
        val v = ShapeBeautifier.triangle(listOf(PointF(108f, 0f), PointF(200f, 400f), PointF(0f, 402f)))
        val s = sides(v)
        assertEquals(s[0], s[2], 0.5f)
        assertEquals(true, hasLevelSide(v))
    }

    @Test
    fun `irregular triangle keeps its corners`() {
        val input = listOf(PointF(0f, 0f), PointF(500f, 150f), PointF(250f, 230f))
        val v = ShapeBeautifier.triangle(input)
        for (i in 0 until 3) {
            assertEquals(input[i].x, v[i].x, 0.01f)
            assertEquals(input[i].y, v[i].y, 0.01f)
        }
        assertEquals(180.0, ShapeBeautifier.angles(v).sum(), 0.01)
    }

    /** Cross product of edges i and j: zero when parallel. */
    private fun edgeCross(
        v: List<PointF>,
        i: Int,
        j: Int,
    ): Float {
        val a = PointF(v[(i + 1) % 4].x - v[i].x, v[(i + 1) % 4].y - v[i].y)
        val b = PointF(v[(j + 1) % 4].x - v[j].x, v[(j + 1) % 4].y - v[j].y)
        return (a.x * b.y - a.y * b.x) / (hypot(a.x, a.y) * hypot(b.x, b.y))
    }

    @Test
    fun `rough parallelogram gets parallel opposite sides and a level base`() {
        val v = ShapeBeautifier.quadrilateral(listOf(PointF(100f, 4f), PointF(400f, 0f), PointF(310f, 200f), PointF(5f, 198f)))
        assertEquals(0f, edgeCross(v, 0, 2), 1e-4f)
        assertEquals(0f, edgeCross(v, 1, 3), 1e-4f)
        assertEquals(true, hasLevelSide(v))
    }

    @Test
    fun `rough rhombus gets four equal sides`() {
        val v = ShapeBeautifier.quadrilateral(listOf(PointF(150f, 0f), PointF(305f, 148f), PointF(150f, 300f), PointF(-4f, 152f)))
        val s = sides(v)
        for (i in 1 until 4) assertEquals(s[0], s[i], 0.5f)
    }

    @Test
    fun `rough isosceles trapezoid becomes symmetric`() {
        val v = ShapeBeautifier.quadrilateral(listOf(PointF(100f, 3f), PointF(300f, 0f), PointF(395f, 200f), PointF(2f, 204f)))
        assertEquals(0f, edgeCross(v, 0, 2), 1e-4f)
        val s = sides(v)
        assertEquals(s[1], s[3], 0.5f)
        assertEquals(true, hasLevelSide(v))
    }

    @Test
    fun `irregular quadrilateral keeps its corners`() {
        val input = listOf(PointF(0f, 0f), PointF(300f, 60f), PointF(380f, 330f), PointF(-50f, 120f))
        val v = ShapeBeautifier.quadrilateral(input)
        for (i in 0 until 4) {
            assertEquals(input[i].x, v[i].x, 0.01f)
            assertEquals(input[i].y, v[i].y, 0.01f)
        }
    }
}
