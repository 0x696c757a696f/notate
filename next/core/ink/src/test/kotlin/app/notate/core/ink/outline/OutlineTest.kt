package app.notate.core.ink.outline

import app.notate.core.model.InkStyle
import app.notate.core.model.PenType
import app.notate.core.model.StrokePoints
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

private fun style(pen: PenType, w: Float = 2f) = InkStyle(pen, 0xFF000000.toInt(), w)

private fun line(n: Int, step: Float = 1f, pressure: Float? = 1f, dtMs: Int = 8): StrokePoints =
    StrokePoints(
        FloatArray(2 * n) { if (it % 2 == 0) it / 2 * step else 50f },
        pressure?.let { p -> FloatArray(n) { p } },
        IntArray(n) { it * dtMs },
    )

private fun ys(g: InkGeometry.Fill) = g.polygon.filterIndexed { i, _ -> i % 2 == 1 }

class VariableWidthOutlineTest {
    @Test fun horizontalLineSpansPlusMinusHalfWidth() {
        // BALLPOINT at pressure 1 → width exactly w.
        val g = Outlines.forPen(PenType.BALLPOINT).outline(line(30), style(PenType.BALLPOINT, 2f)) as InkGeometry.Fill
        val y = ys(g)
        assertThat(y.max()).isWithin(0.01f).of(51f)
        assertThat(y.min()).isWithin(0.01f).of(49f)
    }

    @Test fun singlePointIsACircleOfWidthDiameter() {
        val p = StrokePoints(floatArrayOf(10f, 20f), floatArrayOf(1f), intArrayOf(0))
        val g = Outlines.forPen(PenType.BALLPOINT).outline(p, style(PenType.BALLPOINT, 4f)) as InkGeometry.Fill
        for (i in g.polygon.indices step 2) assertThat(hypot(g.polygon[i] - 10f, g.polygon[i + 1] - 20f)).isWithin(0.01f).of(2f)
    }

    @Test fun allPensProduceFiniteOutputOnCurvesAndSharpTurns() {
        val n = 400
        val xy = FloatArray(2 * n)
        for (i in 0 until n) { xy[2 * i] = i * 0.7f; xy[2 * i + 1] = (20 * sin(i * 0.05 * PI)).toFloat() }
        // Add a hairpin and a duplicate point.
        xy[2 * 100] = xy[2 * 99]; xy[2 * 100 + 1] = xy[2 * 99 + 1]
        xy[2 * 200] = xy[2 * 198]; xy[2 * 200 + 1] = xy[2 * 198 + 1]
        val pts = StrokePoints(xy, FloatArray(n) { (it % 10) / 9f }, IntArray(n) { it * 8 })
        for (pen in listOf(PenType.BALLPOINT, PenType.FOUNTAIN, PenType.PENCIL)) {
            val g = Outlines.forPen(pen).outline(pts, style(pen)) as InkGeometry.Fill
            assertThat(g.polygon.all { it.isFinite() }).isTrue()
            assertThat(g.polygon.size).isGreaterThan(2 * n)
        }
    }

    @Test fun benchmark100kPoints() {
        val n = 100_000
        val xy = FloatArray(2 * n) { if (it % 2 == 0) it / 2 * 0.3f else (10 * sin(it * 0.001)).toFloat() }
        val pts = StrokePoints(xy, FloatArray(n) { 0.5f }, IntArray(n) { it * 4 })
        val t0 = System.nanoTime()
        val g = Outlines.forPen(PenType.FOUNTAIN).outline(pts, style(PenType.FOUNTAIN)) as InkGeometry.Fill
        println("outline 100k points: ${(System.nanoTime() - t0) / 1_000_000} ms, ${g.polygon.size / 2} vertices")
        assertThat(g.polygon.all { it.isFinite() }).isTrue()
    }
}

class FountainWidthTest {
    @Test fun pressureMapsToWidth() {
        assertThat(PenWidths.fountain(2f, 0f, 0f)).isWithin(1e-5f).of(0.6f)
        assertThat(PenWidths.fountain(2f, 1f, 0f)).isWithin(1e-5f).of(2f)
    }

    @Test fun speedThins() {
        assertThat(PenWidths.fountain(2f, 1f, 1.5f)).isWithin(1e-5f).of(1.5f)
        assertThat(PenWidths.fountain(2f, 1f, 5f)).isWithin(1e-5f).of(1.5f)
        assertThat(PenWidths.fountain(2f, 1f, 0.75f)).isLessThan(2f)
    }

    @Test fun strokeEndsTaper() {
        val w = PenWidths.forStroke(PenType.FOUNTAIN, line(20, step = 0.01f), 2f)
        assertThat(w[0]).isWithin(1e-4f).of(2f * 0.4f * (1f - 0.25f * (0.01f / 8f / 1.5f)))
        assertThat(w[10]).isGreaterThan(w[0])
        assertThat(w[19]).isLessThan(w[10])
    }

    @Test fun pencilAndBallpointFormulas() {
        assertThat(PenWidths.ballpoint(2f, 0f)).isWithin(1e-5f).of(1.7f)
        assertThat(PenWidths.pencil(2f, 0f)).isWithin(1e-5f).of(1.2f)
        assertThat(PenWidths.pencil(2f, 1f)).isWithin(1e-5f).of(2f)
    }
}

class PolylineGeometryTest {
    @Test fun dashedHasDashArray() {
        val g = Outlines.forPen(PenType.DASHED).outline(line(10), style(PenType.DASHED, 2f)) as InkGeometry.Polyline
        assertThat(g.dash!!.toList()).containsExactly(6f, 4f).inOrder()
        assertThat(g.width).isEqualTo(2f)
    }

    @Test fun finelinerAndHighlighterHaveNoDash() {
        for (pen in listOf(PenType.FINELINER, PenType.HIGHLIGHTER)) {
            val g = Outlines.forPen(pen).outline(line(10), style(pen, 3f)) as InkGeometry.Polyline
            assertThat(g.dash).isNull()
            assertThat(g.points.size).isEqualTo(20)
        }
    }

    @Test fun singlePointStillDraws() {
        val p = StrokePoints(floatArrayOf(1f, 2f), null, null)
        val g = Outlines.forPen(PenType.FINELINER).outline(p, style(PenType.FINELINER)) as InkGeometry.Polyline
        assertThat(g.points.toList()).containsExactly(1f, 2f, 1f, 2f).inOrder()
    }
}

class DotsGeometryTest {
    @Test fun dotsAreSpacedAtTwoAndAHalfWidths() {
        val pts = StrokePoints(floatArrayOf(0f, 0f, 100f, 0f), null, null)
        val g = Outlines.forPen(PenType.DOTTED).outline(pts, style(PenType.DOTTED, 2f)) as InkGeometry.Dots
        assertThat(g.centers[2] - g.centers[0]).isWithin(1e-4f).of(5f)
        assertThat(g.radii.all { it == 1f }).isTrue()
        assertThat(g.radii.size).isEqualTo(g.centers.size / 2)
        assertThat(g.centers[g.centers.size - 2]).isEqualTo(100f)
    }
}
