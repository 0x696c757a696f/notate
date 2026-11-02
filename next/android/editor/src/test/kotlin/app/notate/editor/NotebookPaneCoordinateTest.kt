package app.notate.editor

import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.model.PageLayoutMode
import app.notate.core.testing.page
import app.notate.editor.pane.PaneCoords
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotebookPaneCoordinateTest {
    private val pages = listOf(page(1), page(2))
    private val layout = DocumentLayout(pages, PageLayoutMode.CONTINUOUS)

    @Test fun pointOnFirstPageAtOrigin() {
        val vp = Viewport(0f, 0f, 1f, 2f, 800, 1200)
        val p = PaneCoords.toPage(vp, layout, 100f, 60f)!!
        assertThat(p.page).isEqualTo(pages[0].id)
        assertThat(p.x).isWithin(0.001f).of(50f)
        assertThat(p.y).isWithin(0.001f).of(30f)
    }

    @Test fun scrollAndZoomShiftTheMapping() {
        // scale 6 (zoom 3, 2 px/pt), scrolled to doc (20, 900): the second page starts at y = 841.89 + 16.
        val vp = Viewport(20f, 900f, 3f, 2f, 800, 1200)
        val p = PaneCoords.toPage(vp, layout, 60f, 300f)!!
        assertThat(p.page).isEqualTo(pages[1].id)
        assertThat(p.x).isWithin(0.001f).of(20f + 10f - layout.frameOf(pages[1].id).l)
        assertThat(p.y).isWithin(0.001f).of(900f + 50f - layout.frameOf(pages[1].id).t)
    }

    @Test fun gapBetweenPagesBelongsToNoPage() {
        val vp = Viewport(0f, 800f, 1f, 1f, 800, 1200)
        val gapY = layout.frameOf(pages[0].id).b + 4f - 800f
        assertThat(PaneCoords.toPage(vp, layout, 100f, gapY)).isNull()
    }

    @Test fun roundTripThroughScreen() {
        val vp = Viewport(10f, 20f, 2.5f, 3.15f, 1404, 1800)
        val frame = layout.frameOf(pages[0].id)
        val s = PaneCoords.toScreen(vp, frame, 100f, 200f)
        val back = PaneCoords.toPageIn(vp, frame, s.x, s.y)
        assertThat(back.x).isWithin(0.01f).of(100f)
        assertThat(back.y).isWithin(0.01f).of(200f)
    }

    @Test fun strokeStaysOnItsPageWhileMovingOverTheGap() {
        val vp = Viewport(0f, 0f, 1f, 1f, 800, 1200)
        val frame = layout.frameOf(pages[0].id)
        val below = PaneCoords.toPageIn(vp, frame, 100f, frame.b + 30f)   // beyond the page bottom: still page-0 coordinates
        assertThat(below.y).isWithin(0.001f).of(frame.height + 30f)
    }
}
