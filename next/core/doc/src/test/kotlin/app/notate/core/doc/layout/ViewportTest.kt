package app.notate.core.doc.layout

import app.notate.core.model.PageId
import app.notate.core.model.PageLayoutMode
import app.notate.core.testing.page
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ViewportTest {
    private fun vp(sx: Float = 0f, sy: Float = 0f, zoom: Float = 1f, w: Int = 400, h: Int = 600) =
        Viewport(sx, sy, zoom, pxPerPt = 2f, widthPx = w, heightPx = h)

    private val layout = DocumentLayout(
        listOf(page(1, height = 800f), page(2, height = 800f)).map { it.copy(width = 300f) }, PageLayoutMode.CONTINUOUS, gapPt = 16f,
    )

    @Test fun transformsAreInverse() {
        val v = vp(10f, 20f, 1.5f)
        val p = v.docToScreen().map(50f, 60f)
        assertThat(p.x).isWithin(1e-3f).of((50f - 10f) * 3f)
        assertThat(p.y).isWithin(1e-3f).of((60f - 20f) * 3f)
        val back = v.screenToDoc().map(p.x, p.y)
        assertThat(back.x).isWithin(1e-3f).of(50f)
        assertThat(back.y).isWithin(1e-3f).of(60f)
    }

    @Test fun visibleDocRect() {
        val r = vp(10f, 20f, 1f).visibleDocRect()
        assertThat(r.l).isEqualTo(10f); assertThat(r.t).isEqualTo(20f)
        assertThat(r.width).isEqualTo(200f); assertThat(r.height).isEqualTo(300f)
    }

    @Test fun zoomAroundKeepsFocusFixed() {
        val v = vp(30f, 40f, 1f)
        val before = v.screenToDoc().map(123f, 77f)
        val z = v.zoomAround(2f, 123f, 77f)
        assertThat(z.zoom).isEqualTo(2f)
        val after = z.screenToDoc().map(123f, 77f)
        assertThat(after.x).isWithin(1e-3f).of(before.x)
        assertThat(after.y).isWithin(1e-3f).of(before.y)
    }

    @Test fun zoomIsClamped() {
        assertThat(vp().zoomAround(100f, 0f, 0f).zoom).isEqualTo(6f)
        assertThat(vp().zoomAround(0.001f, 0f, 0f).zoom).isEqualTo(0.25f)
    }

    @Test fun panFollowsFingerAndClampsVertically() {
        val v = vp(sx = 0f, sy = 100f, zoom = 1f)
        assertThat(v.panBy(0f, -40f, layout).scrollY).isEqualTo(120f)           // finger up → scroll down (40 px = 20 pt)
        assertThat(v.panBy(0f, 10_000f, layout).scrollY).isEqualTo(-16f)         // top: −margin
        val visH = 300f
        assertThat(v.panBy(0f, -100_000f, layout).scrollY).isEqualTo(layout.totalHeight - visH + 16f)
    }

    @Test fun narrowPageIsCenteredHorizontally() {
        val v = vp(sx = 0f)   // viewport shows 200 pt, page is 300 pt wide → scrollable
        assertThat(v.panBy(-50f, 0f, layout).scrollX).isEqualTo(25f)
        val wide = vp(zoom = 0.5f, w = 800)   // scale 1 → 800 pt visible, page 300 wide
        assertThat(wide.panBy(30f, 0f, layout).scrollX).isEqualTo((0f + 300f - 800f) / 2f)
    }

    @Test fun whiteboardIsNotClamped() {
        val wb = DocumentLayout(listOf(page(1, height = null)), PageLayoutMode.CONTINUOUS)
        assertThat(vp().panBy(5000f, 5000f, wb).scrollX).isEqualTo(-2500f)
    }

    @Test fun singleModeClampsToCurrentPage() {
        val l = DocumentLayout(listOf(page(1, height = 800f), page(2, height = 800f)).map { it.copy(width = 300f) }, PageLayoutMode.SINGLE, gapPt = 16f)
        val v = vp(sx = 316f, sy = 0f)    // viewing page 2 (x 316..616)
        val p = v.panBy(100_000f, 0f, l)
        assertThat(p.scrollX).isEqualTo(316f - 16f)   // cannot slide back past page 2's left margin
        assertThat(v.panBy(0f, -100_000f, l).scrollY).isEqualTo(800f - 300f + 16f)
    }

    @Test fun fitWidth() {
        val v = vp(zoom = 3f).fitWidth(layout, PageId(2), marginPx = 10f)
        // (400 − 20) / (300 · 2) px per pt
        assertThat(v.zoom).isWithin(1e-4f).of(380f / 600f)
        val topLeft = v.docToScreen().map(0f, layout.frameOf(PageId(2)).t)
        assertThat(topLeft.x).isWithin(1e-2f).of(10f)
        assertThat(topLeft.y).isWithin(1e-2f).of(10f)
    }

    @Test fun levelsAreInverse() {
        for (l in -4..10) assertThat(Viewport.levelFor(Viewport.scaleOf(l))).isEqualTo(l)
        assertThat(Viewport.levelFor(1f)).isEqualTo(0)
        assertThat(Viewport.levelFor(2f)).isEqualTo(2)
        assertThat(Viewport.scaleOf(2)).isWithin(1e-5f).of(2f)
    }

    @Test fun extraBottomPtExtendsContinuousScrollRange() {
        val v = vp(sx = 0f, sy = 100f, zoom = 1f)
        val plain = v.panBy(0f, -1_000_000f, layout).scrollY
        val extra = v.panBy(0f, -1_000_000f, layout, extraBottomPt = 80f).scrollY
        assertThat(extra - plain).isEqualTo(80f)
    }
}
