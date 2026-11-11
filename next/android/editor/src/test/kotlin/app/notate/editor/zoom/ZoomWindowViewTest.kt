package app.notate.editor.zoom

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.doc.zoom.ZoomWindowState
import app.notate.core.geometry.Rect
import app.notate.core.model.PageId
import app.notate.core.model.PageLayoutMode
import app.notate.core.testing.page
import app.notate.editor.view.LiveInkLayer
import app.notate.render.tile.TileCache
import app.notate.render.view.TileCompositor
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZoomWindowViewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val density = context.resources.displayMetrics.density
    private val cache = TileCache(TileCache.defaultMaxBytes(64))
    private val view = ZoomWindowView(context, TileCompositor(cache, density) { null }, LiveInkLayer(View(context), density) { null })
    private val pages = listOf(page(1))
    private val layout = DocumentLayout(pages, PageLayoutMode.CONTINUOUS)

    private fun open() {
        view.dock(1404, 1872)
        view.layout = layout
        view.viewport = Viewport(0f, 0f, 1f, 3f, 1404, 1872)
        val w = view.writing
        view.state = ZoomWindowState(PageId(1), Rect(0f, 0f, 595.28f, 841.89f), w.width() / 3f, w.width() / w.height())
    }

    @Test fun aClosedWindowHasNoHitAreas() {
        view.dock(1404, 1872)
        assertThat(view.isOpen).isFalse()
        assertThat(view.contains(700f, 1700f)).isFalse()
        assertThat(view.controlAt(1380f, 1300f)).isNull()
        assertThat(view.pointMap()).isNull()
        assertThat(view.zoomViewport()).isNull()
    }

    @Test fun theWindowDocksToTheBottomThird() {
        open()
        assertThat(view.region.top).isEqualTo(1872f - 1872 / 3)
        assertThat(view.region.bottom).isEqualTo(1872f)
        assertThat(view.writing.right).isEqualTo(view.buttons.left)
        assertThat(view.buttons.right).isEqualTo(1404f)
        assertThat(view.contains(10f, 1500f)).isTrue()
        assertThat(view.contains(10f, 500f)).isFalse()
    }

    @Test fun everyButtonIsHitAtItsCentre() {
        open()
        for (c in ZoomWindowView.Control.entries) {
            val r = view.rectOf(c)!!
            assertThat(view.controlAt(r.centerX(), r.centerY())).isEqualTo(c)
        }
        assertThat(view.controlAt(view.writing.centerX(), view.writing.centerY())).isNull()
    }

    @Test fun buttonsAreInTheDocumentedOrderAndStackedVertically() {
        open()
        val tops = ZoomWindowView.Control.entries.map { view.rectOf(it)!!.top }
        assertThat(tops).isInStrictOrder()
        assertThat(ZoomWindowView.Control.entries.map { it.name })
            .containsExactly("BACK", "FORWARD", "NEW_LINE", "SMALLER", "LARGER", "CLOSE").inOrder()
    }

    @Test fun writingCornersMapToTheBoxCorners() {
        open()
        val box = view.state!!.box
        val m = view.pointMap()!!
        val tl = m.map(view.writing.left, view.writing.top)
        val br = m.map(view.writing.right, view.writing.bottom)
        assertThat(tl.x).isWithin(0.01f).of(box.l)
        assertThat(tl.y).isWithin(0.01f).of(box.t)
        assertThat(br.x).isWithin(0.01f).of(box.r)
        assertThat(br.y).isWithin(0.01f).of(box.b)
    }

    @Test fun theZoomViewportShowsExactlyTheBox() {
        open()
        val box = view.state!!.box
        val zv = view.zoomViewport()!!
        val visible = zv.visibleDocRect()
        val f = layout.frameOf(PageId(1))
        assertThat(visible.l).isWithin(0.01f).of(f.l + box.l)
        assertThat(visible.t).isWithin(0.01f).of(f.t + box.t)
        assertThat(visible.width).isWithin(0.05f).of(box.width)
        assertThat(visible.height).isWithin(0.05f).of(box.height)
        assertThat(zv.scale).isWithin(0.001f).of(view.scale)
    }

    @Test fun theDashedBoxIsLocatedOnTheScreenWithThePaneViewport() {
        open()
        val box = view.state!!.box
        val r = view.boxOnScreen()!!
        assertThat(r.left).isWithin(0.01f).of(box.l * 3f)
        assertThat(r.top).isWithin(0.01f).of(box.t * 3f)
        assertThat(r.width()).isWithin(0.05f).of(box.width * 3f)
    }
}
