package com.alexdremov.notate.ui.input

import com.alexdremov.notate.util.Logger

/**
 * Learns whether the panel reports usable contact sizes. A panel that always reports 0 (or a huge
 * constant) would make every finger look like a palm, or none of them: the size rule only switches on
 * once the median of the first [sampleCount] finger contacts is a plausible fingertip size.
 */
class TouchSizeCalibrator(
    private val pxPerMm: Float,
    private val sampleCount: Int = SAMPLE_COUNT,
) {
    companion object {
        const val SAMPLE_COUNT = 20
        const val MIN_FINGER_MM = 3f
        const val MAX_FINGER_MM = 15f
        private const val TAG = "TouchSizeCalibrator"
    }

    private val samplesMm = ArrayList<Float>(sampleCount)

    @Volatile
    var enabled = false
        private set

    @Volatile
    var calibrated = false
        private set

    /** One single-finger DOWN: its contact size in pixels (0 if the panel doesn't report it). */
    @Synchronized
    fun add(touchMajorPx: Float) {
        if (calibrated) return
        samplesMm.add(if (pxPerMm > 0f) touchMajorPx / pxPerMm else 0f)
        if (samplesMm.size < sampleCount) return
        val sorted = samplesMm.sorted()
        val median = (sorted[sorted.size / 2] + sorted[(sorted.size - 1) / 2]) / 2f
        enabled = median in MIN_FINGER_MM..MAX_FINGER_MM
        calibrated = true
        if (!enabled) Logger.d(TAG, "Palm-size rule disabled: median finger contact is $median mm")
    }
}
