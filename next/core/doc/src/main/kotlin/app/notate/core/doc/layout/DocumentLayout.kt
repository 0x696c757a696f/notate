package app.notate.core.doc.layout

import app.notate.core.geometry.Rect
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PageLayoutMode

/**
 * Arrangement of pages on the "document plane" (pt). Pure computation; any thread.
 *
 * CONTINUOUS: pages stacked vertically, centered horizontally, [gapPt] apart.
 * SINGLE: pages laid out left to right in one strip (top aligned, [gapPt] apart) so neighbours can be pre-rendered;
 * the viewport shows one of them at a time.
 *
 * Pages with `height == null` (whiteboard / web) take their height from [contentHeight].
 */
class DocumentLayout(
    pages: List<PageInfo>,
    val mode: PageLayoutMode,
    val gapPt: Float = 16f,
    contentHeight: (PageInfo) -> Float = { DEFAULT_OPEN_HEIGHT_PT },
) {
    private val ids: List<PageId> = pages.map { it.id }
    private val frames: List<Rect>
    private val index: Map<PageId, Int>

    /** True when any page has no fixed height (whiteboard); such documents are panned freely. */
    val hasUnboundedPage: Boolean = pages.any { it.height == null }

    val totalHeight: Float
    val maxWidth: Float

    init {
        val out = ArrayList<Rect>(pages.size)
        val widest = pages.maxOfOrNull { it.width } ?: 0f
        val tallest = pages.maxOfOrNull { it.height ?: contentHeight(it) } ?: 0f
        var cursor = 0f
        for (p in pages) {
            val h = p.height ?: contentHeight(p)
            if (mode == PageLayoutMode.CONTINUOUS) {
                val x = (widest - p.width) / 2f
                out.add(Rect(x, cursor, x + p.width, cursor + h))
                cursor += h + gapPt
            } else {
                out.add(Rect(cursor, 0f, cursor + p.width, h))
                cursor += p.width + gapPt
            }
        }
        frames = out
        index = ids.withIndex().associate { (i, id) -> id to i }
        if (mode == PageLayoutMode.CONTINUOUS) {
            totalHeight = if (pages.isEmpty()) 0f else cursor - gapPt
            maxWidth = widest
        } else {
            totalHeight = tallest
            maxWidth = if (pages.isEmpty()) 0f else cursor - gapPt
        }
    }

    val pageCount: Int get() = ids.size

    /** Frame of [page] on the document plane; [Rect.EMPTY] for an unknown page. */
    fun frameOf(page: PageId): Rect = index[page]?.let { frames[it] } ?: Rect.EMPTY

    fun frameAt(i: Int): Rect = frames[i]

    fun pageIdAt(i: Int): PageId = ids[i]

    /** Index of [page], or -1 when it is not part of the layout. */
    fun indexOf(page: PageId): Int = index[page] ?: -1

    /** Page whose frame contains the point (gaps belong to no page). */
    fun pageAt(x: Float, y: Float): PageId? {
        for (i in frames.indices) if (frames[i].contains(x, y)) return ids[i]
        return null
    }

    /** Pages whose frame intersects [viewport], in page order. */
    fun visiblePages(viewport: Rect): List<PageId> {
        val out = ArrayList<PageId>()
        for (i in frames.indices) if (frames[i].intersects(viewport)) out.add(ids[i])
        return out
    }

    /** Index of the page whose frame center is nearest to ([x], [y]); -1 for an empty layout. */
    fun nearestIndex(x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in frames.indices) {
            val f = frames[i]
            val dx = if (x < f.l) f.l - x else if (x > f.r) x - f.r else 0f
            val dy = if (y < f.t) f.t - y else if (y > f.b) y - f.b else 0f
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    companion object {
        const val DEFAULT_OPEN_HEIGHT_PT = 1000f
    }
}
