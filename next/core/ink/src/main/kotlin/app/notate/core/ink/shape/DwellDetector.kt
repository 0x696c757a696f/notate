package app.notate.core.ink.shape

import kotlin.math.hypot

/**
 * Pure-logic "hold still" detector for hold-to-shape. The caller feeds every pen point and polls [isDwelling]
 * from its own timer. Main thread only. One instance per stroke (or call [reset]).
 */
class DwellDetector {
    private var xs = FloatArray(64)
    private var ys = FloatArray(64)
    private var ts = LongArray(64)
    private var n = 0

    fun reset() { n = 0 }

    fun onPoint(x: Float, y: Float, tMs: Long) {
        if (n == xs.size) {
            xs = xs.copyOf(n * 2); ys = ys.copyOf(n * 2); ts = ts.copyOf(n * 2)
        }
        xs[n] = x; ys[n] = y; ts[n] = tMs
        n++
    }

    /**
     * True when the pen has been inside a [radiusPt] circle around the latest point for at least [holdMs]:
     * the last point is at least [holdMs] old at [nowMs]... measured from the oldest point of the trailing run
     * that stays within the radius. Needs at least one point.
     */
    fun isDwelling(nowMs: Long, holdMs: Long, radiusPt: Float = 1.5f): Boolean {
        if (n == 0) return false
        val lx = xs[n - 1]
        val ly = ys[n - 1]
        var i = n - 1
        while (i > 0 && hypot(xs[i - 1] - lx, ys[i - 1] - ly) <= radiusPt) i--
        return nowMs - ts[i] >= holdMs
    }

    /** Number of leading points recorded before the current still run began (the part to recognize). */
    fun indexOfStillRunStart(radiusPt: Float = 1.5f): Int {
        if (n == 0) return 0
        val lx = xs[n - 1]
        val ly = ys[n - 1]
        var i = n - 1
        while (i > 0 && hypot(xs[i - 1] - lx, ys[i - 1] - ly) <= radiusPt) i--
        return i
    }
}
