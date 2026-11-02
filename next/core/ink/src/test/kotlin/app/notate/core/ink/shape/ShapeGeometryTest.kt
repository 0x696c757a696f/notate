package app.notate.core.ink.shape

import app.notate.core.geometry.Vec2
import app.notate.core.model.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ShapeGeometryTest {
    private val start = Vec2(100f, 100f)
    private val end = Vec2(400f, 300f)

    @Test
    fun `tap produces no shape`() {
        for (kind in ShapeKind.entries) assertNull(kind.name, ShapeGeometry.paramsFromDrag(kind, start, Vec2(102f, 101f)))
    }

    @Test
    fun `every draggable kind produces params`() {
        for (kind in ShapeKind.entries.filter { it != ShapeKind.POLYLINE }) assertTrue(kind.name, ShapeGeometry.paramsFromDrag(kind, start, end) != null)
    }

    @Test
    fun `nearly horizontal line snaps to horizontal`() {
        val p = ShapeGeometry.paramsFromDrag(ShapeKind.LINE, start, Vec2(400f, 110f))!!
        assertEquals(start.y, p[3], 0.01f)
        assertEquals(hypot(300f, 10f), p[2] - start.x, 0.01f)
    }

    @Test
    fun `steep line is not snapped`() {
        val e = ShapeGeometry.snapAngle(start, Vec2(400f, 220f))
        assertEquals(400f, e.x, 0.001f)
        assertEquals(220f, e.y, 0.001f)
    }

    @Test
    fun `rectangle covers the drag box even when reversed`() {
        val p = ShapeGeometry.paramsFromDrag(ShapeKind.RECT, end, start)!!
        assertEquals(listOf(100f, 100f, 400f, 100f, 400f, 300f, 100f, 300f), p.toList())
    }

    @Test
    fun `square drag gives a circle`() {
        val p = ShapeGeometry.paramsFromDrag(ShapeKind.ELLIPSE, Vec2(0f, 0f), Vec2(200f, 205f))!!
        assertEquals(p[2], p[3], 1e-4f)
        assertEquals(100f, p[0], 1e-3f)
    }

    @Test
    fun `ellipse fits the drag box`() {
        val p = ShapeGeometry.paramsFromDrag(ShapeKind.ELLIPSE, start, end)!!
        assertEquals(listOf(250f, 200f, 150f, 100f, 0f), p.toList())
    }

    @Test
    fun `polygon has requested number of equal sides and clamps`() {
        for (n in listOf(3, 5, 8)) {
            val p = ShapeGeometry.paramsFromDrag(ShapeKind.POLYGON, start, Vec2(200f, 130f), n)!!
            assertEquals(2 * n, p.size)
            val sides = (0 until n).map { i -> val j = (i + 1) % n; hypot(p[2 * j] - p[2 * i], p[2 * j + 1] - p[2 * i + 1]) }
            assertTrue(sides.max() - sides.min() < 0.01f)
        }
        assertEquals(24, ShapeGeometry.paramsFromDrag(ShapeKind.POLYGON, start, end, 40)!!.size)
    }

    @Test
    fun `axes params center and bottom left origin`() {
        val a = ShapeGeometry.paramsFromDrag(ShapeKind.AXES_2D, Vec2(0f, 0f), Vec2(400f, 400f))!!
        assertEquals(200f, a[0], 1e-3f); assertEquals(200f, a[1], 1e-3f)
        assertEquals(400f, a[2], 1e-3f)
        val q = ShapeGeometry.paramsFromDrag(ShapeKind.AXES_Q1, Vec2(0f, 0f), Vec2(300f, 200f))!!
        assertEquals(0f, q[0], 1e-3f); assertEquals(200f, q[1], 1e-3f)
        assertTrue(q[5] > 0f)
    }

    @Test
    fun `nice step uses 1 2 5 spacing`() {
        assertEquals(10f, ShapeGeometry.niceStep(12f), 0.001f)
        assertEquals(20f, ShapeGeometry.niceStep(27f), 0.001f)
        assertEquals(50f, ShapeGeometry.niceStep(40f), 0.001f)
        assertEquals(100f, ShapeGeometry.niceStep(80f), 0.001f)
    }
}
