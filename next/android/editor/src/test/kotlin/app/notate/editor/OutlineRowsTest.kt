package app.notate.editor

import app.notate.core.model.Background
import app.notate.core.model.AssetId
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.store.OutlineEntry
import app.notate.editor.outline.OutlineRow
import app.notate.editor.outline.OutlineRows
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutlineRowsTest {
    private fun page(n: Long, marked: Boolean = false) =
        PageInfo(PageId(n), n.toDouble(), 600f, 800f, Background.Pdf(AssetId("a"), (n - 1).toInt()), 0, 0, bookmarked = marked)

    private val pages = (1L..4L).map { page(it, marked = it == 3L) }
    private fun label(n: Int) = "Page $n"

    @Test fun indentsChildrenAndAddsHeaders() {
        val outline = listOf(
            OutlineEntry(1, null, 0, "Chapter 1", PageId(1), 100f),
            OutlineEntry(2, 1, 0, "1.1", PageId(2), null),
            OutlineEntry(3, 2, 0, "1.1.1", PageId(2), null),
            OutlineEntry(4, null, 1, "Chapter 2", PageId(4), null),
        )
        val rows = OutlineRows.build(outline, pages, ::label)
        assertThat(rows.first()).isEqualTo(OutlineRow.Header(R.string.outline_title))
        val entries = rows.filterIsInstance<OutlineRow.Entry>()
        assertThat(entries.map { it.title }).containsExactly("Chapter 1", "1.1", "1.1.1", "Chapter 2", "Page 3").inOrder()
        assertThat(entries.map { it.depth }).containsExactly(0, 1, 2, 0, 0).inOrder()
        assertThat(entries[0].yPt).isEqualTo(100f)
        assertThat(entries[3].pageNumber).isEqualTo(4)
        assertThat(rows).contains(OutlineRow.Header(R.string.outline_bookmarks))
    }

    @Test fun bookmarksOnlyForANotebookWithoutOutline() {
        val rows = OutlineRows.build(emptyList(), pages, ::label)
        assertThat(rows.map { it::class }).containsExactly(OutlineRow.Header::class, OutlineRow.Entry::class).inOrder()
    }

    @Test fun nothingToShowGivesNoRows() {
        assertThat(OutlineRows.build(emptyList(), pages.map { it.copy(bookmarked = false) }, ::label)).isEmpty()
    }

    @Test fun skipsEntriesWhosePageWasDeleted() {
        val outline = listOf(OutlineEntry(1, null, 0, "Gone", PageId(99), null), OutlineEntry(2, null, 1, "Here", PageId(1), null))
        val titles = OutlineRows.build(outline, pages.map { it.copy(bookmarked = false) }, ::label).filterIsInstance<OutlineRow.Entry>().map { it.title }
        assertThat(titles).containsExactly("Here")
    }
}
