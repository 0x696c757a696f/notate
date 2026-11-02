package app.notate.core.workspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SplitGeometryTest {
    private fun ratio(raw: Float) = (SplitGeometry.drop(raw) as DividerDrop.Ratio).value

    @Test fun releasingNearAThirdOrAHalfSnaps() {
        assertThat(ratio(0.36f)).isWithin(0.0001f).of(1f / 3f)
        assertThat(ratio(0.47f)).isEqualTo(0.5f)
        assertThat(ratio(0.70f)).isWithin(0.0001f).of(2f / 3f)
    }

    @Test fun releasingBetweenSnapPointsKeepsThePosition() {
        assertThat(ratio(0.18f)).isEqualTo(0.18f)
        assertThat(ratio(0.58f)).isEqualTo(0.58f)
    }

    @Test fun draggingToAnEdgeClosesThatSide() {
        assertThat(SplitGeometry.drop(0.05f)).isEqualTo(DividerDrop.CloseFirst)
        assertThat(SplitGeometry.drop(0.95f)).isEqualTo(DividerDrop.CloseSecond)
        assertThat(SplitGeometry.drop(0.10f)).isInstanceOf(DividerDrop.Ratio::class.java)
        assertThat(SplitGeometry.drop(0.90f)).isInstanceOf(DividerDrop.Ratio::class.java)
    }
}
