package app.notate.core.ink.sample

import app.notate.core.model.InkPoint
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class StrokeSamplerTest {
    private fun pt(x: Float, y: Float, p: Float = 0.5f, t: Long = 0) = InkPoint(x, y, p, 0f, 0f, t)

    @Test fun duplicatesAreDropped() {
        val s = StrokeSampler()
        assertThat(s.add(pt(0f, 0f, t = 0))).isTrue()
        assertThat(s.add(pt(0f, 0f, t = 5))).isFalse()
        assertThat(s.add(pt(0.05f, 0f, t = 10))).isFalse()
        assertThat(s.add(pt(5f, 0f, t = 15))).isTrue()
        assertThat(s.points().size).isEqualTo(2)
    }

    @Test fun finishKeepsFirstAndLast() {
        val s = StrokeSampler()
        for (i in 0..30) s.add(pt(i * 2f, (i % 3) * 0.01f, t = i * 8L))
        val raw = s.points()
        val fin = s.finish()
        assertThat(fin.xy[0]).isEqualTo(raw.xy[0])
        assertThat(fin.xy[fin.xy.size - 2]).isEqualTo(raw.xy[raw.xy.size - 2])
        assertThat(fin.tMs!![0]).isEqualTo(0)
    }

    @Test fun straightLineOf100PointsSimplifiesToTwo() {
        val s = StrokeSampler()
        for (i in 0 until 100) s.add(pt(i * 3f, 10f, t = i * 8L))
        assertThat(s.points().size).isEqualTo(100)
        assertThat(s.finish().size).isEqualTo(2)
    }

    @Test fun pressureJumpIsKept() {
        val s = StrokeSampler()
        for (i in 0 until 50) s.add(pt(i * 3f, 10f, p = if (i < 25) 0.2f else 0.8f, t = i * 8L))
        val fin = s.finish()
        // first, last and the point where pressure jumps
        assertThat(fin.size).isEqualTo(3)
        assertThat(fin.pressure!![1]).isEqualTo(0.8f)
    }
}

class OneEuroFilterTest {
    @Test fun stillPointConverges() {
        val f = OneEuroFilter2D(1f, 0.007f)
        f.filter(0f, 0f, 0)
        var last = f.filter(10f, 10f, 8)
        for (i in 2..400) last = f.filter(10f, 10f, i * 8L)
        assertThat(abs(last.x - 10f)).isLessThan(0.01f)
        assertThat(abs(last.y - 10f)).isLessThan(0.01f)
    }

    @Test fun stepResponseHasNoOvershoot() {
        val f = OneEuroFilter2D(1f, 0f)
        f.filter(0f, 0f, 0)
        for (i in 1..200) {
            val v = f.filter(10f, 0f, i * 8L)
            assertThat(v.x).isAtMost(10f + 1e-4f)
        }
    }

    @Test fun firstSampleIsUnchanged() {
        val v = OneEuroFilter2D(1f, 0.007f).filter(3f, 4f, 100)
        assertThat(v.x).isEqualTo(3f)
        assertThat(v.y).isEqualTo(4f)
    }
}
