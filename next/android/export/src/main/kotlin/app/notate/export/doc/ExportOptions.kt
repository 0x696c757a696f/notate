package app.notate.export.doc

import app.notate.core.doc.export.PageRangeParser
import app.notate.core.geometry.Rect
import app.notate.core.model.Item
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo

enum class ExportFormat { PDF, PNG }

/** Which pages of a document to export. */
sealed interface PageSelection {
    data object All : PageSelection
    data class Current(val page: PageId) : PageSelection
    /** [spec] as typed by the user, e.g. "1-3, 5, 8-" (1-based). */
    data class Pages(val spec: String) : PageSelection
}

class ExportOptions(
    val format: ExportFormat = ExportFormat.PDF,
    val range: PageSelection = PageSelection.All,
    val includeTemplate: Boolean = true,
    val includeLinks: Boolean = true,
)

/** The pages an export covers could not be determined. */
class ExportRangeException(message: String) : Exception(message)

internal object ExportPlan {
    /** Margin around the content of an infinite-canvas page, pt. */
    const val CANVAS_MARGIN_PT = 36f

    /** The pages of [pages] selected by [range], in document order. Throws [ExportRangeException] with a user-readable message. */
    fun select(range: PageSelection, pages: List<PageInfo>): List<PageInfo> = when (range) {
        PageSelection.All -> pages
        is PageSelection.Current -> pages.filter { it.id == range.page }.ifEmpty { throw ExportRangeException("This page no longer exists.") }
        is PageSelection.Pages -> when (val r = PageRangeParser.parse(range.spec, pages.size)) {
            is PageRangeParser.Result.Ok -> r.indexes.map { pages[it] }
            is PageRangeParser.Result.Error -> throw ExportRangeException(r.message)
        }
    }

    /**
     * The part of the page that is exported, page coordinates: the whole page for fixed pages, the content bounds plus
     * [CANVAS_MARGIN_PT] for an infinite canvas (a 3:4 window at the origin while it is empty).
     */
    fun area(page: PageInfo, items: List<Item>): Rect {
        val h = page.height
        if (h != null) return Rect(0f, 0f, page.width, h)
        var bounds = Rect.EMPTY
        for (i in items) bounds = bounds.union(i.bounds)
        return if (bounds.isEmpty) Rect(0f, 0f, page.width, page.width * 4f / 3f) else bounds.inset(-CANVAS_MARGIN_PT)
    }

    /** Drawing order of a page: layer, then creation order. */
    val ITEM_ORDER: Comparator<Item> = compareBy<Item>({ it.layer.code }, { it.order })
}
