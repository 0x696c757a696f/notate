package app.notate.core.doc.pages

import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.model.Background
import app.notate.core.model.ImageItem
import app.notate.core.model.Item
import app.notate.core.model.LinkItem
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PageTemplate
import app.notate.core.model.Shape
import app.notate.core.model.Stroke
import app.notate.core.model.TextBox
import app.notate.core.model.edit.Edit

/** Fractional position strictly between [a] and [b] (either may be null = open end); null when no distinct double fits and the pages need renumbering. */
fun ordBetween(a: Double?, b: Double?): Double? = when {
    a == null && b == null -> 1.0
    b == null -> a!! + 1.0
    a == null -> b - 1.0
    else -> ((a + b) / 2.0).takeIf { it > a && it < b && b - a > MIN_ORD_GAP }
}

private const val MIN_ORD_GAP = 1e-9

/**
 * Builds the [Edit]s of page operations; nothing here changes state. Apply the result with [DocumentEditor.apply]
 * (bookmarks with `undoable = false`). Main thread only, because it reads the editor's current pages.
 */
object PageOps {
    /** Message for the UI when [delete] refuses the last page. */
    const val LAST_PAGE_MESSAGE = "A notebook needs at least one page."

    /**
     * Inserts a blank page after [after] (null = at the start). Usually an [Edit.InsertPage]; a [Edit.Batch] of
     * [Edit.MovePage]s plus the insert when the neighbours had no room left between them.
     * Next to a PDF page the new page takes that page's size and [template]'s background.
     */
    fun insertAfter(editor: DocumentEditor, after: PageId?, template: PageTemplate): Edit {
        val pages = editor.pages.value
        val idx = if (after == null) -1 else pages.indexOfFirst { it.id == after }.also { require(it >= 0) { "Unknown page $after" } }
        val neighbour = pages.getOrNull(idx) ?: pages.getOrNull(0)
        val pdfSized = neighbour?.takeIf { it.background is Background.Pdf }
        val (w, h) = if (pdfSized != null) pdfSized.width to pdfSized.height else sizeOf(template)
        val ord = ordBetween(pages.getOrNull(idx)?.ord, pages.getOrNull(idx + 1)?.ord)
        val id = editor.newPageId()
        val now = editor.clock.now()
        fun page(o: Double) = PageInfo(id, o, w, h, template.background, inkRev = 0, modifiedAt = now)
        if (ord != null) return Edit.InsertPage(page(ord), emptyList())
        // No room: renumber everything 1..n+1 with the new page in its slot.
        val order = pages.map { it.id }.toMutableList().also { it.add(idx + 1, id) }
        val moves = renumber(pages, order)
        return Edit.Batch(moves + Edit.InsertPage(page((idx + 2).toDouble()), emptyList()))
    }

    /** Inserts before [before]; same as [insertAfter] with the previous page. */
    fun insertBefore(editor: DocumentEditor, before: PageId, template: PageTemplate): Edit {
        val pages = editor.pages.value
        val i = pages.indexOfFirst { it.id == before }
        require(i >= 0) { "Unknown page $before" }
        return insertAfter(editor, pages.getOrNull(i - 1)?.id, template)
    }

    /** Copy of [page] (content gets new item ids) right after it; an [Edit.Batch] when the pages had to be renumbered. The page must be loaded. */
    fun duplicate(editor: DocumentEditor, page: PageId): Edit {
        val pages = editor.pages.value
        val i = pages.indexOfFirst { it.id == page }
        require(i >= 0) { "Unknown page $page" }
        val src = pages[i]
        val content = checkNotNull(editor.content(page)) { "Page $page is not loaded" }
        val items = content.all().sortedBy { it.order }.map { it.withId(editor.newItemId()) }.toList()
        val id = editor.newPageId()
        fun copy(o: Double) = src.copy(id = id, ord = o, inkRev = 0, modifiedAt = editor.clock.now(), bookmarked = false)
        val ord = ordBetween(src.ord, pages.getOrNull(i + 1)?.ord)
        if (ord != null) return Edit.InsertPage(copy(ord), items)
        val order = pages.map { it.id }.toMutableList().also { it.add(i + 1, id) }
        return Edit.Batch(renumber(pages, order) + Edit.InsertPage(copy((i + 2).toDouble()), items))
    }

