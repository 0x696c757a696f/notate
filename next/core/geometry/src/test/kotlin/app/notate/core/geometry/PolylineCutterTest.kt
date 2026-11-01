package app.notate.core.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PolylineCutterTest {
    // Horizontal line, 0..100 → t in 0..1 for the single segment... use 11 points so t = x / 10.
    private val line = FloatArray(22) { if (it % 2 == 0) (it / 2) * 10f else 0f }

    @Test fun circleInMiddleGivesTwoPieces() {
        val p = PolylineCutter.cut(line, 0f, floatArrayOf(50f, 0f, 10f))
        assertThat(p).hasSize(2)
        assertThat(p[0].fromT).isEqualTo(0f)
        assertThat(p[0].toT).isWithin(1e-3f).of(4f)
        assertThat(p[1].fromT).isWithin(1e-3f).of(6f)
        assertThat(p[1].toT).isWithin(1e-3f).of(10f)
    }

    @Test fun coveringStartGivesOnePiece() {
        val p = PolylineCutter.cut(line, 0f, floatArrayOf(0f, 0f, 20f))
        assertThat(p).hasSize(1)
        assertThat(p[0].fromT).isWithin(1e-3f).of(2f)
        assertThat(p[0].toT).isEqualTo(10f)
    }

    @Test fun fullCoverageIsEmpty() {
        assertThat(PolylineCutter.cut(line, 0f, floatArrayOf(50f, 0f, 60f))).isEmpty()
    }

    @Test fun disjointCircleKeepsWhole() {
        val p = PolylineCutter.cut(line, 0f, floatArrayOf(50f, 100f, 10f))
        assertThat(p).containsExactly(PolylineCutter.Piece(0f, 10f))
    }

    @Test fun twoCirclesGiveThreePieces() {
        val p = PolylineCutter.cut(line, 0f, floatArrayOf(25f, 0f, 5f, 75f, 0f, 5f))
        assertThat(p).hasSize(3)
        assertThat(p[1].fromT).isWithin(1e-3f).of(3f)
        assertThat(p[1].toT).isWithin(1e-3f).of(7f)
    }

    @Test fun halfWidthWidensCut() {
        val p = PolylineCutter.cut(line, 5f, floatArrayOf(50f, 0f, 5f))
        assertThat(p[0].toT).isWithin(1e-3f).of(4f)
    }

    @Test fun overlappingCirclesMerge() {
        val p = PolylineCutter.cut(line, 0f, floatArrayOf(40f, 0f, 10f, 50f, 0f, 10f))
        assertThat(p).hasSize(2)
        assertThat(p[0].toT).isWithin(1e-3f).of(3f)
        assertThat(p[1].fromT).isWithin(1e-3f).of(6f)
    }

    @Test fun degenerateInputs() {
        assertThat(PolylineCutter.cut(FloatArray(0), 0f, floatArrayOf(0f, 0f, 1f))).isEmpty()
        assertThat(PolylineCutter.cut(floatArrayOf(5f, 5f), 0f, floatArrayOf(0f, 0f, 1f))).hasSize(1)
        assertThat(PolylineCutter.cut(floatArrayOf(5f, 5f), 0f, floatArrayOf(5f, 5f, 1f))).isEmpty()
    }
}
