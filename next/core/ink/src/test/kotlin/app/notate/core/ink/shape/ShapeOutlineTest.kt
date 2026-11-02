package app.notate.core.ink.shape

import app.notate.core.model.ShapeKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShapeOutlineTest {
    @Test fun lineIsOnePolyline() {
        assertThat(ShapeOutline.of(ShapeKind.LINE, floatArrayOf(0f, 0f, 10f, 0f), 2f)).hasSize(1)
    }

    @Test fun arrowHasShaftAndTwoWings() {
        val g = ShapeOutline.of(ShapeKind.ARROW, floatArrayOf(0f, 0f, 100f, 0f), 2f)
        assertThat(g).hasSize(3)
        // Wings end at the tip, start behind it, 3w+4 = 10 pt long, 25° off the shaft.
        for (wing in g.drop(1)) {
            assertThat(wing.points[2]).isEqualTo(100f)
            assertThat(wing.points[0]).isWithin(0.01f).of(100f - (10f * Math.cos(Math.toRadians(25.0))).toFloat())
        }
        assertThat(ShapeOutline.of(ShapeKind.DOUBLE_ARROW, floatArrayOf(0f, 0f, 100f, 0f), 2f)).hasSize(5)
    }

    @Test fun closedShapesRepeatFirstPoint() {
        val r = ShapeOutline.of(ShapeKind.RECT, floatArrayOf(0f, 0f, 10f, 0f, 10f, 5f, 0f, 5f), 1f).single().points
        assertThat(r.size).isEqualTo(10)
        assertThat(r.take(2)).isEqualTo(r.takeLast(2))
        val t = ShapeOutline.of(ShapeKind.TRIANGLE, floatArrayOf(0f, 0f, 10f, 0f, 5f, 5f), 1f).single().points
        assertThat(t.size).isEqualTo(8)
    }

    @Test fun polylineStaysOpen() {
        assertThat(ShapeOutline.of(ShapeKind.POLYLINE, floatArrayOf(0f, 0f, 1f, 1f, 2f, 0f), 1f).single().points.size).isEqualTo(6)
    }

    @Test fun ellipseIsClosedWith64Segments() {
        val p = ShapeOutline.of(ShapeKind.ELLIPSE, floatArrayOf(50f, 50f, 20f, 10f, 0f), 1f).single().points
        assertThat(p.size).isEqualTo(65 * 2)
        assertThat(p[0]).isWithin(1e-3f).of(p[p.size - 2])
        assertThat(p[1]).isWithin(1e-3f).of(p[p.size - 1])
        assertThat(p[0]).isWithin(1e-3f).of(70f)
    }

    @Test fun rotatedEllipseKeepsCenterExtent() {
        val p = ShapeOutline.of(ShapeKind.ELLIPSE, floatArrayOf(0f, 0f, 20f, 10f, 90f), 1f).single().points
        val ys = p.filterIndexed { i, _ -> i % 2 == 1 }
        assertThat(ys.max()).isWithin(0.01f).of(20f)
    }

    @Test fun q1AxesTickCountIsLengthOverTick() {
        val g = ShapeOutline.of(ShapeKind.AXES_Q1, floatArrayOf(0f, 100f, 100f, 60f, 0f, 10f), 1f)
        // 2 arrows × 3 polylines, then ticks: x has 9 (10..90), y has 5 (10..50)
        assertThat(g).hasSize(6 + 9 + 5)
        // Arrow tips: x axis to the right, y axis up (y decreases).
        assertThat(g[0].points[2]).isEqualTo(100f)
        assertThat(g[3].points[3]).isWithin(1e-3f).of(40f)
    }

    @Test fun cross2dHasTicksOnBothSides() {
        val g = ShapeOutline.of(ShapeKind.AXES_2D, floatArrayOf(0f, 0f, 100f, 100f, 0f, 10f), 1f)
        // each half-axis is 50 long → 4 ticks per half; 4 halves
        assertThat(g).hasSize(6 + 16)
    }

    @Test fun zeroTickMeansNoTicks() {
        assertThat(ShapeOutline.of(ShapeKind.AXES_2D, floatArrayOf(0f, 0f, 100f, 100f, 0f, 0f), 1f)).hasSize(6)
    }
}
