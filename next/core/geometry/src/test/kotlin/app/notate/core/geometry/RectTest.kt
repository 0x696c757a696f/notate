package app.notate.core.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RectTest {
    @Test fun basics() {
        val r = Rect(0f, 0f, 10f, 20f)
        assertThat(r.width).isEqualTo(10f)
        assertThat(r.height).isEqualTo(20f)
        assertThat(r.center).isEqualTo(Vec2(5f, 10f))
        assertThat(Rect.EMPTY.isEmpty).isTrue()
    }

    @Test fun intersectsAndContains() {
        val r = Rect(0f, 0f, 10f, 10f)
        assertThat(r.intersects(Rect(5f, 5f, 15f, 15f))).isTrue()
        assertThat(r.intersects(Rect(10f, 0f, 20f, 10f))).isFalse()
        assertThat(r.contains(10f, 10f)).isTrue()
        assertThat(r.contains(Rect(1f, 1f, 2f, 2f))).isTrue()
        assertThat(r.contains(Rect(1f, 1f, 11f, 2f))).isFalse()
    }

    @Test fun unionInsetOffsetOfPointsAround() {
        assertThat(Rect.EMPTY.union(Rect(1f, 1f, 2f, 2f))).isEqualTo(Rect(1f, 1f, 2f, 2f))
        assertThat(Rect(0f, 0f, 1f, 1f).union(Rect(2f, 2f, 3f, 3f))).isEqualTo(Rect(0f, 0f, 3f, 3f))
        assertThat(Rect(0f, 0f, 10f, 10f).inset(2f)).isEqualTo(Rect(2f, 2f, 8f, 8f))
        assertThat(Rect(0f, 0f, 1f, 1f).offset(5f, 6f)).isEqualTo(Rect(5f, 6f, 6f, 7f))
        assertThat(Rect.ofPoints(floatArrayOf(3f, 4f, -1f, 9f, 2f, 0f))).isEqualTo(Rect(-1f, 0f, 3f, 9f))
        assertThat(Rect.ofPoints(FloatArray(0))).isEqualTo(Rect.EMPTY)
        assertThat(Rect.around(5f, 5f, 2f)).isEqualTo(Rect(3f, 3f, 7f, 7f))
    }
}
