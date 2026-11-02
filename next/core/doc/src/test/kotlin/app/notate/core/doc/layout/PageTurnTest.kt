package app.notate.core.doc.layout

import app.notate.core.model.PageLayoutMode
import app.notate.core.testing.page
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageTurnTest {
    @Test fun swipeLeftIsNextRightIsPrevious() {
        assertThat(PageTurn.direction(-400f, 10f, -200f, 1000, 2f)).isEqualTo(1)
        assertThat(PageTurn.direction(400f, 10f, 200f, 1000, 2f)).isEqualTo(-1)
    }

    @Test fun shortSlowStaysFastShortTurns() {
        assertThat(PageTurn.direction(-200f, 0f, -300f, 1000, 2f)).isEqualTo(0)
        assertThat(PageTurn.direction(-100f, 0f, -2500f, 1000, 2f)).isEqualTo(1)
        assertThat(PageTurn.direction(-100f, 0f, -1500f, 1000, 2f)).isEqualTo(0)   // 1500 px/s = 750 dp/s
    }

    @Test fun verticalNeverTurns() {
        assertThat(PageTurn.direction(-100f, 900f, -5000f, 1000, 2f)).isEqualTo(0)
    }

    @Test fun edgesAtFitAndZoomed() {
        val pages = listOf(page(1), page(2), page(3))
        val layout = DocumentLayout(pages, PageLayoutMode.SINGLE)
        val fit = Viewport(0f, 0f, 1f, 1f, 595, 842).fitWidth(layout, pages[1].id, 0f)
        assertThat(PageTurn.atEdge(fit, layout, 1)).isTrue()
        assertThat(PageTurn.atEdge(fit, layout, -1)).isTrue()
        val f = layout.frameAt(1)
        val zoomed = Viewport(f.l + 50f, 0f, 2f, 1f, 595, 842)
        assertThat(PageTurn.atEdge(zoomed, layout, 1)).isFalse()
        assertThat(PageTurn.atEdge(zoomed, layout, -1)).isFalse()
        assertThat(PageTurn.atEdge(zoomed.copy(scrollX = f.r - 595 / 2f), layout, 1)).isTrue()
        assertThat(PageTurn.atEdge(zoomed.copy(scrollX = f.l), layout, -1)).isTrue()
    }
}
