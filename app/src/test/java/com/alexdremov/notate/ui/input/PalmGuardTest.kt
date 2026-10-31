package com.alexdremov.notate.ui.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmGuardTest {
    // 100 px per cm; calibrated so the palm-size rule is active.
    private val calibrator =
        TouchSizeCalibrator(pxPerMm = 10f).also { c -> repeat(TouchSizeCalibrator.SAMPLE_COUNT) { c.add(80f) } }
    private val guard = PalmGuard(palmMinSizePx = 200f, graceMs = 300L, pxPerCm = 100f, calibrator = calibrator)

    @Test
    fun `fingers work while the pen is away`() {
        assertFalse(guard.shouldReject(10_000L, 500f, 500f))
    }

    @Test
    fun `pen on the screen rejects fingers anywhere`() {
        guard.onPenDown(10_000L)
        assertTrue(guard.shouldReject(10_500L, 0f, 0f))
        assertTrue(guard.shouldReject(11_500L, 2_000f, 2_000f))
    }

    @Test
    fun `grace after pen up is 300 ms`() {
        guard.onPenDown(10_000L)
        guard.onPenUp(10_500L)
        assertTrue(guard.shouldReject(10_799L, 0f, 0f))
        assertFalse(guard.shouldReject(10_801L, 0f, 0f))
    }

    @Test
    fun `hover rejects only the wrist zone`() {
        guard.onHover(9_900L, 1_000f, 1_000f)
        assertTrue(guard.shouldReject(10_000L, 1_300f, 1_400f)) // right and below the pen: the hand
        assertFalse(guard.shouldReject(10_000L, 200f, 200f)) // far upper-left: the other hand
        assertFalse(guard.shouldReject(10_000L, 1_000f, 100f)) // well above the pen
        assertFalse(guard.shouldReject(10_000L, 2_800f, 1_000f)) // beyond 12 cm
    }

    @Test
    fun `left handed mirrors the wrist zone`() {
        guard.handedness = Handedness.LEFT
        guard.onHover(9_900L, 1_000f, 1_000f)
        assertTrue(guard.shouldReject(10_000L, 700f, 1_400f))
        assertFalse(guard.shouldReject(10_000L, 1_600f, 1_400f))
    }

    @Test
    fun `hover exit leaves a short grace`() {
        guard.onHover(9_900L, 1_000f, 1_000f)
        guard.onHoverExit(10_000L)
        assertTrue(guard.shouldReject(10_299L, 100f, 100f))
        assertFalse(guard.shouldReject(10_301L, 1_300f, 1_400f))
    }

    @Test
    fun `reset forgets a cut off stroke and hover`() {
        guard.onHover(9_900L, 1_000f, 1_000f)
        guard.onPenDown(10_000L)
        guard.reset()
        assertFalse(guard.shouldReject(10_100L, 1_300f, 1_400f))
    }

    @Test
    fun `a stroke whose end was never reported stops blocking after 2 seconds`() {
        guard.onPenDown(10_000L)
        assertTrue(guard.shouldReject(11_900L, 0f, 0f))
        assertFalse(guard.shouldReject(12_100L, 0f, 0f))
    }

    @Test
    fun `a hover whose exit was lost stops blocking fingers`() {
        guard.onHover(10_000L, 1_000f, 1_000f)
        assertTrue(guard.shouldReject(11_000L, 1_300f, 1_400f))
        assertFalse(guard.shouldReject(14_000L, 1_300f, 1_400f))
    }

    @Test
    fun `large contacts are palms, fingertips and unreported sizes are not`() {
        assertTrue(guard.isPalmSized(floatArrayOf(40f, 260f)))
        assertFalse(guard.isPalmSized(floatArrayOf(60f)))
        assertFalse(guard.isPalmSized(floatArrayOf(0f, 0f)))
    }

    @Test
    fun `size rule stays off until the panel is calibrated`() {
        val fresh = PalmGuard(palmMinSizePx = 200f, pxPerCm = 100f)
        assertFalse(fresh.isPalmSized(floatArrayOf(260f)))
    }
}
