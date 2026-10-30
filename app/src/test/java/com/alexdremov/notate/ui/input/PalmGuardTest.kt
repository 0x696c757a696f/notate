package com.alexdremov.notate.ui.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmGuardTest {
    private val guard = PalmGuard(palmMinSizePx = 200f, graceMs = 500L)

    @Test
    fun `fingers work while the pen is away`() {
        assertFalse(guard.isPenNear(10_000L))
    }

    @Test
    fun `hovering pen blocks fingers until shortly after it leaves`() {
        guard.onHover(9_900L)
        assertTrue(guard.isPenNear(10_000L))

        guard.onHoverExit(10_000L)
        assertTrue(guard.isPenNear(10_400L))
        assertFalse(guard.isPenNear(10_600L))
    }

    @Test
    fun `a long stroke keeps fingers blocked after hover ends`() {
        guard.onHover(9_900L)
        guard.onHoverExit(10_000L)
        guard.onPenDown(10_000L)
        assertTrue(guard.isPenNear(13_000L))

        guard.onPenUp(13_000L)
        assertTrue(guard.isPenNear(13_300L))
        assertFalse(guard.isPenNear(13_600L))
    }

    @Test
    fun `a stroke whose end was never reported stops blocking fingers`() {
        guard.onPenDown(10_000L)
        assertFalse(guard.isPenNear(60_000L))
    }

    @Test
    fun `a hover whose exit was lost stops blocking fingers`() {
        guard.onHover(10_000L)
        assertTrue(guard.isPenNear(11_000L))
        assertFalse(guard.isPenNear(14_000L))
    }

    @Test
    fun `large contacts are palms, fingertips and unreported sizes are not`() {
        assertTrue(guard.isPalmSized(floatArrayOf(40f, 260f)))
        assertFalse(guard.isPalmSized(floatArrayOf(60f)))
        assertFalse(guard.isPalmSized(floatArrayOf(0f, 0f)))
    }
}
