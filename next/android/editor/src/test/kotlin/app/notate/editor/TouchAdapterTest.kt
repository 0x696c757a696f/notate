package app.notate.editor

import android.view.MotionEvent
import app.notate.core.input.PointerAction
import app.notate.core.input.PointerKind
import app.notate.core.input.PointerSample
import app.notate.editor.input.TouchAdapter
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TouchAdapterTest {
    private val adapter = TouchAdapter(xdpi = 254f)    // 10 px per mm

    private fun props(vararg tools: Int) = Array(tools.size) { i ->
        MotionEvent.PointerProperties().apply { id = i + 1; toolType = tools[i] }
    }

    private fun coords(x: Float, y: Float, pressure: Float = 0.5f, major: Float = 0f) =
        MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure; touchMajor = major }

    private fun event(action: Int, tools: IntArray, c: Array<MotionEvent.PointerCoords>, buttons: Int = 0, time: Long = 100L): MotionEvent =
        MotionEvent.obtain(0L, time, action, tools.size, props(*tools), c, 0, buttons, 1f, 1f, 0, 0, 0, 0)

    @Test fun stylusMoveExpandsHistoricalPointsInOrder() {
        val ev = event(MotionEvent.ACTION_MOVE, intArrayOf(MotionEvent.TOOL_TYPE_STYLUS), arrayOf(coords(10f, 10f)), time = 10L)
        ev.addBatch(20L, arrayOf(coords(20f, 20f)), 0)
        ev.addBatch(30L, arrayOf(coords(30f, 30f)), 0)
        val samples = adapter.convert(ev)
        assertThat(samples.map { it.x }).containsExactly(10f, 20f, 30f).inOrder()
        assertThat(samples.map { it.timeMs }).containsExactly(10L, 20L, 30L).inOrder()
        assertThat(samples.map { it.action }.toSet()).containsExactly(PointerAction.MOVE)
        assertThat(samples.map { it.kind }.toSet()).containsExactly(PointerKind.STYLUS)
    }

    @Test fun fingerHistoryIsNotExpanded() {
        val ev = event(MotionEvent.ACTION_MOVE, intArrayOf(MotionEvent.TOOL_TYPE_FINGER), arrayOf(coords(10f, 10f)), time = 10L)
        ev.addBatch(20L, arrayOf(coords(20f, 20f)), 0)
        assertThat(adapter.convert(ev)).hasSize(1)
    }

    @Test fun secondFingerDownReportsThatPointer() {
        val action = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val ev = event(action, intArrayOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_FINGER), arrayOf(coords(1f, 1f), coords(5f, 6f)))
        val s = adapter.convert(ev).single()
        assertThat(s.action).isEqualTo(PointerAction.POINTER_DOWN)
        assertThat(s.id).isEqualTo(2)
        assertThat(s.x).isEqualTo(5f)
        assertThat(s.y).isEqualTo(6f)
        assertThat(s.pointerCount).isEqualTo(2)
        assertThat(s.kind).isEqualTo(PointerKind.FINGER)
    }

    @Test fun moveEmitsOneSamplePerPointer() {
        val ev = event(MotionEvent.ACTION_MOVE, intArrayOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_FINGER), arrayOf(coords(1f, 1f), coords(5f, 6f)))
        assertThat(adapter.convert(ev).map { it.id }).containsExactly(1, 2).inOrder()
    }

    @Test fun eraserEndAndSideButtonAreReported() {
        val eraser = adapter.convert(event(MotionEvent.ACTION_DOWN, intArrayOf(MotionEvent.TOOL_TYPE_ERASER), arrayOf(coords(1f, 1f)))).single()
        assertThat(eraser.kind).isEqualTo(PointerKind.ERASER_END)
        val btn = adapter.convert(
            event(MotionEvent.ACTION_DOWN, intArrayOf(MotionEvent.TOOL_TYPE_STYLUS), arrayOf(coords(1f, 1f)), buttons = MotionEvent.BUTTON_STYLUS_PRIMARY),
        ).single()
        assertThat(btn.buttons and PointerSample.BUTTON_STYLUS_PRIMARY).isNotEqualTo(0)
    }

    @Test fun touchMajorIsConvertedToMillimetres() {
        val s = adapter.convert(event(MotionEvent.ACTION_DOWN, intArrayOf(MotionEvent.TOOL_TYPE_FINGER), arrayOf(coords(1f, 1f, major = 100f)))).single()
        assertThat(s.majorMm).isWithin(0.01f).of(10f)
    }

    @Test fun hoverEventsMapToHoverActions() {
        val enter = adapter.convert(event(MotionEvent.ACTION_HOVER_ENTER, intArrayOf(MotionEvent.TOOL_TYPE_STYLUS), arrayOf(coords(1f, 1f)))).single()
        val move = adapter.convert(event(MotionEvent.ACTION_HOVER_MOVE, intArrayOf(MotionEvent.TOOL_TYPE_STYLUS), arrayOf(coords(2f, 2f)))).single()
        val exit = adapter.convert(event(MotionEvent.ACTION_HOVER_EXIT, intArrayOf(MotionEvent.TOOL_TYPE_STYLUS), arrayOf(coords(3f, 3f)))).single()
        assertThat(listOf(enter.action, move.action, exit.action))
            .containsExactly(PointerAction.HOVER_ENTER, PointerAction.HOVER_MOVE, PointerAction.HOVER_EXIT).inOrder()
    }

    @Test fun cancelReportsEveryPointer() {
        val ev = event(MotionEvent.ACTION_CANCEL, intArrayOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_FINGER), arrayOf(coords(1f, 1f), coords(5f, 6f)))
        assertThat(adapter.convert(ev).map { it.action }).containsExactly(PointerAction.CANCEL, PointerAction.CANCEL)
    }
}
