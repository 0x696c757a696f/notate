package app.notate.editor

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.doc.select.Handle
import app.notate.core.doc.select.Selection
import app.notate.core.geometry.Rect
import app.notate.core.input.BlockReason
import app.notate.core.input.EpdScheduler
import app.notate.core.input.InkArbiter
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.PageLayoutMode
import app.notate.core.testing.page
import app.notate.editor.popup.PopupHost
import app.notate.editor.select.SelectionLayer
import app.notate.platform.epd.NoEpdPort
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectionLayerHitTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val density = context.resources.displayMetrics.density
    private val layer = SelectionLayer(density)
    private val layout = DocumentLayout(listOf(page(1)), PageLayoutMode.CONTINUOUS)
    private val vp = Viewport(0f, 0f, 1f, 1f, 1000, 1000)
    private val sel = Selection(PageId(1), setOf(ItemId(1)), Rect(100f, 100f, 300f, 200f))

    private fun hit(x: Float, y: Float) = layer.hit(sel, vp, layout, x, y)

    @Test fun handleTouchTargetIsVisualSizePlusEightDp() {
        val reach = (SelectionLayer.HANDLE_DP / 2f + 8f) * density
        assertThat(hit(300f + reach - 1f, 200f + reach - 1f)).isEqualTo(Handle.SE)
        assertThat(hit(100f - reach + 1f, 100f - reach + 1f)).isEqualTo(Handle.NW)
        assertThat(hit(300f + reach + 2f, 200f + reach + 2f)).isNull()
    }

    @Test fun edgeAndRotateHandlesAreFound() {
        assertThat(hit(200f, 200f)).isEqualTo(Handle.S)
        assertThat(hit(300f, 150f)).isEqualTo(Handle.E)
        assertThat(hit(200f, 100f - SelectionLayer.ROTATE_DP * density)).isEqualTo(Handle.ROTATE)
    }

    @Test fun interiorMovesAndOutsideMisses() {
        assertThat(hit(200f, 150f)).isEqualTo(Handle.MOVE)
        assertThat(hit(500f, 500f)).isNull()
    }

    @Test fun barDoesNotBlockThePenAndIsReportedAsExcluded() {
        val root = FrameLayout(context).also {
            it.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            it.layout(0, 0, 1000, 1000)
        }
        val arbiter = InkArbiter()
        val host = PopupHost(root, arbiter, EpdScheduler(NoEpdPort))
        var changes = 0
        host.onBarChanged = { changes++ }
        val content = View(context).apply { minimumWidth = 200; minimumHeight = 50 }
        host.showBar(content, root, android.graphics.Rect(300, 300, 500, 400))
        assertThat(host.barRect()).isNotNull()
        assertThat(arbiter.isBlockedBy(BlockReason.POPUP)).isFalse()
        assertThat(host.isShowing).isFalse()
        // A regular popup can open next to the bar and replaces only itself.
        host.show(null, View(context).apply { minimumWidth = 100; minimumHeight = 100 })
        assertThat(host.barRect()).isNotNull()
        host.dismiss()
        host.hideBar()
        assertThat(host.barRect()).isNull()
        assertThat(changes).isEqualTo(2)
    }
}
