package app.notate.core.ink.shape

import app.notate.core.geometry.Vec2
import app.notate.core.model.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Hand-drawn-looking input: rounded corners, smooth wobble, overshoot or a gap where the loop closes. */
class ShapeRecognizerSloppyTest {
    private fun rec(p: List<Vec2>) = ShapeRecognizer.recognize(FloatArray(p.size * 2) { if (it % 2 == 0) p[it / 2].x else p[it / 2].y })

    private fun wobble(
        p: Vec2,
        t: Float,
        amp: Float,
        seed: Int,
    ) = Vec2(p.x + amp * sin(t * 7.1 + seed).toFloat(), p.y + amp * sin(t * 5.3 + seed * 2.0).toFloat())

    /** Walks a closed polygon with rounded corners ([round] = fraction of the shorter adjacent edge) and resamples it. */
    private fun roundedPolygon(
        v: List<Vec2>,
        round: Float,
        close: Float, // >0 overshoot past the start, <0 stops short (fraction of the perimeter)
        amp: Float,
        angle: Double = 0.0,
        seed: Int = 1,
    ): List<Vec2> {
        val c = Vec2(v.map { it.x }.average().toFloat(), v.map { it.y }.average().toFloat())
        val ca = cos(angle).toFloat()
        val sa = sin(angle).toFloat()
        val rv = v.map { Vec2(c.x + (it.x - c.x) * ca - (it.y - c.y) * sa, c.y + (it.x - c.x) * sa + (it.y - c.y) * ca) }
        val dense = mutableListOf<Vec2>()
        val n = rv.size
        for (i in 0 until n + 1) {
            val prev = rv[(i + n - 1) % n]
            val cur = rv[i % n]
            val next = rv[(i + 1) % n]
            val r1 = round * minOf(hypot(cur.x - prev.x, cur.y - prev.y), hypot(next.x - cur.x, next.y - cur.y))
            val d1 = hypot(prev.x - cur.x, prev.y - cur.y)
            val d2 = hypot(next.x - cur.x, next.y - cur.y)
            val a = Vec2(cur.x + (prev.x - cur.x) / d1 * r1, cur.y + (prev.y - cur.y) / d1 * r1)
            val b = Vec2(cur.x + (next.x - cur.x) / d2 * r1, cur.y + (next.y - cur.y) / d2 * r1)
            for (k in 0..8) {
                val t = k / 8f
                val m1 = Vec2(a.x + (cur.x - a.x) * t, a.y + (cur.y - a.y) * t)
                val m2 = Vec2(cur.x + (b.x - cur.x) * t, cur.y + (b.y - cur.y) * t)
                dense.add(Vec2(m1.x + (m2.x - m1.x) * t, m1.y + (m2.y - m1.y) * t))
            }
        }
        return finish(dense, close, amp, seed)
    }

    /** Inserts points so no gap exceeds ~3 units, like a digitizer sampling a moving pen. */
    private fun densify(p: List<Vec2>): List<Vec2> {
        val out = mutableListOf(p[0])
        for (i in 1 until p.size) {
            val a = p[i - 1]
            val b = p[i]
            val steps = (hypot(b.x - a.x, b.y - a.y) / 3f).toInt().coerceAtLeast(1)
            for (k in 1..steps) out.add(Vec2(a.x + (b.x - a.x) * k / steps, a.y + (b.y - a.y) * k / steps))
        }
        return out
    }

    private fun finish(
        sparse: List<Vec2>,
        close: Float,
        amp: Float,
        seed: Int,
    ): List<Vec2> {
        val dense = densify(sparse)
        var len = 0f
        for (i in 1 until dense.size) len += hypot(dense[i].x - dense[i - 1].x, dense[i].y - dense[i - 1].y)
        val keep = (len * (1f + close)).coerceAtLeast(1f)
        // Overshoot: continue along the path again after the start.
        val path = if (close > 0f) dense + dense.drop(1) else dense
        val out = mutableListOf<Vec2>()
        var walked = 0f
        out.add(path[0])
        for (i in 1 until path.size) {
            walked += hypot(path[i].x - path[i - 1].x, path[i].y - path[i - 1].y)
            if (walked > keep) break
            out.add(wobble(path[i], walked / len * 6f, amp, seed))
        }
        return out
    }

