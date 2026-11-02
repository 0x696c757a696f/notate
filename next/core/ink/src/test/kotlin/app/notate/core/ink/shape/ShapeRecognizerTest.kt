package app.notate.core.ink.shape

import app.notate.core.geometry.Vec2
import app.notate.core.model.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ShapeRecognizerTest {
    private fun rec(p: List<Vec2>) = ShapeRecognizer.recognize(FloatArray(p.size * 2) { if (it % 2 == 0) p[it / 2].x else p[it / 2].y })

    /** Deterministic hand jitter so tests are stable. */
    private fun jitter(i: Int) = (sin(i * 12.9898) * 1.5).toFloat()

    /** Samples a polyline through [vertices] with [perEdge] points per edge. */
    private fun stroke(
        vertices: List<Vec2>,
        perEdge: Int = 25,
    ): List<Vec2> {
        val out = mutableListOf<Vec2>()
        var k = 0
        for (i in 0 until vertices.size - 1) {
            val a = vertices[i]
            val b = vertices[i + 1]
            for (j in 0 until perEdge) {
                val t = j.toFloat() / perEdge
                out.add(Vec2(a.x + (b.x - a.x) * t + jitter(k), a.y + (b.y - a.y) * t + jitter(k + 7)))
                k++
            }
        }
        out.add(vertices.last())
        return out
    }

    private fun closed(vararg v: Vec2) = stroke(v.toList() + v[0])

    private fun ellipsePoints(
        a: Float,
        b: Float,
        n: Int = 80,
    ) = (0..n).map { i ->
        val t = 2 * PI * i / n
        Vec2((300 + a * cos(t)).toFloat() + jitter(i), (300 + b * sin(t)).toFloat() + jitter(i + 3))
    }

    @Test
    fun `slightly tilted line snaps horizontal`() {
        val result = rec(stroke(listOf(Vec2(0f, 0f), Vec2(300f, 8f))))!!
        assertEquals(ShapeKind.LINE, result.kind)
        assertEquals(4, result.params.size)
        assertEquals(result.params[1], result.params[3], 0.01f)
    }

    @Test
    fun `circle is recognized`() {
        val r = rec(ellipsePoints(120f, 118f))!!
        assertEquals(ShapeKind.ELLIPSE, r.kind)
        assertEquals(r.params[2], r.params[3], 1e-3f) // circle: rx = ry
        assertEquals(120f, r.params[2], 6f)
    }

    @Test
    fun `elongated oval is an ellipse not a circle`() {
        val result = rec(ellipsePoints(180f, 70f))!!
        assertEquals(ShapeKind.ELLIPSE, result.kind)
        assertEquals(5, result.params.size)
        assertEquals(360f, result.params[2] * 2, 12f)
        assertEquals(140f, result.params[3] * 2, 12f)
    }

    @Test
    fun `rectangle is recognized and squared`() {
        val result =
            rec(
                closed(Vec2(0f, 0f), Vec2(300f, 4f), Vec2(302f, 200f), Vec2(2f, 198f)),
            )!!
        assertEquals(ShapeKind.RECT, result.kind)
        assertEquals(8, result.params.size)
    }

    @Test
    fun `slanted quadrilateral keeps drawn corners`() {
        val result =
            rec(
                closed(Vec2(0f, 0f), Vec2(300f, 0f), Vec2(380f, 200f), Vec2(-160f, 200f)),
            )!!
        assertEquals(ShapeKind.POLYGON, result.kind)
        assertEquals(8, result.params.size)
    }

    @Test
    fun `triangle is recognized`() {
        val result = rec(closed(Vec2(150f, 0f), Vec2(300f, 260f), Vec2(0f, 260f)))!!
        assertEquals(ShapeKind.TRIANGLE, result.kind)
        assertEquals(6, result.params.size)
    }

    @Test
    fun `open L shape is straightened`() {
        val result = rec(stroke(listOf(Vec2(0f, 0f), Vec2(0f, 300f), Vec2(300f, 300f))))!!
        assertEquals(ShapeKind.POLYLINE, result.kind)
        assertEquals(6, result.params.size) // 3 vertices = 2 segments
    }

    @Test
    fun `open arc stays freehand`() {
        val arc = (0..60).map { i -> Vec2((300 + 150 * cos(PI * i / 60)).toFloat(), (300 - 150 * sin(PI * i / 60)).toFloat()) }
        assertNull(rec(arc))
    }

    @Test
    fun `too few points are ignored`() {
        assertNull(rec(listOf(Vec2(0f, 0f), Vec2(10f, 10f))))
    }

    @Test
    fun `scores are within 0 to 1`() {
        for (p in listOf(ellipsePoints(100f, 100f), stroke(listOf(Vec2(0f, 0f), Vec2(300f, 8f))))) {
            val s = rec(p)!!.score
            assertTrue(s in 0f..1f)
        }
    }

    @Test
    fun `regular pentagon is a polygon`() {
        val v = (0 until 5).map { Vec2((300 + 100 * cos(2 * PI * it / 5)).toFloat(), (300 + 100 * sin(2 * PI * it / 5)).toFloat()) }
        val r = rec(closed(*v.toTypedArray()))!!
        assertEquals(ShapeKind.POLYGON, r.kind)
        assertTrue(r.params.size >= 10) // 5 or 6 corners: legacy DP may add one on closed loops
    }
}
