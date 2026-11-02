package app.notate.core.doc.tools

import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.ShapeKind
import app.notate.core.model.Stroke
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HoldToShapeTest {
    private val pen = ToolPreset(ToolKind.PEN, pen = PenType.BALLPOINT, color = 0xFF000000.toInt(), widthPt = 1f)

    /** Roughly rectangular path 100×60, closed, sampled along the edges. */
    private fun rectPath(): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        val cx = floatArrayOf(100f, 200f, 200f, 100f, 100f); val cy = floatArrayOf(100f, 100f, 160f, 160f, 100f)
        for (e in 0 until 4) for (k in 0 until 10) {
            val t = k / 10f
            out.add((cx[e] + (cx[e + 1] - cx[e]) * t + (if (k % 2 == 0) 0.3f else -0.3f)) to (cy[e] + (cy[e + 1] - cy[e]) * t))
        }
        out.add(100f to 100f)
        return out
    }

    private fun draw(r: ToolRig, s: PenSession): Pair<Float, Float> {
        val path = rectPath()
        s.begin(ip(path[0].first, path[0].second, 0))
        path.drop(1).forEachIndexed { i, p -> r.clock.advance(10); s.move(listOf(ip(p.first, p.second, (i + 1) * 10L))) }
        return path.last()
    }

    @Test fun holdRecognisesRectThenPenResizes() = runTest {
        val r = ToolRig(this)
        val s = PenSession(pen, r.ctx)
        val end = draw(r, s)
        r.clock.advance(650); s.poll()
        assertThat(r.preview.shown.last()).isInstanceOf(Preview.Ghost::class.java)
        val before = ((r.preview.shown.last() as Preview.Ghost).items.single() as Shape)
        assertThat(before.kind).isEqualTo(ShapeKind.RECT)
        // Drag outward: shape grows around its centre.
        s.move(listOf(ip(end.first - 60f, end.second - 60f, 2000)))
        val result = (r.preview.shown.last() as Preview.Ghost).items.single() as Shape
        s.end(ip(end.first - 60f, end.second - 60f, 2010))
        val committed = r.items().single() as Shape
        assertThat(committed.kind).isEqualTo(ShapeKind.RECT)
        assertThat(committed.bounds.width).isGreaterThan(before.bounds.width * 1.3f)
        assertThat(committed).isEqualTo(result)
    }

    @Test fun neverRecognisesWhenOff() = runTest {
        val r = ToolRig(this, holdMs = null)
        val s = PenSession(pen, r.ctx)
        draw(r, s); r.clock.advance(5000); s.poll()
        s.end(ip(100f, 100f, 9000))
        assertThat(r.items().single()).isInstanceOf(Stroke::class.java)
    }

    @Test fun failedRecognitionKeepsStroke() = runTest {
        val r = ToolRig(this)
        val s = PenSession(pen, r.ctx)
        // A scribble that no recogniser can fit.
        s.begin(ip(10f, 10f, 0))
        val rnd = java.util.Random(7)
        for (i in 1..60) { r.clock.advance(5); s.move(listOf(ip(10f + rnd.nextFloat() * 80f, 10f + rnd.nextFloat() * 80f, i * 5L))) }
        r.clock.advance(700); s.poll()
        s.end(ip(50f, 50f, 5000))
        assertThat(r.items().single()).isInstanceOf(Stroke::class.java)
    }
}
