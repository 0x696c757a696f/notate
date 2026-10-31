package com.alexdremov.notate.ui.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchSizeCalibratorTest {
    private fun calibrate(sizePx: Float): TouchSizeCalibrator {
        val c = TouchSizeCalibrator(pxPerMm = 10f)
        repeat(TouchSizeCalibrator.SAMPLE_COUNT) { c.add(sizePx) }
        return c
    }

    @Test
    fun `not enabled before enough samples`() {
        val c = TouchSizeCalibrator(pxPerMm = 10f)
        repeat(TouchSizeCalibrator.SAMPLE_COUNT - 1) { c.add(80f) }
        assertFalse(c.calibrated)
        assertFalse(c.enabled)
    }

    @Test
    fun `median of 8 mm enables the size rule`() {
        val c = calibrate(80f)
        assertTrue(c.calibrated)
        assertTrue(c.enabled)
    }

    @Test
    fun `panel that reports zero disables it`() {
        val c = calibrate(0f)
        assertTrue(c.calibrated)
        assertFalse(c.enabled)
    }

    @Test
    fun `panel that reports 200 mm disables it`() {
        assertFalse(calibrate(2_000f).enabled)
    }
}
