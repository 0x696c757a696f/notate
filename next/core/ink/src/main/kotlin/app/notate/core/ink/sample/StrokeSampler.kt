package app.notate.core.ink.sample

import app.notate.core.geometry.Geo
import app.notate.core.model.InkPoint
import app.notate.core.model.StrokePoints
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns raw pen samples into stored stroke points: 1€-filters x/y, drops points closer than [minDistPt] to the
 * previous kept point, and simplifies on [finish]. Main thread only. One instance per stroke.
 */
class StrokeSampler(
    private val minDistPt: Float = 0.15f,
    private val filter: OneEuroFilter2D = OneEuroFilter2D(MIN_CUTOFF_HZ, BETA, D_CUTOFF_HZ),
) {
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastRawT = 0L
    private var xy = FloatArray(256)
    private var pressure = FloatArray(128)
    private var tMs = IntArray(128)
    private var n = 0
    private var t0 = 0L

    /** Adds a sample; returns false when it was dropped as a duplicate. */
    fun add(p: InkPoint): Boolean {
        lastRawX = p.x; lastRawY = p.y; lastRawT = p.tMs
        val f = filter.filter(p.x, p.y, p.tMs)
        if (n == 0) t0 = p.tMs
        else if (hypot(f.x - xy[2 * n - 2], f.y - xy[2 * n - 1]) < minDistPt) return false
        if (n == pressure.size) {
            pressure = pressure.copyOf(n * 2); tMs = tMs.copyOf(n * 2); xy = xy.copyOf(n * 4)
        }
        xy[2 * n] = f.x; xy[2 * n + 1] = f.y
        pressure[n] = p.pressure.coerceIn(0f, 1f)
        tMs[n] = (p.tMs - t0).toInt()
        n++
        return true
    }

    /** Current points, unsimplified (live preview). The arrays are copies. */
    fun points(): StrokePoints = StrokePoints(xy.copyOf(2 * n), pressure.copyOf(n), tMs.copyOf(n))

    /**
     * Ramer–Douglas–Peucker (ε = 0.05 pt) simplified points. First and last points are kept, and so is any point
     * whose pressure differs from the previous one by more than 0.2.
     */
    fun finish(): StrokePoints {
        if (n == 0) return points()
        // The filter trails the pen, so the stroke would end short of where the pen lifted: end exactly on the last raw sample.
        if (n > 1 && hypot(lastRawX - xy[2 * n - 2], lastRawY - xy[2 * n - 1]) > 0f) {
            if (2 * n + 2 > xy.size) { pressure = pressure.copyOf(n * 2); tMs = tMs.copyOf(n * 2); xy = xy.copyOf(n * 4) }
            xy[2 * n] = lastRawX; xy[2 * n + 1] = lastRawY
            pressure[n] = pressure[n - 1]; tMs[n] = (lastRawT - t0).toInt()
            n++
        }
        if (n <= 2) return points()
        val xs = xy.copyOf(2 * n)
        val keep = BooleanArray(n)
        for (i in 1 until n) if (abs(pressure[i] - pressure[i - 1]) > PRESSURE_JUMP) keep[i] = true
        val idx = Geo.rdp(xs, EPSILON_PT, keep)
        return StrokePoints(
            FloatArray(idx.size * 2) { xs[2 * idx[it / 2] + it % 2] },
            FloatArray(idx.size) { pressure[idx[it]] },
            IntArray(idx.size) { tMs[idx[it]] },
        )
    }

    private companion object {
        // Light smoothing: only digitizer jitter goes. The old 1 Hz / 0.007 setting lagged the pen by several mm, rounded
        // corners, shrank small loops and ended strokes short, so ink changed shape when the live driver ink was replaced.
        const val MIN_CUTOFF_HZ = 25f
        const val BETA = 0.1f
        const val D_CUTOFF_HZ = 10f
        const val EPSILON_PT = 0.05f
        const val PRESSURE_JUMP = 0.2f
    }
}
