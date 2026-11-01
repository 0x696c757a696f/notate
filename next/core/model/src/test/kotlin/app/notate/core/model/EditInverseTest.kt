package app.notate.core.model

import app.notate.core.model.edit.Edit
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditInverseTest {
    private val style = InkStyle(PenType.BALLPOINT, 0, 1f)
    private fun stroke(id: Long): Stroke {
        val p = StrokePoints(floatArrayOf(0f, 0f, id.toFloat(), 1f), null, null)
        return Stroke(ItemId(id), id, style, p, ItemBounds.strokeBounds(p, style))
    }
    private fun page(id: Long) = PageInfo(PageId(id), id.toDouble(), 100f, 200f, Background.None, 0, 0)
    private val meta = DocumentMeta(DocId("A"), "t", DocKind.NOTEBOOK, 1, 2)

    private val all: List<Edit> = listOf(
        Edit.Items(PageId(1), listOf(stroke(1)), listOf(stroke(2), stroke(3))),
        Edit.InsertPage(page(1), listOf(stroke(1))),
        Edit.DeletePage(page(2), listOf(stroke(2))),
        Edit.MovePage(PageId(1), 1.0, 2.5),
        Edit.SetPage(page(1), page(1).copy(background = Background.Paper(PaperPattern.GRID, 10f))),
        Edit.SetMeta(meta, meta.copy(title = "new")),
        Edit.Batch(listOf(Edit.MovePage(PageId(1), 1.0, 2.0), Edit.Items(PageId(1), emptyList(), listOf(stroke(4))))),
    )

    @Test fun doubleInverseIsIdentity() {
        for (e in all) assertThat(e.inverse().inverse()).isEqualTo(e)
    }

    @Test fun itemsSwapsRemovedAndAdded() {
        val e = Edit.Items(PageId(1), listOf(stroke(1)), listOf(stroke(2))) 
        val inv = e.inverse() as Edit.Items
        assertThat(inv.removed).isEqualTo(e.added)
        assertThat(inv.added).isEqualTo(e.removed)
    }

    @Test fun pageEditsInvertToCounterpart() {
        assertThat(Edit.InsertPage(page(1), emptyList()).inverse()).isEqualTo(Edit.DeletePage(page(1), emptyList()))
        assertThat(Edit.MovePage(PageId(1), 1.0, 2.0).inverse()).isEqualTo(Edit.MovePage(PageId(1), 2.0, 1.0))
    }

    @Test fun batchInvertsInReverseOrder() {
        val a = Edit.MovePage(PageId(1), 1.0, 2.0)
        val b = Edit.MovePage(PageId(1), 2.0, 3.0)
        assertThat(Edit.Batch(listOf(a, b)).inverse()).isEqualTo(Edit.Batch(listOf(b.inverse(), a.inverse())))
    }
}
