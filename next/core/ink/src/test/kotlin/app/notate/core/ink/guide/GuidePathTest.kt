package app.notate.core.ink.guide

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot

class GuidePathTest {
    @Test fun lineSpansLowestToHighestProjection() {
        val g = GuideSnap.Line(0f, 0f, 1f, 0f)
        val out = GuidePath.build(g, floatArrayOf(10f, 5f, 80f, -7f, 40f, 3f))
        assertThat(out.toList()).containsExactly(10f, 0f, 80f, 0f).inOrder()
    }

    @Test fun singlePointGivesOnePoint() {
        assertThat(GuidePath.build(GuideSnap.Line(0f, 0f, 1f, 0f), floatArrayOf(4f, 9f)).size).isEqualTo(2)
    }

    @Test fun arcKeepsRadiusAndFollowsTheSweep() {
        val g = GuideSnap.Arc(0f, 0f, 100f)
        // Quarter circle drawn roughly: (100,0) → (0,100) via 45°.
        val out = GuidePath.build(g, floatArrayOf(102f, 3f, 72f, 70f, 2f, 98f))
        val n = out.size / 2
        for (i in 0 until n) assertThat(hypot(out[2 * i], out[2 * i + 1])).isWithin(1e-2f).of(100f)
        assertThat(out[0]).isWithin(0.5f).of(100f)
        assertThat(out[2 * n - 2]).isWithin(3f).of(0f)
        assertThat(out[2 * n - 1]).isWithin(0.5f).of(100f)
        assertThat(n).isAtLeast(40)        // 90° at 2° steps
    }

    @Test fun arcGoingBackwardsSweepsTheOtherWay() {
        val out = GuidePath.build(GuideSnap.Arc(0f, 0f, 10f), floatArrayOf(0f, 10f, 10f, 0f))   // 90° → 0°
        assertThat(out[out.size - 2]).isWithin(1e-2f).of(10f)
        assertThat(out[out.size - 1]).isWithin(1e-2f).of(0f)
    }

    @Test fun rayIsASegmentFromTheCentreAtAWholeDegree() {
        val g = GuideSnap.Ray(50f, 50f, 0f)
        val out = GuidePath.build(g, floatArrayOf(51f, 50f, 100f, 50.2f, 150f, 50.4f))
        assertThat(out.size).isEqualTo(4)
        assertThat(out[0]).isEqualTo(50f)
        assertThat(out[1]).isEqualTo(50f)
        assertThat(out[3]).isWithin(1e-3f).of(50f)     // last point is 0.23° off the base → rounds to 0°
        assertThat(hypot(out[2] - 50f, out[3] - 50f)).isWithin(0.5f).of(100f)
    }
}
