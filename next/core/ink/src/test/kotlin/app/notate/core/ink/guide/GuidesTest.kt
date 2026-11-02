package app.notate.core.ink.guide

import app.notate.core.model.Units
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot

class GuidesTest {
    private val tol = Units.mm(6f)
    private val ruler = RulerState(100f, 100f, 0f)      // horizontal; edges at y = 100 ± 25.5

    @Test fun rulerSnapsToNearerEdgeWithinTolerance() {
        val top = ruler.cy - ruler.widthPt / 2
        val s = Guides.rulerSnap(ruler, 120f, top - 5f, tol)!!
        assertThat(s.y0).isWithin(1e-3f).of(top)
        assertThat(s.dx).isWithin(1e-4f).of(1f)
        val bottom = ruler.cy + ruler.widthPt / 2
        assertThat(Guides.rulerSnap(ruler, 120f, bottom + 5f, tol)!!.y0).isWithin(1e-3f).of(bottom)
    }

    @Test fun rulerDoesNotSnapFarAwayOrBeyondItsEnds() {
        assertThat(Guides.rulerSnap(ruler, 100f, ruler.cy - ruler.widthPt / 2 - tol - 1f, tol)).isNull()
        assertThat(Guides.rulerSnap(ruler, 100f + ruler.lengthPt / 2 + 1f, ruler.cy - ruler.widthPt / 2, tol)).isNull()
    }

    @Test fun rotatedRulerEdgeFollowsItsAngle() {
        val r = RulerState(0f, 0f, 90f)                 // vertical
        val s = Guides.rulerSnap(r, r.widthPt / 2 + 2f, 30f, tol)!!
        assertThat(s.dx).isWithin(1e-4f).of(0f)
        assertThat(s.dy).isWithin(1e-4f).of(1f)
        assertThat(Guides.project(s, 50f, 80f).x).isWithin(1e-3f).of(r.widthPt / 2)
    }

    @Test fun projectOntoLineAndArc() {
        val line = GuideSnap.Line(0f, 10f, 1f, 0f)
        assertThat(Guides.project(line, 33f, 55f)).isEqualTo(app.notate.core.geometry.Vec2(33f, 10f))
        val arc = GuideSnap.Arc(10f, 10f, 50f)
        for ((x, y) in listOf(70f to 10f, 10f to -90f, 40f to 50f)) {
            val p = Guides.project(arc, x, y)
            assertThat(hypot(p.x - 10f, p.y - 10f)).isWithin(1e-3f).of(50f)
        }
    }

    @Test fun rayProjectionSnapsToWholeDegreesAndKeepsDistance() {
        val ray = GuideSnap.Ray(0f, 0f, 0f)
        val p = Guides.project(ray, 100f, 0.4f)         // 0.23° off the base
        assertThat(p.y).isWithin(1e-3f).of(0f)
        assertThat(p.x).isWithin(0.05f).of(hypot(100f, 0.4f))
        assertThat(Guides.rayAngleDeg(ray, 100f, 100f)).isWithin(1e-3f).of(45f)
        assertThat(Guides.rayAngleDeg(GuideSnap.Ray(0f, 0f, 10f), 100f, 100f)).isWithin(1e-3f).of(45f)
    }

    @Test fun protractorSnapPicksRayArcOrBase() {
        val p = ProtractorState(0f, 0f, 0f, 100f)
        assertThat(Guides.protractorSnap(p, 3f, -2f, tol)).isInstanceOf(GuideSnap.Ray::class.java)
        // On the arc, above the base (disc is at negative y for angle 0).
        assertThat(Guides.protractorSnap(p, 0f, -102f, tol)).isEqualTo(GuideSnap.Arc(0f, 0f, 100f))
        // Below the base on the arc's circle: not part of the half disc.
        assertThat(Guides.protractorSnap(p, 0f, 102f, tol)).isNull()
        assertThat(Guides.protractorSnap(p, 50f, 3f, tol)).isInstanceOf(GuideSnap.Line::class.java)
        assertThat(Guides.protractorSnap(p, 50f, -50f, tol)).isNull()
    }

    @Test fun angleSnapStepsOf15WithinTolerance() {
        assertThat(Guides.snapAngle(31.5f)).isEqualTo(30f)
        assertThat(Guides.snapAngle(34f)).isEqualTo(34f)
        assertThat(Guides.snapAngle(-14f)).isEqualTo(-15f)
        assertThat(Guides.snapAngle(47f, 45f, 3f)).isEqualTo(45f)
    }

    @Test fun degreesReadCounterClockwise() {
        assertThat(Guides.lineDegrees(0f)).isEqualTo(0)
        assertThat(Guides.lineDegrees(-30f)).isEqualTo(30)      // y-down: negative = up-right = 30° ccw
        assertThat(Guides.lineDegrees(150f)).isEqualTo(30)
        assertThat(Guides.betweenDeg(10f, 350f)).isWithin(1e-4f).of(20f)
        assertThat(Guides.betweenDeg(0f, 90f)).isWithin(1e-4f).of(90f)
    }
}
