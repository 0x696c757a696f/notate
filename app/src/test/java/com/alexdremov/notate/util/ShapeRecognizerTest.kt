package com.alexdremov.notate.util

import android.graphics.PointF
import com.alexdremov.notate.util.ShapeRecognizer.RecognizedShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShapeRecognizerTest {
    /** Deterministic hand jitter so tests are stable. */
    private fun jitter(i: Int) = (sin(i * 12.9898) * 1.5).toFloat()

    /** Samples a polyline through [vertices] with [perEdge] points per edge. */
    private fun stroke(
        vertices: List<PointF>,
        perEdge: Int = 25,
    ): List<PointF> {
        val out = mutableListOf<PointF>()
        var k = 0
        for (i in 0 until vertices.size - 1) {
            val a = vertices[i]
            val b = vertices[i + 1]
            for (j in 0 until perEdge) {
                val t = j.toFloat() / perEdge
                out.add(PointF(a.x + (b.x - a.x) * t + jitter(k), a.y + (b.y - a.y) * t + jitter(k + 7)))
                k++
            }
        }
        out.add(vertices.last())
        return out
    }

    private fun closed(vararg v: PointF) = stroke(v.toList() + v[0])

    private fun ellipsePoints(
        a: Float,
        b: Float,
        n: Int = 80,
    ) = (0..n).map { i ->
        val t = 2 * PI * i / n
        PointF((300 + a * cos(t)).toFloat() + jitter(i), (300 + b * sin(t)).toFloat() + jitter(i + 3))
    }

    @Test
    fun `slightly tilted line snaps horizontal`() {
        val result = ShapeRecognizer.recognizePoints(stroke(listOf(PointF(0f, 0f), PointF(300f, 8f))))!!
        assertEquals(RecognizedShape.LINE, result.shape)
        val seg = result.segments.single()
        assertEquals(seg[0].y, seg[1].y, 0.01f)
    }

    @Test
    fun `circle is recognized`() {
        assertEquals(RecognizedShape.CIRCLE, ShapeRecognizer.recognizePoints(ellipsePoints(120f, 118f))!!.shape)
    }

    @Test
    fun `elongated oval is an ellipse not a circle`() {
        val result = ShapeRecognizer.recognizePoints(ellipsePoints(180f, 70f))!!
        assertEquals(RecognizedShape.ELLIPSE, result.shape)
        val pts = result.segments.single()
        val width = pts.maxOf { it.x } - pts.minOf { it.x }
        val height = pts.maxOf { it.y } - pts.minOf { it.y }
        assertEquals(360f, width, 12f)
        assertEquals(140f, height, 12f)
    }

    @Test
    fun `rectangle is recognized and squared`() {
        val result =
            ShapeRecognizer.recognizePoints(
                closed(PointF(0f, 0f), PointF(300f, 4f), PointF(302f, 200f), PointF(2f, 198f)),
            )!!
        assertEquals(RecognizedShape.SQUARE, result.shape)
        assertEquals(4, result.segments.size)
    }

    @Test
    fun `slanted quadrilateral keeps drawn corners`() {
        val result =
            ShapeRecognizer.recognizePoints(
                closed(PointF(0f, 0f), PointF(300f, 0f), PointF(380f, 200f), PointF(-160f, 200f)),
            )!!
        assertEquals(RecognizedShape.POLYGON, result.shape)
        assertEquals(4, result.segments.size)
    }

    @Test
    fun `triangle is recognized`() {
        val result = ShapeRecognizer.recognizePoints(closed(PointF(150f, 0f), PointF(300f, 260f), PointF(0f, 260f)))!!
        assertEquals(RecognizedShape.TRIANGLE, result.shape)
        assertEquals(3, result.segments.size)
    }

    @Test
    fun `open L shape is straightened`() {
        val result = ShapeRecognizer.recognizePoints(stroke(listOf(PointF(0f, 0f), PointF(0f, 300f), PointF(300f, 300f))))!!
        assertEquals(RecognizedShape.POLYLINE, result.shape)
        assertEquals(2, result.segments.size)
    }

    @Test
    fun `open arc stays freehand`() {
        val arc = (0..60).map { i -> PointF((300 + 150 * cos(PI * i / 60)).toFloat(), (300 - 150 * sin(PI * i / 60)).toFloat()) }
        assertNull(ShapeRecognizer.recognizePoints(arc))
    }

    @Test
    fun `too few points are ignored`() {
        assertNull(ShapeRecognizer.recognizePoints(listOf(PointF(0f, 0f), PointF(10f, 10f))))
    }

    @Test
    fun `results expose a preview path`() {
        val result = ShapeRecognizer.recognizePoints(ellipsePoints(100f, 100f))!!
        assertTrue(!result.path.isEmpty)
    }
}
