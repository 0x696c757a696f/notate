package app.notate.core.doc.tools

import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.ShapeKind
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.math.hypot

class HoldToShapeAdjustTest {
    private val pen = ToolPreset(ToolKind.PEN, pen = PenType.BALLPOINT, color = 0xFF000000.toInt(), widthPt = 1f)

    private fun path(pts: List<Pair<Float, Float>>, perEdge: Int = 10): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (e in 0 until pts.size - 1) for (k in 0 until perEdge) {
            val t = k / perEdge.toFloat()
            out.add((pts[e].first + (pts[e + 1].first - pts[e].first) * t) to (pts[e].second + (pts[e + 1].second - pts[e].second) * t))
        }
        out.add(pts.last())
        return out
    }

    private fun hold(r: ToolRig, s: PenSession, p: List<Pair<Float, Float>>) {
        s.begin(ip(p[0].first, p[0].second, 0))
        p.drop(1).forEachIndexed { i, q -> r.clock.advance(10); s.move(listOf(ip(q.first, q.second, (i + 1) * 10L))) }
        r.clock.advance(700); s.poll()
    }

    @Test fun lineAdjustMovesOnlyTheEnd() = runTest {
        val r = ToolRig(this)
        val s = PenSession(pen, r.ctx)
        hold(r, s, path(listOf(100f to 100f, 200f to 102f), 30))
        assertThat(s.isAdjusting).isTrue()
        val start = ((r.preview.shown.last() as Preview.Ghost).items.single() as Shape).params.copyOf()
        s.move(listOf(ip(150f, 180f, 3000)))
        s.end(ip(150f, 180f, 3010))
        val c = r.items().single() as Shape
        assertThat(c.kind).isEqualTo(ShapeKind.LINE)
        assertThat(c.params[0]).isEqualTo(start[0]); assertThat(c.params[1]).isEqualTo(start[1])
        assertThat(c.params[2]).isEqualTo(150f); assertThat(c.params[3]).isEqualTo(180f)
    }

    @Test fun rectScalesAndRotatesAroundTheFirstVertex() = runTest {
        val r = ToolRig(this)
        val s = PenSession(pen, r.ctx)
        hold(r, s, path(listOf(100f to 100f, 200f to 100f, 200f to 160f, 100f to 160f, 100f to 100f)))
        val before = ((r.preview.shown.last() as Preview.Ghost).items.single() as Shape)
        assertThat(before.kind).isEqualTo(ShapeKind.RECT)
        val pivot = before.params[0] to before.params[1]
        val last = ((r.preview.shown.last() as Preview.Ghost).items.single() as Shape)
        // Move the pen 40 pt along the diagonal away from the pivot's far corner: the shape grows, the pivot stays.
        val bx = before.bounds.center.x; val by = before.bounds.center.y
        val ux = (bx - pivot.first); val uy = (by - pivot.second)
        val n = hypot(ux, uy)
        s.move(listOf(ip(100f + ux / n * 40f, 160f + uy / n * 40f, 3000)))
        s.end(ip(100f + ux / n * 40f, 160f + uy / n * 40f, 3010))
        val c = r.items().single() as Shape
        assertThat(c.params[0]).isWithin(1e-2f).of(pivot.first)
        assertThat(c.params[1]).isWithin(1e-2f).of(pivot.second)
        assertThat(c.bounds.width * c.bounds.height).isGreaterThan(before.bounds.width * before.bounds.height)
        assertThat(c.style.widthPt).isEqualTo(before.style.widthPt)
        // It is still a rectangle: opposite sides are equal and the corners are right angles.
        val p = c.params
        fun d(i: Int, j: Int) = hypot(p[2 * i] - p[2 * j], p[2 * i + 1] - p[2 * j + 1])
        assertThat(d(0, 1)).isWithin(1e-2f).of(d(2, 3)); assertThat(d(1, 2)).isWithin(1e-2f).of(d(3, 0))
        assertThat(d(0, 2)).isWithin(1e-2f).of(d(1, 3))
        // Rotating the pen around the pivot rotates the shape.
        assertThat(last.kind).isEqualTo(ShapeKind.RECT)
    }

    @Test fun rectRotatesWhenThePenSweepsSideways() = runTest {
        val r = ToolRig(this)
        val s = PenSession(pen, r.ctx)
        hold(r, s, path(listOf(100f to 100f, 200f to 100f, 200f to 160f, 100f to 160f, 100f to 100f)))
        val g = (r.preview.shown.last() as Preview.Ghost).items.single() as Shape
        val axisBefore = g.params[2] - g.params[0]
        s.move(listOf(ip(400f, 400f, 3000)))      // big sideways drag
        s.end(ip(400f, 400f, 3010))
        val c = r.items().single() as Shape
        assertThat(c.params[2] - c.params[0]).isNotEqualTo(axisBefore)
        assertThat(c.params[3]).isNotEqualTo(g.params[3])   // the first edge is no longer horizontal
    }
}
