package app.notate.core.doc.tools

import app.notate.core.geometry.Rect
import app.notate.core.ink.shape.ScribbleDetector
import app.notate.core.model.Item
import app.notate.core.model.ToolPreset
import kotlin.math.hypot

/**
 * Scribble-to-erase: decides whether a finished pen stroke is a scribble-out and which items it covers. Pure.
 *
 * Being a scribble ([ScribbleDetector]) is not enough, because writing has back-and-forth passes too (m, w, hatching inside an
 * outline). The stroke only erases when most of its passes cross an item in their *middle*, away from the turns: hatching inside a
 * shape meets the outline only where it turns back, so it stays ink. Idea from the MIT-licensed xnotes-android (`Scribble.kt`).
 */
internal object ScribbleEraser {
    /** Share of passes that must cross something (strictly more than half). */
    private const val MIN_CROSSING_SHARE = 0.5f

    /** Length at each end of a pass that does not count, in screen px (turns happen there). */
    private const val TURN_ZONE_PX = 10f

    /** Items covered by the scribble [xy] ([tMs] optional), or empty when it is not a scribble-out. [pxPerPt] converts screen px. */
    fun targets(xy: FloatArray, tMs: IntArray?, candidates: (Rect) -> Iterable<Item>, preset: ToolPreset, pxPerPt: Float): List<Item> {
        val sc = ScribbleDetector.analyze(xy, tMs) ?: return emptyList()
        val p = sc.xy
        val n = p.size / 2
        val bounds = bounds(p)
        val items = candidates(bounds).filter { EraserGeometry.erasable(it, preset) }
        if (items.isEmpty()) return emptyList()

        val trim = TURN_ZONE_PX / pxPerPt
        val cuts = intArrayOf(0) + sc.turns + intArrayOf(n - 1)
        val hit = LinkedHashMap<Long, Item>()
        var passes = 0
        var crossing = 0
        for (k in 0 until cuts.size - 1) {
            val a = cuts[k]; val b = cuts[k + 1]
            if (b <= a) continue
            passes++
            var len = 0f
            for (i in a until b) len += hypot(p[2 * i + 2] - p[2 * i], p[2 * i + 3] - p[2 * i + 1])
            var run = 0f
            var crosses = false
            for (i in a until b) {
                val seg = hypot(p[2 * i + 2] - p[2 * i], p[2 * i + 3] - p[2 * i + 1])
                if (run >= trim && run + seg <= len - trim) {
                    for (it in items) {
                        if (EraserGeometry.hit(it, p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3], 0f)) { hit[it.id.value] = it; crosses = true }
                    }
                }
                run += seg
            }
            if (crosses) crossing++
        }
        return if (passes > 0 && crossing.toFloat() / passes > MIN_CROSSING_SHARE) hit.values.toList() else emptyList()
    }

    private fun bounds(p: FloatArray): Rect {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until p.size / 2) { l = minOf(l, p[2 * i]); r = maxOf(r, p[2 * i]); t = minOf(t, p[2 * i + 1]); b = maxOf(b, p[2 * i + 1]) }
        return Rect(l, t, r, b)
    }
}
