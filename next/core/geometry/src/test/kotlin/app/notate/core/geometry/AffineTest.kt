package app.notate.core.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AffineTest {
    private val tol = 1e-4f

    @Test fun composeAppliesRightOperandFirst() {
        val m = Affine.translate(10f, 0f) * Affine.scale(2f, 2f)
        val p = m.map(1f, 1f)
        assertThat(p.x).isWithin(tol).of(12f)
        assertThat(p.y).isWithin(tol).of(2f)
    }

    @Test fun identityIsNeutral() {
        val m = Affine.rotate(30f, 5f, 5f)
        assertThat(m * Affine.IDENTITY).isEqualTo(m)
        assertThat(Affine.IDENTITY * m).isEqualTo(m)
    }

    @Test fun invertRoundTrips() {
        val m = Affine.translate(3f, -4f) * Affine.rotate(37f, 1f, 2f) * Affine.scale(2f, 0.5f)
        val inv = m.invert()!!
        val p = inv.map(m.map(7f, 9f).x, m.map(7f, 9f).y)
        assertThat(p.x).isWithin(1e-3f).of(7f)
        assertThat(p.y).isWithin(1e-3f).of(9f)
    }

    @Test fun singularInvertReturnsNull() {
        assertThat(Affine.scale(0f, 1f).invert()).isNull()
        assertThat(Affine(1f, 2f, 2f, 4f, 0f, 0f).invert()).isNull()
    }

    @Test fun mapRectRotated90() {
        val r = Affine.rotate(90f, 50f, 50f).mapRect(Rect(0f, 40f, 100f, 60f))
        assertThat(r.l).isWithin(tol).of(40f)
        assertThat(r.r).isWithin(tol).of(60f)
        assertThat(r.t).isWithin(tol).of(0f)
        assertThat(r.b).isWithin(tol).of(100f)
    }

    @Test fun scaleXIgnoresRotationAndTranslation() {
        assertThat((Affine.rotate(45f) * Affine.scale(3f, 3f)).scaleX).isWithin(tol).of(3f)
        assertThat(Affine.translate(100f, 100f).scaleX).isWithin(tol).of(1f)
    }

    @Test fun scaleAroundPivotKeepsPivot() {
        val p = Affine.scale(2f, 2f, 10f, 10f).map(10f, 10f)
        assertThat(p).isEqualTo(Vec2(10f, 10f))
    }

    @Test fun rotationDegAndMapPoints() {
        assertThat(Affine.rotate(30f).rotationDeg).isWithin(1e-3f).of(30f)
        val out = Affine.translate(1f, 2f).mapPoints(floatArrayOf(0f, 0f, 1f, 1f))
        assertThat(out.toList()).containsExactly(1f, 2f, 2f, 3f).inOrder()
    }
}
