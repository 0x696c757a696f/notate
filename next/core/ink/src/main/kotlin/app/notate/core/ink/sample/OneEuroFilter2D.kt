package app.notate.core.ink.sample

import app.notate.core.geometry.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 1€ filter (Casiez et al.) on a 2D point: low jitter when slow, low lag when fast. Speeds are in pt/s.
 * Main thread only. One instance per stroke.
 */
class OneEuroFilter2D(
    private val minCutoff: Float,
    private val beta: Float,
    private val dCutoff: Float = 1f,
) {
    private var started = false
    private var lastT = 0L
    private var xHat = 0f
    private var yHat = 0f
    private var dxHat = 0f
    private var dyHat = 0f

    /** Filters the next sample; the first sample is returned unchanged. [tMs] must be non-decreasing. */
    fun filter(x: Float, y: Float, tMs: Long): Vec2 {
        if (!started) {
            started = true
            lastT = tMs; xHat = x; yHat = y
            return Vec2(x, y)
        }
        val dt = max((tMs - lastT) / 1000f, 0.001f)
        lastT = tMs
        val aD = alpha(dCutoff, dt)
        dxHat += aD * ((x - xHat) / dt - dxHat)
        dyHat += aD * ((y - yHat) / dt - dyHat)
        val cutoff = minCutoff + beta * hypot(dxHat, dyHat)
        val a = alpha(cutoff, dt)
        xHat += a * (x - xHat)
        yHat += a * (y - yHat)
        return Vec2(xHat, yHat)
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * max(abs(cutoff), 1e-3f))
        return 1f / (1f + tau / dt)
    }
}
