package app.notate.core.ink.outline

import app.notate.core.model.PenType
import app.notate.core.model.StrokePoints
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/** Per-point stroke width (diameter, pt) formulas of the variable-width pens (see plans/rewrite/03 §3). */
internal object PenWidths {
    /** Pressure assumed for input that carries none (finger, mouse). */
    const val DEFAULT_PRESSURE = 0.5f

    private val FOUNTAIN_TAPER = floatArrayOf(0.4f, 0.7f, 0.9f)

    fun ballpoint(w: Float, p: Float) = w * (0.85f + 0.15f * p)
    fun pencil(w: Float, p: Float) = w * (0.6f + 0.4f * p)
    fun fountain(w: Float, p: Float, v: Float) = w * (0.3f + 0.7f * p.pow(0.8f)) * (1f - 0.25f * (v / 1.5f).coerceIn(0f, 1f))

    /** Diameter at every point of [pts] for [pen] (BALLPOINT, FOUNTAIN or PENCIL). */
    fun forStroke(pen: PenType, pts: StrokePoints, w: Float): FloatArray {
        val n = pts.size
        val pr = pts.pressure
        fun p(i: Int) = (pr?.get(i) ?: DEFAULT_PRESSURE).coerceIn(0f, 1f)
        return when (pen) {
            PenType.BALLPOINT -> FloatArray(n) { ballpoint(w, p(it)) }
            PenType.PENCIL -> FloatArray(n) { pencil(w, p(it)) }
            PenType.FOUNTAIN -> {
                val v = speeds(pts)
                FloatArray(n) {
                    val taper = min(taperAt(it), taperAt(n - 1 - it))
                    fountain(w, p(it), v[it]) * taper
                }
            }
            else -> throw IllegalArgumentException("Not a variable-width pen: $pen")
        }
    }

    private fun taperAt(distFromEnd: Int) = if (distFromEnd < FOUNTAIN_TAPER.size) FOUNTAIN_TAPER[distFromEnd] else 1f

    /** Speed in pt/ms per point: neighbour distance / max(dt, 1), 3-point moving average. Zero without timestamps. */
    private fun speeds(pts: StrokePoints): FloatArray {
        val n = pts.size
        val t = pts.tMs ?: return FloatArray(n)
        val raw = FloatArray(n)
        for (i in 1 until n) {
            val d = hypot(pts.xy[2 * i] - pts.xy[2 * i - 2], pts.xy[2 * i + 1] - pts.xy[2 * i - 1])
            raw[i] = d / maxOf(t[i] - t[i - 1], 1)
        }
        if (n > 1) raw[0] = raw[1]
        return FloatArray(n) { i ->
            val a = maxOf(i - 1, 0)
            val b = min(i + 1, n - 1)
            var s = 0f
            for (k in a..b) s += raw[k]
            s / (b - a + 1)
        }
    }
}
