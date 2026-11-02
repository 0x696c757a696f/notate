package app.notate.core.web

/**
 * Splits a scrolled page into screen-sized slices for export (R4-3). Each slice is captured at [Slice.scrollY]; rows
 * `[srcTop, srcTop + height)` of the capture are kept, so the last slice never repeats content when the page cannot scroll a
 * full screen further. Pure.
 */
object WebExportPlan {
    data class Slice(val scrollY: Int, val srcTop: Int, val height: Int)

    fun slices(contentHeightPx: Int, viewportHeightPx: Int, maxSlices: Int = 300): List<Slice> {
        if (viewportHeightPx <= 0) return emptyList()
        val content = contentHeightPx.coerceAtLeast(viewportHeightPx)
        val maxScroll = content - viewportHeightPx
        val out = ArrayList<Slice>()
        var y = 0
        while (y < content && out.size < maxSlices) {
            val scroll = minOf(y, maxScroll)
            val srcTop = y - scroll
            val height = minOf(viewportHeightPx - srcTop, content - y)
            if (height <= 0) break
            out.add(Slice(scroll, srcTop, height))
            y += height
        }
        return out
    }

    /** True when two reports of the content height, taken [intervalMs] apart, count as "settled". */
    fun heightStable(previous: Int, now: Int): Boolean = now > 0 && previous == now
}
