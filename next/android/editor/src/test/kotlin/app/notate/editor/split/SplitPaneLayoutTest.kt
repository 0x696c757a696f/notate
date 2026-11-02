package app.notate.editor.split

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import app.notate.core.workspace.DividerDrop
import app.notate.core.workspace.Orientation
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SplitPaneLayoutTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val events = ArrayList<String>()
    private val drops = ArrayList<DividerDrop>()
    private val layout = SplitPaneLayout(
        context,
        object : SplitPaneLayout.Listener {
            override fun onDividerDragStarted() { events += "start" }
            override fun onDividerDropped(drop: DividerDrop) { events += "drop"; drops += drop }
            override fun onDividerDragCancelled() { events += "cancel" }
        },
    )
    private val a = View(context)
    private val b = View(context)

    private fun arrange(w: Int = 1000, h: Int = 800) {
        layout.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        layout.layout(0, 0, w, h)
    }

    private fun touch(action: Int, x: Float, y: Float): Boolean {
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, action, x, y, 0)
        return layout.dispatchTouchEvent(ev).also { ev.recycle() }
    }

    @Test fun aSinglePaneFillsTheSpace() {
        layout.setPanes(a, null)
        arrange()
        assertThat(a.width).isEqualTo(1000)
        assertThat(a.height).isEqualTo(800)
        assertThat(layout.isSplit).isFalse()
        assertThat(layout.hitsDivider(500f, 400f)).isFalse()
    }

    @Test fun twoPanesShareTheSpaceAroundA12DpDivider() {
        layout.setPanes(a, b)
        layout.ratio = 0.5f
        arrange()
        val divider = (12 * context.resources.displayMetrics.density + 0.5f).toInt()
        assertThat(a.left).isEqualTo(0)
        assertThat(a.width + divider + b.width).isEqualTo(1000)
        assertThat(b.left).isEqualTo(a.width + divider)
        assertThat(a.height).isEqualTo(800)
        assertThat(kotlin.math.abs(a.width - b.width)).isAtMost(1)
    }

    @Test fun stackedPanesShareTheHeight() {
        layout.setPanes(a, b)
        layout.orientation = Orientation.VERTICAL
        layout.ratio = 0.25f
        arrange()
        val divider = (12 * context.resources.displayMetrics.density + 0.5f).toInt()
        assertThat(a.width).isEqualTo(1000)
        assertThat(a.height).isEqualTo(((800 - divider) * 0.25f).toInt())
        assertThat(b.top).isEqualTo(a.height + divider)
        assertThat(b.height + a.height + divider).isEqualTo(800)
    }

    @Test fun changingTheDirectionLaysThePanesOutAgain() {
        layout.setPanes(a, b)
        arrange()
        val wide = a.width
        layout.orientation = Orientation.VERTICAL
        arrange()
        assertThat(a.width).isEqualTo(1000)
        assertThat(a.width).isNotEqualTo(wide)
    }

    @Test fun draggingOnlyMovesAPreviewAndReleaseSnapsToAThird() {
        layout.setPanes(a, b)
        layout.ratio = 0.5f
        arrange()
        val widthBefore = a.width
        val x = layout.run { a.width + 6f }
        assertThat(touch(MotionEvent.ACTION_DOWN, x, 400f)).isTrue()
        assertThat(events).containsExactly("start")
        touch(MotionEvent.ACTION_MOVE, 340f, 400f)
        assertThat(a.width).isEqualTo(widthBefore)          // the panes did not move while dragging
        touch(MotionEvent.ACTION_UP, 340f, 400f)
        assertThat(events).containsExactly("start", "drop").inOrder()
        val drop = drops.single() as DividerDrop.Ratio
        assertThat(drop.value).isWithin(0.001f).of(1f / 3f)
    }

    @Test fun draggingToTheEdgeClosesThatSide() {
        layout.setPanes(a, b)
        arrange()
        touch(MotionEvent.ACTION_DOWN, a.width + 6f, 400f)
        touch(MotionEvent.ACTION_MOVE, 20f, 400f)
        touch(MotionEvent.ACTION_UP, 20f, 400f)
        assertThat(drops.single()).isEqualTo(DividerDrop.CloseFirst)

        drops.clear()
        touch(MotionEvent.ACTION_DOWN, a.width + 6f, 400f)
        touch(MotionEvent.ACTION_MOVE, 985f, 400f)
        touch(MotionEvent.ACTION_UP, 985f, 400f)
        assertThat(drops.single()).isEqualTo(DividerDrop.CloseSecond)
    }

    @Test fun draggingUnderTheSplitOfAVerticalLayoutUsesTheHeight() {
        layout.setPanes(a, b)
        layout.orientation = Orientation.VERTICAL
        arrange()
        touch(MotionEvent.ACTION_DOWN, 500f, a.height + 6f)
        touch(MotionEvent.ACTION_MOVE, 500f, 540f)
        touch(MotionEvent.ACTION_UP, 500f, 540f)
        val drop = drops.single() as DividerDrop.Ratio
        assertThat(drop.value).isWithin(0.001f).of(2f / 3f)
    }

    @Test fun aCancelledDragIsReported() {
        layout.setPanes(a, b)
        arrange()
        touch(MotionEvent.ACTION_DOWN, a.width + 6f, 400f)
        touch(MotionEvent.ACTION_CANCEL, a.width + 6f, 400f)
        assertThat(events).containsExactly("start", "cancel").inOrder()
        assertThat(drops).isEmpty()
    }

    @Test fun theSamePanesWithANewRatioAreNotReAdded() {
        layout.setPanes(a, b)
        arrange()
        val parentBefore = a.parent
        layout.ratio = 0.7f
        layout.setPanes(a, b)
        arrange()
        assertThat(a.parent).isSameInstanceAs(parentBefore)
        assertThat(a.width).isGreaterThan(b.width)
    }

    @Test fun paneFrameShowsTheFocusInTheStripAndTheTitleColour() {
        val frame = PaneFrame(context, ownsBar = false)
        assertThat(frame.barVisible()).isFalse()
        assertThat(frame.stripVisible()).isFalse()
        frame.setSplit(true)
        frame.setTitle("Calculus")
        frame.setPage(2, 10)
        assertThat(frame.barVisible()).isTrue()
        assertThat(frame.stripVisible()).isTrue()
        assertThat(frame.stripBlack()).isFalse()
        assertThat(frame.titleColor()).isEqualTo(PaneFrame.GRAY)
        frame.setFocused(true)
        assertThat(frame.stripBlack()).isTrue()
        assertThat(frame.titleColor()).isEqualTo(Color.BLACK)
    }

    @Test fun aPaneThatDrawsItsOwnBarGetsNoSecondOne() {
        val frame = PaneFrame(context, ownsBar = true)
        frame.setSplit(true)
        assertThat(frame.barVisible()).isFalse()
        assertThat(frame.stripVisible()).isTrue()
    }
}
