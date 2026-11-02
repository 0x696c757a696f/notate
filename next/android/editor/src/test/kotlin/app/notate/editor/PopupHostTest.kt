package app.notate.editor

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import app.notate.core.input.BlockReason
import app.notate.core.input.EpdScheduler
import app.notate.core.input.InkArbiter
import app.notate.editor.popup.Placement
import app.notate.editor.popup.PopupHost
import app.notate.platform.epd.NoEpdPort
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PopupHostTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = FrameLayout(context).also {
        it.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        it.layout(0, 0, 1000, 1000)
    }
    private val arbiter = InkArbiter()
    private val host = PopupHost(root, arbiter, EpdScheduler(NoEpdPort))

    private fun content() = View(context).apply { minimumWidth = 200; minimumHeight = 100 }

    private fun down(x: Float, y: Float, tool: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().also { it.id = 0; it.toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().also { it.x = x; it.y = y })
        return MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    @Test fun showBlocksThePenAndDismissReleasesIt() {
        host.show(null, content(), Placement.CENTER)
        assertThat(host.isShowing).isTrue()
        assertThat(arbiter.isBlockedBy(BlockReason.POPUP)).isTrue()
        host.dismiss()
        assertThat(host.isShowing).isFalse()
        assertThat(arbiter.isBlockedBy(BlockReason.POPUP)).isFalse()
    }

    @Test fun aSecondPopupReplacesTheFirst() {
        val first = host.show(null, content(), Placement.CENTER)
        val second = host.show(null, content(), Placement.CENTER)
        assertThat(first.isShowing).isFalse()
        assertThat(second.isShowing).isTrue()
        assertThat(root.childCount).isEqualTo(1)
        assertThat(arbiter.isBlockedBy(BlockReason.POPUP)).isTrue()
    }

    @Test fun rectIsReportedWhileShownAndClearedAfterwards() {
        val seen = ArrayList<Boolean>()
        host.onRectChanged = { seen.add(it != null) }
        host.show(null, content(), Placement.CENTER)
        host.dismiss()
        assertThat(seen).containsExactly(true, false).inOrder()
    }

    @Test fun penDownOutsideClosesAndLetsTheStrokeThrough() {
        host.show(null, content(), Placement.CENTER)
        val consumed = host.interceptOutside(down(5f, 5f, MotionEvent.TOOL_TYPE_STYLUS))
        assertThat(consumed).isFalse()
        assertThat(host.isShowing).isFalse()
        assertThat(arbiter.isBlockedBy(BlockReason.POPUP)).isFalse()
    }

    @Test fun fingerDownOutsideClosesAndIsSwallowed() {
        host.show(null, content(), Placement.CENTER)
        assertThat(host.interceptOutside(down(5f, 5f, MotionEvent.TOOL_TYPE_FINGER))).isTrue()
        assertThat(host.isShowing).isFalse()
    }

    @Test fun touchInsideLeavesThePopupAlone() {
        host.show(null, content(), Placement.CENTER)
        assertThat(host.interceptOutside(down(500f, 500f, MotionEvent.TOOL_TYPE_FINGER))).isFalse()
        assertThat(host.isShowing).isTrue()
        assertThat(host.interceptOutside(down(500f, 500f, MotionEvent.TOOL_TYPE_STYLUS))).isFalse()
        assertThat(host.isShowing).isTrue()
    }
}
