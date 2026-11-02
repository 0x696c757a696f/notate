package app.notate.core.store.outline

import app.notate.core.model.PageId
import app.notate.core.store.OutlineEntry

/** One node of a document outline as a reader (pdfbox, EPUB nav, …) found it; [pageIndex] is 0-based. */
class RawOutlineNode(
    val title: String,
    val pageIndex: Int?,
    val yPt: Float? = null,
    val children: List<RawOutlineNode> = emptyList(),
)

/** Turns reader output into [OutlineEntry] rows. Pure; any thread. */
object OutlineMapper {
    const val MAX_ENTRIES = 5000
    const val MAX_DEPTH = 32

    /**
     * Flattens [nodes] depth-first into rows (ids from 1, `ord` = position among siblings). A node whose [RawOutlineNode.pageIndex]
     * is null or outside [pages] is skipped together with its subtree (its children are reachable only through it);
     * blank titles are skipped the same way. Stops after [MAX_ENTRIES] rows or below [MAX_DEPTH] levels.
     */
    fun map(nodes: List<RawOutlineNode>, pages: List<PageId>): List<OutlineEntry> {
        val out = ArrayList<OutlineEntry>()
        var nextId = 1L
        fun walk(level: List<RawOutlineNode>, parent: Long?, depth: Int) {
            if (depth >= MAX_DEPTH) return
            var ord = 0
            for (n in level) {
                if (out.size >= MAX_ENTRIES) return
                val title = n.title.trim()
                val idx = n.pageIndex
                if (title.isEmpty() || idx == null || idx !in pages.indices) continue
                val id = nextId++
                out += OutlineEntry(id, parent, ord++, title, pages[idx], n.yPt?.takeIf { it.isFinite() && it >= 0f })
                walk(n.children, id, depth + 1)
            }
        }
        walk(nodes, null, 0)
        return out
    }
}
