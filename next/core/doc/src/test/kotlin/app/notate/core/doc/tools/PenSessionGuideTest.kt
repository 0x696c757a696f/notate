package app.notate.core.doc.tools

import app.notate.core.ink.guide.ProtractorState
import app.notate.core.ink.guide.RulerState
import app.notate.core.model.PenType
import app.notate.core.model.Stroke
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.math.hypot

class PenSessionGuideTest {
    private val pen = ToolPreset(ToolKind.PEN, pen = PenType.BALLPOINT, color = 0xFF000000.toInt(), widthPt = 1f)

    private class Guides(override val ruler: RulerState? = null, override val protractor: ProtractorState? = null) : GuideState {
        val rays = ArrayList<Float>()
        override val snapTolPt: Float = 17f
        override fun onRay(absAngleDeg: Float) { rays.add(absAngleDeg) }
    }

    private fun ctx(r: ToolRig, g: GuideState) = ToolContext(r.editor, r.p1, null, 1f, r.preview, ToolSettings(null, 5f), r.clock, g)

    @Test fun strokeAlongTheRulerEdgeBecomesAStraightLine() = runTest {
        val r = ToolRig(this)
        val ruler = RulerState(200f, 200f, 0f)
        val edge = 200f - ruler.widthPt / 2
        val s = PenSession(pen, ctx(r, Guides(ruler)))
        s.begin(ip(120f, edge - 4f, 0))                               // starts 4 pt away from the edge
        s.move((1..30).map { ip(120f + it * 5f, edge - 4f + (it % 3) - 1f, it * 5L) })   // wobbly hand
        s.end(ip(280f, edge - 3f, 160))
        val st = r.items().single() as Stroke
        assertThat(st.points.size).isEqualTo(2)
        for (i in 0 until st.points.size) assertThat(st.points.xy[2 * i + 1]).isWithin(1e-3f).of(edge)
        assertThat(st.points.xy[0]).isWithin(1e-3f).of(120f)
        assertThat(st.points.xy[2]).isWithin(1e-3f).of(280f)
    }

    @Test fun strokeFarFromTheRulerIsNotProjected() = runTest {
        val r = ToolRig(this)
        val ruler = RulerState(200f, 200f, 0f)
        val s = PenSession(pen, ctx(r, Guides(ruler)))
        s.begin(ip(120f, 20f, 0))
        s.move(listOf(ip(150f, 35f, 10), ip(180f, 18f, 20), ip(210f, 40f, 30)))
        s.end(ip(240f, 22f, 40))
        val st = r.items().single() as Stroke
        assertThat(st.points.size).isAtLeast(3)         // a zig-zag survives
    }

    @Test fun protractorArcKeepsTheRadius() = runTest {
        val r = ToolRig(this)
        val p = ProtractorState(200f, 300f, 0f, 100f)
        val s = PenSession(pen, ctx(r, Guides(protractor = p)))
        // Start on the arc at the top (disc is above the base for angle 0), sweep towards the right end.
        s.begin(ip(200f, 197f, 0))
        s.move((1..12).map { i -> val a = Math.toRadians(-90.0 + i * 5.0); ip(200f + 103f * Math.cos(a).toFloat(), 300f + 103f * Math.sin(a).toFloat(), i * 5L) })
        s.end(ip(200f + 103f * Math.cos(Math.toRadians(-30.0)).toFloat(), 300f + 103f * Math.sin(Math.toRadians(-30.0)).toFloat(), 70))
        val st = r.items().single() as Stroke
        for (i in 0 until st.points.size) assertThat(hypot(st.points.xy[2 * i] - 200f, st.points.xy[2 * i + 1] - 300f)).isWithin(0.01f).of(100f)
        assertThat(st.points.size).isAtLeast(25)        // 60° at 2° steps
    }

    @Test fun rayFromTheProtractorCentreIsReportedAndSnapped() = runTest {
        val r = ToolRig(this)
        val g = Guides(protractor = ProtractorState(200f, 300f, 0f, 100f))
        val s = PenSession(pen, ctx(r, g))
        s.begin(ip(202f, 299f, 0))
        s.move(listOf(ip(250f, 270f, 10)))
        s.end(ip(300f, 240.3f, 20))                       // ≈ -30.9° → -31°
        val st = r.items().single() as Stroke
        assertThat(st.points.size).isEqualTo(2)
        assertThat(st.points.xy[0]).isEqualTo(200f)
        assertThat(g.rays).hasSize(1)
        assertThat(g.rays[0]).isWithin(1e-3f).of(-31f)
    }

    @Test fun guidedPreviewIsProjectedLive() = runTest {
        val r = ToolRig(this)
        val ruler = RulerState(200f, 200f, 0f)
        val edge = 200f - ruler.widthPt / 2
        val s = PenSession(pen, ctx(r, Guides(ruler)))
        s.begin(ip(120f, edge - 3f, 0))
        s.move(listOf(ip(160f, edge + 2f, 10)))
        val live = r.preview.shown.last() as Preview.LiveStroke
        assertThat(live.points.xy[1]).isWithin(1e-3f).of(edge)
        assertThat(live.points.xy[live.points.xy.size - 1]).isWithin(1e-3f).of(edge)
    }
}
