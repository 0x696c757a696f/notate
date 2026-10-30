package com.alexdremov.notate.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PenDynamicsTest {
    @Test
    fun `fountain responds to pressure much more than ballpoint`() {
        val fountainRange = PenDynamics.fountainWidthScale(1f) / PenDynamics.fountainWidthScale(0.1f)
        val ballpointRange = PenDynamics.ballpointWidthScale(1f) / PenDynamics.ballpointWidthScale(0.1f)

        assertTrue("fountain light vs hard should differ at least 3x, was $fountainRange", fountainRange >= 3f)
        assertTrue("ballpoint should vary, was $ballpointRange", ballpointRange > 1.3f)
        assertTrue(fountainRange > ballpointRange * 1.5f)
    }

    @Test
    fun `curves are monotonic and clamp out of range pressure`() {
        var prevF = 0f
        var prevB = 0f
        var prevA = 0
        for (i in 0..20) {
            val p = i / 20f
            val f = PenDynamics.fountainWidthScale(p)
            val b = PenDynamics.ballpointWidthScale(p)
            val a = PenDynamics.ballpointAlpha(p)
            assertTrue(f >= prevF && b >= prevB && a >= prevA)
            prevF = f
            prevB = b
            prevA = a
        }
        assertEquals(PenDynamics.fountainWidthScale(1f), PenDynamics.fountainWidthScale(3f), 1e-6f)
        assertEquals(PenDynamics.ballpointWidthScale(0f), PenDynamics.ballpointWidthScale(-1f), 1e-6f)
        assertEquals(255, PenDynamics.ballpointAlpha(1f))
        assertEquals(1f, PenDynamics.ballpointWidthScale(1f), 1e-6f)
    }

    @Test
    fun `fountain taper thins both ends and leaves the middle untouched`() {
        val n = 40
        assertEquals(PenDynamics.FOUNTAIN_TAPER_MIN, PenDynamics.fountainTaper(0, n), 1e-6f)
        assertEquals(PenDynamics.FOUNTAIN_TAPER_MIN, PenDynamics.fountainTaper(n - 1, n), 1e-6f)
        assertEquals(1f, PenDynamics.fountainTaper(n / 2, n), 1e-6f)
        assertTrue(PenDynamics.fountainTaper(2, n) < PenDynamics.fountainTaper(4, n))
        // Tiny strokes are never tapered away.
        assertEquals(1f, PenDynamics.fountainTaper(0, 2), 1e-6f)
    }
}
