package app.notate.core.doc.layout

import app.notate.core.geometry.Rect
import app.notate.core.model.PageId
import app.notate.core.model.PageLayoutMode
import app.notate.core.testing.page
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentLayoutTest {
    private val pages = listOf(page(1, height = 100f), page(2, height = 200f), page(3, height = 100f)).map { it.copy(width = 50f) }

    @Test fun continuousFramesAccumulateWithGap() {
        val l = DocumentLayout(pages, PageLayoutMode.CONTINUOUS, gapPt = 10f)
        assertThat(l.frameOf(PageId(1))).isEqualTo(Rect(0f, 0f, 50f, 100f))
        assertThat(l.frameOf(PageId(2))).isEqualTo(Rect(0f, 110f, 50f, 310f))
        assertThat(l.frameOf(PageId(3))).isEqualTo(Rect(0f, 320f, 50f, 420f))
        assertThat(l.totalHeight).isEqualTo(420f)
        assertThat(l.maxWidth).isEqualTo(50f)
    }

    @Test fun narrowerPagesAreCentered() {
        val l = DocumentLayout(listOf(pages[0], pages[1].copy(width = 30f)), PageLayoutMode.CONTINUOUS, gapPt = 10f)
        assertThat(l.frameOf(PageId(2)).l).isEqualTo(10f)
    }

    @Test fun pageAtAndGaps() {
        val l = DocumentLayout(pages, PageLayoutMode.CONTINUOUS, gapPt = 10f)
        assertThat(l.pageAt(10f, 50f)).isEqualTo(PageId(1))
        assertThat(l.pageAt(10f, 105f)).isNull()
        assertThat(l.pageAt(10f, 200f)).isEqualTo(PageId(2))
        assertThat(l.pageAt(60f, 50f)).isNull()
    }

    @Test fun visiblePagesAndIndex() {
        val l = DocumentLayout(pages, PageLayoutMode.CONTINUOUS, gapPt = 10f)
        assertThat(l.visiblePages(Rect(0f, 90f, 50f, 115f))).containsExactly(PageId(1), PageId(2)).inOrder()
        assertThat(l.visiblePages(Rect(0f, 100f, 50f, 110f))).isEmpty()
        assertThat(l.indexOf(PageId(3))).isEqualTo(2)
        assertThat(l.indexOf(PageId(9))).isEqualTo(-1)
        assertThat(l.frameOf(PageId(9)).isEmpty).isTrue()
    }

    @Test fun singleModeLaysPagesInAStrip() {
        val l = DocumentLayout(pages, PageLayoutMode.SINGLE, gapPt = 10f)
        assertThat(l.frameOf(PageId(1))).isEqualTo(Rect(0f, 0f, 50f, 100f))
        assertThat(l.frameOf(PageId(2))).isEqualTo(Rect(60f, 0f, 110f, 200f))
        assertThat(l.frameOf(PageId(3))).isEqualTo(Rect(120f, 0f, 170f, 100f))
        assertThat(l.totalHeight).isEqualTo(200f)
        assertThat(l.maxWidth).isEqualTo(170f)
        assertThat(l.visiblePages(Rect(0f, 0f, 55f, 50f))).containsExactly(PageId(1))
    }

    @Test fun whiteboardUsesContentHeightCallback() {
        val l = DocumentLayout(listOf(page(1, height = null)), PageLayoutMode.CONTINUOUS, contentHeight = { 777f })
        assertThat(l.frameOf(PageId(1)).height).isEqualTo(777f)
        assertThat(l.hasUnboundedPage).isTrue()
    }

    @Test fun emptyLayout() {
        val l = DocumentLayout(emptyList(), PageLayoutMode.CONTINUOUS)
        assertThat(l.totalHeight).isEqualTo(0f)
        assertThat(l.pageAt(0f, 0f)).isNull()
    }
}
