package app.notate.web

import app.notate.core.input.BlockReason
import app.notate.core.input.GestureClassifier
import app.notate.core.input.HoverFocusDebouncer
import app.notate.core.input.InputDispatcher
import app.notate.core.input.PalmRejector
import app.notate.core.input.PenEvent
import app.notate.core.input.PointerAction
import app.notate.core.input.PointerKind
import app.notate.core.input.PointerSample
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Writing on a web chapter always ends up in storage, including the two situations that used to swallow strokes. */
@RunWith(RobolectricTestRunner::class)
class EditorFlowWebTest {
    private fun stroke(pane: app.notate.web.pane.WebPane, x: Float = 300f, y: Float = 400f) {
        pane.onPen(PenEvent.Down(x, y, 0.5f, 0f, 0f, 0L, eraser = false))
        for (i in 1..10) pane.onPen(PenEvent.Move(x + i * 20f, y + i * 5f, 0.5f, 0f, 0f, i * 8L))
        pane.onPen(PenEvent.Up(x + 220f, y + 60f, 100L))
    }

    @Test fun strokeIsStoredAndSurvivesReopening() = runBlocking {
        val h = WebPaneHarness()
        val pane = h.open()
        stroke(pane)
        assertThat(h.handle.editor.content(h.chapter)!!.all().toList()).hasSize(1)
        h.close()
        val reopened = h.factory.open(h.dir)
        try {
            assertThat(reopened.readItems(h.chapter)).hasSize(1)
        } finally {
            reopened.close()
        }
    }

    @Test fun palmDownFirstThenWritingIsRecorded() = runBlocking {
        val h = WebPaneHarness()
        val pane = h.open()
        val palm = PalmRejector(palmMinSizePx = 8f * 227f / 25.4f, pxPerCm = 227f / 2.54f)
        val dispatcher = InputDispatcher(palm, GestureClassifier(touchSlopPx = 8f, multiTapSlopPx = 48f), HoverFocusDebouncer()) { }
        val panes = listOf<app.notate.core.input.PaneTarget>(pane)
        // A big contact (the side of the hand) lands first and stays down.
        dispatcher.onSample(PointerSample(1, PointerKind.FINGER, PointerAction.DOWN, 900f, 1200f, majorMm = 30f, timeMs = 0L), panes)
        fun pen(a: PointerAction, x: Float, y: Float, t: Long) =
            dispatcher.onSample(PointerSample(2, PointerKind.STYLUS, a, x, y, pressure = 0.5f, timeMs = t, pointerCount = 2), panes)
        assertThat(pen(PointerAction.POINTER_DOWN, 300f, 400f, 10L)).isTrue()
        for (i in 1..8) pen(PointerAction.MOVE, 300f + i * 25f, 400f + i * 4f, 10L + i * 8)
        pen(PointerAction.POINTER_UP, 500f, 432f, 100L)
        assertThat(h.handle.editor.content(h.chapter)!!.all().toList()).hasSize(1)
        h.close()
    }

    @Test fun writingRightAfterAPopupClosedIsRecorded() = runBlocking {
        val h = WebPaneHarness()
        val pane = h.open()
        h.arbiter.block(BlockReason.POPUP, true)
        h.arbiter.block(BlockReason.POPUP, false)
        stroke(pane)
        assertThat(h.handle.editor.content(h.chapter)!!.all().toList()).hasSize(1)
        h.close()
    }

    @Test fun aStrokeStartedWhileInkIsSuspendedIsStillKept() = runBlocking {
        val h = WebPaneHarness()
        val pane = h.open()
        h.arbiter.block(BlockReason.POPUP, true)        // e.g. the pen went down just as a popup was closing
        stroke(pane)
        assertThat(h.handle.editor.content(h.chapter)!!.all().toList()).hasSize(1)
        h.close()
    }
}
