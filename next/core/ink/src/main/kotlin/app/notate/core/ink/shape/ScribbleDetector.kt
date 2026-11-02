package app.notate.core.ink.shape

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Detects scribble gestures ("scribble to erase"; ported from the legacy app).
 *
 * A scribble has rapid back-and-forth motion: at least [MIN_REVERSALS] sharp turns (> 140°) on a path longer
 * than [MIN_STROKE_LENGTH], drawn faster than [MIN_AVERAGE_SPEED] per ms. Thresholds are in legacy pixels.
 * Pure function, any thread.
 */
object ScribbleDetector {
    private const val MIN_POINTS = 10
    private const val MIN_REVERSALS = 5
    private const val SHARP_TURN_DEG = 140.0
    private const val MIN_STROKE_LENGTH = 80f
    private const val MIN_AVERAGE_SPEED = 1.0f
    private const val MIN_SPACING = 8f

    /** [xy] interleaved coordinates; [tMs] per-point timestamps or null when unknown (speed check skipped). */
    fun isScribble(xy: FloatArray, tMs: IntArray?): Boolean {
        val n = xy.size / 2
        if (n < MIN_POINTS) return false

        // Drop points closer than MIN_SPACING to the last kept one (noise).
        val kx = ArrayList<Float>()
        val ky = ArrayList<Float>()
        kx.add(xy[0]); ky.add(xy[1])
        for (i in 1 until n) {
            if (hypot(xy[i * 2] - kx.last(), xy[i * 2 + 1] - ky.last()) >= MIN_SPACING) {
                kx.add(xy[i * 2]); ky.add(xy[i * 2 + 1])
            }
        }
        if (kx.size < 4) return false

        var total = 0f
        for (i in 0 until kx.size - 1) total += hypot(kx[i + 1] - kx[i], ky[i + 1] - ky[i])
        if (total < MIN_STROKE_LENGTH) return false

        if (tMs != null && tMs.size == n) {
            val duration = tMs[n - 1] - tMs[0]
            if (duration > 0 && total / duration < MIN_AVERAGE_SPEED) return false
        }

        var reversals = 0
        for (i in 1 until kx.size - 1) {
            val a1 = atan2((ky[i] - ky[i - 1]).toDouble(), (kx[i] - kx[i - 1]).toDouble())
            val a2 = atan2((ky[i + 1] - ky[i]).toDouble(), (kx[i + 1] - kx[i]).toDouble())
            var diff = Math.toDegrees(a1 - a2)
            while (diff < -180) diff += 360
            while (diff > 180) diff -= 360
            if (abs(diff) > SHARP_TURN_DEG) reversals++
        }
        return reversals >= MIN_REVERSALS
    }
}
