package app.notate.core.ink.shape

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DwellDetectorTest {
    @Test fun dwellsWhenStillForHoldTime() {
        val d = DwellDetector()
        d.onPoint(0f, 0f, 0)
        d.onPoint(50f, 0f, 100)
        d.onPoint(50.5f, 0.5f, 200)
        d.onPoint(50.2f, 0.1f, 500)
        assertThat(d.isDwelling(nowMs = 700, holdMs = 600)).isTrue()
        assertThat(d.isDwelling(nowMs = 650, holdMs = 600)).isFalse()
        assertThat(d.indexOfStillRunStart()).isEqualTo(1)
    }

    @Test fun movementResets() {
        val d = DwellDetector()
        d.onPoint(0f, 0f, 0)
        d.onPoint(10f, 0f, 500)
        assertThat(d.isDwelling(nowMs = 700, holdMs = 600)).isFalse()
    }

    @Test fun emptyIsNotDwelling() {
        assertThat(DwellDetector().isDwelling(1000, 100)).isFalse()
    }
}

class ScribbleDetectorTest {
    @Test fun zigZagIsScribble() {
        val n = 40
        val xy = FloatArray(2 * n)
        for (i in 0 until n) { xy[2 * i] = if (i % 2 == 0) 0f else 40f; xy[2 * i + 1] = i * 3f }
        assertThat(ScribbleDetector.isScribble(xy, IntArray(n) { it * 20 })).isTrue()
    }

    @Test fun slowZigZagIsNot() {
        val n = 40
        val xy = FloatArray(2 * n)
        for (i in 0 until n) { xy[2 * i] = if (i % 2 == 0) 0f else 40f; xy[2 * i + 1] = i * 3f }
        assertThat(ScribbleDetector.isScribble(xy, IntArray(n) { it * 500 })).isFalse()
    }

    @Test fun straightLineIsNot() {
        val n = 40
        val xy = FloatArray(2 * n) { if (it % 2 == 0) it * 5f else 0f }
        assertThat(ScribbleDetector.isScribble(xy, null)).isFalse()
    }
}
