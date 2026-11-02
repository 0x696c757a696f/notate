package app.notate.core.doc.pages

import app.notate.core.doc.edit.IdAllocator
import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.doc.save.SaveQueue
import app.notate.core.model.Background
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PageTemplate
import app.notate.core.model.PaperPattern
import app.notate.core.model.edit.Edit
import app.notate.core.store.MaxIds
import app.notate.core.testing.FakeClock
import app.notate.core.testing.FakeDocStore
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageOpsTest {
    private val tpl = PageTemplate.DEFAULT

    private fun TestScope.editor(pages: List<PageInfo>): DocumentEditor {
        val d = StandardTestDispatcher(testScheduler)
        val store = FakeDocStore(meta(), pages)
        val save = SaveQueue(store, d, FakeClock())
        return DocumentEditor(meta(), pages, store, save, IdAllocator(MaxIds(pages.size.toLong(), 100, emptyMap())), FakeClock(), d, d)
    }

    private fun DocumentEditor.ids() = pages.value.map { it.id.value }

    @Test fun ordBetweenCases() {
        assertThat(ordBetween(null, null)).isEqualTo(1.0)
        assertThat(ordBetween(3.0, null)).isEqualTo(4.0)
        assertThat(ordBetween(null, 3.0)).isEqualTo(2.0)
        assertThat(ordBetween(1.0, 2.0)).isEqualTo(1.5)
        assertThat(ordBetween(1.0, Math.nextUp(1.0))).isNull()
        assertThat(ordBetween(1.0, 1.0 + 1e-12)).isNull()
    }

    @Test fun insertAtStartMiddleEnd() = runTest {
        val e = editor(listOf(page(1), page(2)))
        e.apply(PageOps.insertAfter(e, null, tpl)); assertThat(e.ids()).containsExactly(3L, 1L, 2L).inOrder()
        e.apply(PageOps.insertAfter(e, PageId(1), tpl)); assertThat(e.ids()).containsExactly(3L, 1L, 4L, 2L).inOrder()
        e.apply(PageOps.insertAfter(e, PageId(2), tpl)); assertThat(e.ids()).containsExactly(3L, 1L, 4L, 2L, 5L).inOrder()
        val added = e.pages.value.first { it.id.value == 5L }
        assertThat(added.width).isEqualTo(tpl.size.widthPt)
        assertThat(added.height).isEqualTo(tpl.size.heightPt)
        assertThat(added.background).isEqualTo(tpl.background)
    }

    @Test fun insertNextToPdfUsesPdfSize() = runTest {
        val pdf = page(1).copy(width = 300f, height = 400f, background = Background.Pdf(app.notate.core.model.AssetId("a"), 0))
        val e = editor(listOf(pdf, page(2)))
        e.apply(PageOps.insertAfter(e, PageId(1), tpl))
        val p = e.pages.value[1]
        assertThat(p.width).isEqualTo(300f)
        assertThat(p.height).isEqualTo(400f)
        assertThat(p.background).isEqualTo(tpl.background)
    }

    @Test fun insertWithoutRoomRenumbers() = runTest {
        val a = 1.0
        val b = Math.nextUp(a)
        val e = editor(listOf(page(1, ord = a), page(2, ord = b)))
        val edit = PageOps.insertAfter(e, PageId(1), tpl)
        assertThat(edit).isInstanceOf(Edit.Batch::class.java)
        e.apply(edit)
        assertThat(e.ids()).containsExactly(1L, 3L, 2L).inOrder()
        assertThat(e.pages.value.map { it.ord }).containsExactly(1.0, 2.0, 3.0).inOrder()
        e.undo()
        assertThat(e.ids()).containsExactly(1L, 2L).inOrder()
        assertThat(e.pages.value.map { it.ord }).containsExactly(a, b).inOrder()
    }

    @Test fun duplicateCopiesContentWithNewIds() = runTest {
        val e = editor(listOf(page(1), page(2)))
        val d = async { e.ensureLoaded(PageId(1)) }; runCurrent(); d.await()
        e.apply(Edit.Items(PageId(1), emptyList(), listOf(stroke(1), stroke(2))))
        e.apply(PageOps.duplicate(e, PageId(1)))
        assertThat(e.ids()).containsExactly(1L, 3L, 2L).inOrder()
        val copy = e.content(PageId(3))!!.all().toList()
        val orig = e.content(PageId(1))!!.all().toList()
        assertThat(copy).hasSize(2)
        assertThat(copy.map { it.id.value }.toSet().intersect(orig.map { it.id.value }.toSet())).isEmpty()
        assertThat(copy.map { it.bounds }).containsExactlyElementsIn(orig.map { it.bounds })
        e.undo()
        assertThat(e.ids()).containsExactly(1L, 2L).inOrder()
    }

    @Test fun deleteLastPageRefusedAndUndoRestores() = runTest {
        val e = editor(listOf(page(1), page(2)))
        val d = async { e.ensureLoaded(PageId(2)) }; runCurrent(); d.await()
        e.apply(Edit.Items(PageId(2), emptyList(), listOf(stroke(1))))
        val del = PageOps.delete(e, PageId(2))!!
        e.apply(del)
        assertThat(e.ids()).containsExactly(1L)
        assertThat(PageOps.delete(e, PageId(1))).isNull()
        e.undo()
        assertThat(e.ids()).containsExactly(1L, 2L).inOrder()
        assertThat(e.content(PageId(2))!!.all().toList()).hasSize(1)
    }

    @Test fun moveSingleAndNoOp() = runTest {
        val e = editor(listOf(page(1), page(2), page(3), page(4)))
        assertThat(PageOps.move(e, PageId(2), 1)).isNull()
        e.apply(PageOps.move(e, PageId(4), 1)!!)
        assertThat(e.ids()).containsExactly(1L, 4L, 2L, 3L).inOrder()
        e.apply(PageOps.move(e, PageId(1), 3)!!)
        assertThat(e.ids()).containsExactly(4L, 2L, 3L, 1L).inOrder()
        e.apply(PageOps.move(e, PageId(1), 0)!!)
        assertThat(e.ids()).containsExactly(1L, 4L, 2L, 3L).inOrder()
        e.undo(); e.undo(); e.undo()
        assertThat(e.ids()).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test fun moveWithoutRoomBatchesAndUndoes() = runTest {
        val b = Math.nextUp(1.0)
        val e = editor(listOf(page(1, ord = 1.0), page(2, ord = b), page(3, ord = 5.0)))
        val edit = PageOps.move(e, PageId(3), 1)!!
        assertThat(edit).isInstanceOf(Edit.Batch::class.java)
        e.apply(edit)
        assertThat(e.ids()).containsExactly(1L, 3L, 2L).inOrder()
        e.undo()
        assertThat(e.ids()).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(e.pages.value.map { it.ord }).containsExactly(1.0, b, 5.0).inOrder()
    }

    @Test fun templateAndBookmarkRoundTrip() = runTest {
        val e = editor(listOf(page(1), page(2)))
        val dots = Background.Paper(PaperPattern.DOTS, 14.17f)
        e.apply(PageOps.setTemplate(e, PageId(1), dots))
        assertThat(e.pages.value[0].background).isEqualTo(dots)
        assertThat(e.pages.value[1].background).isNotEqualTo(dots)
        e.apply(PageOps.setTemplateAll(e, dots)!!)
        assertThat(e.pages.value.map { it.background }).containsExactly(dots, dots)
        e.undo()
        assertThat(e.pages.value[1].background).isNotEqualTo(dots)
        e.apply(PageOps.setBookmark(e, PageId(2), true), undoable = false)
        assertThat(e.pages.value[1].bookmarked).isTrue()
        e.apply(PageOps.setBookmark(e, PageId(2), false), undoable = false)
        assertThat(e.pages.value[1].bookmarked).isFalse()
    }
}
