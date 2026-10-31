package com.alexdremov.notate.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmRejectorTest {
    // 100 px per cm; calibrated so the palm-size rule is active.
    private val calibrator =
        TouchSizeCalibrator(pxPerMm = 10f).also { c -> repeat(TouchSizeCalibrator.SAMPLE_COUNT) { c.add(80f) } }
    private val rejector = PalmRejector(palmMinSizePx = 200f, graceMs = 300L, pxPerCm = 100f, calibrator = calibrator)

    @Test
    fun `fingers work while the pen is away`() {
        assertFalse(rejector.shouldReject(10_000L, 500f, 500f))
    }

    @Test
    fun `pen on the screen rejects fingers anywhere`() {
        rejector.onPenDown(10_000L)
        assertTrue(rejector.shouldReject(10_500L, 0f, 0f))
        assertTrue(rejector.shouldReject(11_500L, 2_000f, 2_000f))
    }

    @Test
    fun `grace after pen up is 300 ms`() {
        rejector.onPenDown(10_000L)
        rejector.onPenUp(10_500L)
        assertTrue(rejector.shouldReject(10_799L, 0f, 0f))
        assertFalse(rejector.shouldReject(10_801L, 0f, 0f))
    }

    @Test
    fun `hover rejects only the wrist zone`() {
        rejector.onHover(9_900L, 1_000f, 1_000f)
        assertTrue(rejector.shouldReject(10_000L, 1_300f, 1_400f)) // right and below the pen: the hand
        assertFalse(rejector.shouldReject(10_000L, 200f, 200f)) // far upper-left: the other hand
        assertFalse(rejector.shouldReject(10_000L, 1_000f, 100f)) // well above the pen
        assertFalse(rejector.shouldReject(10_000L, 2_800f, 1_000f)) // beyond 12 cm
    }

    @Test
    fun `left handed mirrors the wrist zone`() {
        rejector.handedness = Handedness.LEFT
        rejector.onHover(9_900L, 1_000f, 1_000f)
        assertTrue(rejector.shouldReject(10_000L, 700f, 1_400f))
        assertFalse(rejector.shouldReject(10_000L, 1_600f, 1_400f))
    }

    @Test
    fun `hover exit leaves a short grace`() {
        rejector.onHover(9_900L, 1_000f, 1_000f)
        rejector.onHoverExit(10_000L)
        assertTrue(rejector.shouldReject(10_299L, 100f, 100f))
        assertFalse(rejector.shouldReject(10_301L, 1_300f, 1_400f))
    }

    @Test
    fun `reset forgets a cut off stroke and hover`() {
        rejector.onHover(9_900L, 1_000f, 1_000f)
        rejector.onPenDown(10_000L)
        rejector.reset()
        assertFalse(rejector.shouldReject(10_100L, 1_300f, 1_400f))
    }

    @Test
    fun `a stroke whose end was never reported stops blocking after 2 seconds`() {
        rejector.onPenDown(10_000L)
        assertTrue(rejector.shouldReject(11_900L, 0f, 0f))
        assertFalse(rejector.shouldReject(12_100L, 0f, 0f))
    }

    @Test
    fun `a hover whose exit was lost stops blocking fingers`() {
        rejector.onHover(10_000L, 1_000f, 1_000f)
        assertTrue(rejector.shouldReject(11_000L, 1_300f, 1_400f))
        assertFalse(rejector.shouldReject(14_000L, 1_300f, 1_400f))
    }

    @Test
    fun `large contacts are palms, fingertips and unreported sizes are not`() {
        assertTrue(rejector.isPalmSized(floatArrayOf(40f, 260f)))
        assertFalse(rejector.isPalmSized(floatArrayOf(60f)))
        assertFalse(rejector.isPalmSized(floatArrayOf(0f, 0f)))
    }

    @Test
    fun `size rule stays off until the panel is calibrated`() {
        val fresh = PalmRejector(palmMinSizePx = 200f, pxPerCm = 100f)
        assertFalse(fresh.isPalmSized(floatArrayOf(260f)))
    }

    private fun touch(
        action: TouchAction,
        time: Long,
        x: Float = 300f,
        y: Float = 300f,
        major: Float = 60f,
    ) = TouchSample(action, x, y, floatArrayOf(major), time)

    @Test
    fun `a free finger gesture is accepted from down to up`() {
        assertEquals(PalmRejector.Decision.ACCEPT, rejector.onTouch(touch(TouchAction.DOWN, 10_000L)))
        assertEquals(PalmRejector.Decision.ACCEPT, rejector.onTouch(touch(TouchAction.MOVE, 10_050L)))
        assertEquals(PalmRejector.Decision.ACCEPT, rejector.onTouch(touch(TouchAction.UP, 10_100L)))
    }

    @Test
    fun `a gesture that lands while the pen is down is rejected until it ends`() {
        rejector.onPenDown(10_000L)
        assertEquals(PalmRejector.Decision.REJECT, rejector.onTouch(touch(TouchAction.DOWN, 10_100L)))
        rejector.onPenUp(10_200L)
        // Still the same gesture: stays rejected even after the grace period.
        assertEquals(PalmRejector.Decision.REJECT, rejector.onTouch(touch(TouchAction.MOVE, 11_000L)))
        assertEquals(PalmRejector.Decision.REJECT, rejector.onTouch(touch(TouchAction.UP, 11_100L)))
        assertEquals(PalmRejector.Decision.ACCEPT, rejector.onTouch(touch(TouchAction.DOWN, 11_200L)))
    }

    @Test
    fun `a scroll in progress is cancelled when the pen lands`() {
        assertEquals(PalmRejector.Decision.ACCEPT, rejector.onTouch(touch(TouchAction.DOWN, 10_000L)))
        rejector.onPenDown(10_100L)
        assertEquals(PalmRejector.Decision.CANCEL_ONGOING, rejector.onTouch(touch(TouchAction.MOVE, 10_150L)))
        assertEquals(PalmRejector.Decision.REJECT, rejector.onTouch(touch(TouchAction.MOVE, 10_200L)))
    }

    @Test
    fun `two finger tap away from the hovering hand is accepted`() {
        rejector.onHover(9_900L, 1_000f, 1_000f)
        assertEquals(PalmRejector.Decision.ACCEPT, rejector.onTouch(touch(TouchAction.DOWN, 10_000L, x = 200f, y = 200f)))
        assertEquals(PalmRejector.Decision.ACCEPT, rejector.onTouch(touch(TouchAction.POINTER_DOWN, 10_020L, x = 200f, y = 200f)))
        assertEquals(PalmRejector.Decision.ACCEPT, rejector.onTouch(touch(TouchAction.POINTER_UP, 10_080L, x = 200f, y = 200f)))
    }

    @Test
    fun `a palm sized contact is rejected and flagged`() {
        assertEquals(PalmRejector.Decision.REJECT, rejector.onTouch(touch(TouchAction.DOWN, 10_000L, major = 260f)))
        assertTrue(rejector.palmSizedGesture)
        rejector.onTouch(touch(TouchAction.UP, 10_100L, major = 260f))
        rejector.onTouch(touch(TouchAction.DOWN, 10_200L))
        assertFalse(rejector.palmSizedGesture)
    }

    @Test
    fun `the pane can force a rejection or forbid one`() {
        assertEquals(PalmRejector.Decision.REJECT, rejector.onTouch(touch(TouchAction.DOWN, 10_000L), forceReject = true))
        rejector.onTouch(touch(TouchAction.UP, 10_050L), forceReject = true)
        rejector.onPenDown(10_100L)
        assertEquals(PalmRejector.Decision.ACCEPT, rejector.onTouch(touch(TouchAction.DOWN, 10_120L), canReject = false))
    }
}