    /** Deletes [page] with its items (so Undo can bring it back); null for the last remaining page. The page must be loaded. */
    fun delete(editor: DocumentEditor, page: PageId): Edit.DeletePage? {
        val pages = editor.pages.value
        if (pages.size <= 1) return null
        val info = pages.firstOrNull { it.id == page } ?: throw IllegalArgumentException("Unknown page $page")
        val content = checkNotNull(editor.content(page)) { "Page $page is not loaded" }
        return Edit.DeletePage(info, content.all().sortedBy { it.order }.toList())
    }

    /**
     * Moves [page] so that it ends up at [toIndex] of the page list (0-based, clamped). One [Edit.MovePage], or a [Edit.Batch]
     * that renumbers the pages when the neighbours leave no room; null when the page would not move.
     */
    fun move(editor: DocumentEditor, page: PageId, toIndex: Int): Edit? {
        val pages = editor.pages.value
        val from = pages.indexOfFirst { it.id == page }
        require(from >= 0) { "Unknown page $page" }
        val others = pages.filter { it.id != page }
        val to = toIndex.coerceIn(0, others.size)
        if (to == from) return null
        val ord = ordBetween(others.getOrNull(to - 1)?.ord, others.getOrNull(to)?.ord)
        if (ord != null) return Edit.MovePage(page, pages[from].ord, ord)
        val order = others.map { it.id }.toMutableList().also { it.add(to, page) }
        return Edit.Batch(renumber(pages, order))
    }

    /** New paper for [page] (size stays). */
    fun setTemplate(editor: DocumentEditor, page: PageId, bg: Background.Paper): Edit.SetPage {
        val before = editor.pages.value.firstOrNull { it.id == page } ?: throw IllegalArgumentException("Unknown page $page")
        return Edit.SetPage(before, before.copy(background = bg, modifiedAt = editor.clock.now()))
    }

    /** New paper for every page that has paper (PDF and web pages keep their background); null when there is none. */
    fun setTemplateAll(editor: DocumentEditor, bg: Background.Paper): Edit? {
        val now = editor.clock.now()
        val edits = editor.pages.value.filter { it.background is Background.Paper || it.background is Background.None }
            .map { Edit.SetPage(it, it.copy(background = bg, modifiedAt = now)) }
        return edits.takeIf { it.isNotEmpty() }?.let { Edit.Batch(it) }
    }

    /** Sets or clears the bookmark; apply with `undoable = false`. */
    fun setBookmark(editor: DocumentEditor, page: PageId, on: Boolean): Edit.SetPage {
        val before = editor.pages.value.firstOrNull { it.id == page } ?: throw IllegalArgumentException("Unknown page $page")
        return Edit.SetPage(before, before.copy(bookmarked = on, modifiedAt = editor.clock.now()))
    }

    private fun sizeOf(t: PageTemplate): Pair<Float, Float?> {
        val w = t.size.widthPt
        val h = t.size.heightPt ?: return w to null
        return if (t.landscape) h to w else w to h
    }

    /** [Edit.MovePage]s giving the pages ords 1, 2, 3… in [order]; ids missing from [pages] (a page about to be inserted) are skipped. */
    private fun renumber(pages: List<PageInfo>, order: List<PageId>): List<Edit> {
        val byId = pages.associateBy { it.id }
        val out = ArrayList<Edit>()
        for ((i, id) in order.withIndex()) {
            val p = byId[id] ?: continue
            val ord = (i + 1).toDouble()
            if (p.ord != ord) out.add(Edit.MovePage(id, p.ord, ord))
        }
        return out
    }

    private fun Item.withId(id: app.notate.core.model.ItemId): Item = when (this) {
        is Stroke -> copy(id = id)
        is Shape -> copy(id = id)
        is TextBox -> copy(id = id)
        is ImageItem -> copy(id = id)
        is LinkItem -> copy(id = id)
    }
}