    private fun circle(
        r: Float,
        close: Float,
        amp: Float,
        drift: Float = 0.06f,
    ): List<Vec2> {
        val total = 2 * PI * (1 + close)
        val n = 120
        return (0..n).map { i ->
            val t = total * i / n
            val rr = r * (1 + drift * t / (2 * PI)).toFloat()
            wobble(Vec2((300 + rr * cos(t)).toFloat(), (300 + rr * sin(t)).toFloat()), t.toFloat(), amp, 3)
        }
    }

    private val rect = listOf(Vec2(0f, 0f), Vec2(300f, 0f), Vec2(300f, 190f), Vec2(0f, 190f))
    private val tri = listOf(Vec2(150f, 0f), Vec2(300f, 260f), Vec2(0f, 260f))

    private fun kind(p: List<Vec2>) = rec(p)?.kind

    @Test fun `rounded rectangle with overshoot`() = assertEquals(ShapeKind.RECT, kind(roundedPolygon(rect, 0.25f, 0.06f, 3f)))

    @Test fun `rounded rectangle with gap`() = assertEquals(ShapeKind.RECT, kind(roundedPolygon(rect, 0.25f, -0.05f, 3f)))

    @Test fun `very rounded rectangle`() = assertEquals(ShapeKind.RECT, kind(roundedPolygon(rect, 0.3f, 0.04f, 3f)))

    @Test fun `tilted rectangle`() = assertEquals(ShapeKind.RECT, kind(roundedPolygon(rect, 0.2f, 0.05f, 3f, angle = 0.35)))

    @Test fun `wobbly rectangle`() = assertEquals(ShapeKind.RECT, kind(roundedPolygon(rect, 0.15f, 0.03f, 7f, seed = 4)))

    @Test fun `rounded triangle`() = assertEquals(ShapeKind.TRIANGLE, kind(roundedPolygon(tri, 0.2f, 0.05f, 3f)))

    @Test fun `wobbly triangle`() = assertEquals(ShapeKind.TRIANGLE, kind(roundedPolygon(tri, 0.1f, 0.03f, 7f, seed = 5)))

    @Test fun `sloppy circle with overshoot`() = assertEquals(ShapeKind.ELLIPSE, kind(circle(110f, 0.12f, 4f)))

    @Test fun `sloppy circle with gap`() = assertEquals(ShapeKind.ELLIPSE, kind(circle(110f, -0.06f, 4f)))

    @Test
    fun `sloppy circle comes out round`() {
        val r = rec(circle(110f, 0.1f, 4f))!!
        assertEquals(r.params[2], r.params[3], 1e-3f)
    }

    @Test
    fun `sloppy ellipse`() {
        val p = circle(100f, 0.08f, 3f, drift = 0.03f).map { Vec2(300 + (it.x - 300) * 1.8f, 300 + (it.y - 300) * 0.8f) }
        assertEquals(ShapeKind.ELLIPSE, kind(p))
    }

    @Test
    fun `regular pentagon is never irregular`() {
        val v = (0 until 5).map { Vec2((300 + 100 * cos(2 * PI * it / 5)).toFloat(), (300 + 100 * sin(2 * PI * it / 5)).toFloat()) }
        val r = rec(roundedPolygon(v, 0.12f, 0.04f, 3f))!!
        assertEquals(ShapeKind.POLYGON, r.kind)
        assertEquals(10, r.params.size)
    }

    @Test
    fun `rectangle result is axis aligned and exact`() {
        val r = rec(roundedPolygon(rect, 0.25f, 0.06f, 3f))!!
        val p = r.params
        for (i in 0 until 4) {
            val dx = p[(i * 2 + 2) % 8] - p[i * 2]
            val dy = p[(i * 2 + 3) % 8] - p[i * 2 + 1]
            assertEquals(0f, minOf(abs(dx), abs(dy)), 0.5f)
        }
        assertEquals(300f, max(abs(p[0] - p[2]), abs(p[2] - p[4])), 25f)
    }
}
