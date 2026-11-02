package app.notate.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputDispatcherTest {
    private class FakePane(
        override val id: PaneId,
        private val x0: Float,
        private val x1: Float,
        override var penMode: InputPenMode = InputPenMode.WRITE,
        private val log: MutableList<String>,
    ) : PaneTarget {
        val gestures = ArrayList<FingerGesture>()
        val pens = ArrayList<PenEvent>()

        override fun hitTest(
            x: Float,
            y: Float,
        ) = x in x0..x1

        override fun onFingerGesture(g: FingerGesture) {
            log += "${id.value}:gesture"
            gestures += g
        }

        override fun onPen(ev: PenEvent) {
            log += "${id.value}:pen"
            pens += ev
        }

        var constraintTool = false
        val constraints = ArrayList<Boolean>()
        override val takesConstraintFinger: Boolean get() = constraintTool

        override fun onConstraint(on: Boolean) {
            constraints += on
        }
    }

    private val log = ArrayList<String>()
    private val a = FakePane(PaneId("A"), 0f, 499f, log = log)
    private val b = FakePane(PaneId("B"), 500f, 1000f, log = log)
    private val panes = listOf<PaneTarget>(a, b)
    private val calibrator = TouchSizeCalibrator(10f).also { c -> repeat(20) { c.add(80f) } }
    private val palm = PalmRejector(palmMinSizePx = 200f, graceMs = 300L, pxPerCm = 100f, calibrator = calibrator)
    private val gestures = GestureClassifier(touchSlopPx = 10f, multiTapSlopPx = 20f)
    private val d =
        InputDispatcher(palm, gestures, HoverFocusDebouncer(80L)) { id ->
            log += "focus:${id.value}"
        }.also { it.focusedPane = a.id }

    private fun pen(
        a: PointerAction,
        x: Float,
        y: Float,
        t: Long,
        kind: PointerKind = PointerKind.STYLUS,
        buttons: Int = 0,
    ) = PointerSample(100, kind, a, x, y, pressure = 0.5f, buttons = buttons, timeMs = t)

    private fun finger(
        a: PointerAction,
        id: Int,
        x: Float,
        y: Float,
        t: Long,
        majorMm: Float = 8f,
    ) = PointerSample(id, PointerKind.FINGER, a, x, y, majorMm = majorMm, timeMs = t)

    @Test
    fun `palm sized contact is dropped`() {
        assertTrue(d.onSample(finger(PointerAction.DOWN, 0, 100f, 100f, 10_000, majorMm = 40f), panes))
        assertTrue(a.gestures.isEmpty())
        d.onSample(finger(PointerAction.UP, 0, 100f, 100f, 10_050, majorMm = 40f), panes)
    }

    @Test
    fun `pen down on unfocused pane focuses first`() {
        assertTrue(d.onSample(pen(PointerAction.DOWN, 700f, 10f, 100), panes))
        assertEquals(listOf("focus:B", "B:pen"), log)
        assertTrue(b.pens[0] is PenEvent.Down)
        d.onSample(pen(PointerAction.MOVE, 710f, 10f, 110), panes)
        d.onSample(pen(PointerAction.UP, 710f, 10f, 120), panes)
        assertEquals(3, b.pens.size)
        assertTrue(a.pens.isEmpty())
    }

    @Test
    fun `hover over other pane switches focus after 80 ms`() {
        d.onSample(pen(PointerAction.HOVER_ENTER, 700f, 10f, 1000), panes)
        d.onSample(pen(PointerAction.HOVER_MOVE, 700f, 10f, 1079), panes)
        assertFalse(log.contains("focus:B"))
        d.onSample(pen(PointerAction.HOVER_MOVE, 700f, 10f, 1080), panes)
        assertTrue(log.contains("focus:B"))
        assertTrue(b.pens.last() is PenEvent.Hover)
    }

    @Test
    fun `side button and eraser end erase`() {
        d.onSample(pen(PointerAction.DOWN, 10f, 10f, 100, buttons = PointerSample.BUTTON_STYLUS_PRIMARY), panes)
        assertTrue((a.pens[0] as PenEvent.Down).eraser)
        d.onSample(pen(PointerAction.UP, 10f, 10f, 110), panes)
        d.onSample(pen(PointerAction.DOWN, 10f, 10f, 5_000, kind = PointerKind.ERASER_END), panes)
        assertTrue((a.pens[2] as PenEvent.Down).eraser)
        d.onSample(pen(PointerAction.UP, 10f, 10f, 5_010), panes)
        d.onSample(pen(PointerAction.DOWN, 10f, 10f, 9_000), panes)
        assertFalse((a.pens.last() as PenEvent.Down).eraser)
    }

    @Test
    fun `finger gesture keeps its pane across the divider`() {
        d.onSample(finger(PointerAction.DOWN, 0, 400f, 100f, 10_000), panes)
        d.onSample(finger(PointerAction.MOVE, 0, 450f, 100f, 10_020), panes)
        d.onSample(finger(PointerAction.MOVE, 0, 700f, 100f, 10_040), panes)
        d.onSample(finger(PointerAction.UP, 0, 700f, 100f, 10_060), panes)
        assertTrue(b.gestures.isEmpty())
        assertTrue(a.gestures.any { it is FingerGesture.Pan })
    }

    @Test
    fun `tap on unfocused pane focuses then forwards`() {
        d.onSample(finger(PointerAction.DOWN, 0, 700f, 100f, 10_000), panes)
        d.onSample(finger(PointerAction.UP, 0, 700f, 100f, 10_050), panes)
        assertEquals(listOf("focus:B", "B:gesture"), log)
    }

    @Test
    fun `finger mode off does not consume`() {
        d.fingersEnabled = false
        assertFalse(d.onSample(finger(PointerAction.DOWN, 0, 100f, 100f, 10_000), panes))
        assertTrue(log.isEmpty())
    }

    @Test
    fun `interact mode does not consume pen`() {
        a.penMode = InputPenMode.INTERACT
        assertFalse(d.onSample(pen(PointerAction.DOWN, 10f, 10f, 100), panes))
        assertFalse(d.onSample(pen(PointerAction.MOVE, 20f, 10f, 110), panes))
        assertTrue(a.pens.isEmpty())
    }

    @Test
    fun `pen arriving mid pan cancels the gesture`() {
        d.onSample(finger(PointerAction.DOWN, 0, 100f, 100f, 10_000), panes)
        d.onSample(finger(PointerAction.MOVE, 0, 150f, 100f, 10_020), panes)
        d.onDriverStroke(true, 10_030)
        assertTrue(d.onSample(finger(PointerAction.MOVE, 0, 200f, 100f, 10_040), panes))
        assertEquals(FingerGesture.Cancel, a.gestures.last())
        d.onSample(finger(PointerAction.MOVE, 0, 250f, 100f, 10_050), panes)
        assertEquals(1, a.gestures.count { it == FingerGesture.Cancel })
    }

    @Test
    fun `second hand finger becomes the constraint while the shape tool draws`() {
        a.constraintTool = true
        d.onSample(pen(PointerAction.DOWN, 100f, 100f, 100), panes)
        d.onSample(pen(PointerAction.MOVE, 120f, 100f, 110), panes)
        assertTrue(d.onSample(finger(PointerAction.POINTER_DOWN, 1, 900f, 100f, 120), panes))   // 7.8 cm away
        assertEquals(listOf(true), a.constraints)
        d.onSample(finger(PointerAction.MOVE, 1, 905f, 100f, 130), panes)
        d.onSample(finger(PointerAction.POINTER_UP, 1, 905f, 100f, 140), panes)
        assertEquals(listOf(true, false), a.constraints)
        assertTrue(a.gestures.isEmpty() && b.gestures.isEmpty())
        d.onSample(pen(PointerAction.UP, 120f, 100f, 150), panes)
    }

    @Test
    fun `without the shape tool a finger during pen contact is rejected as before`() {
        d.onSample(pen(PointerAction.DOWN, 100f, 100f, 100), panes)
        assertTrue(d.onSample(finger(PointerAction.POINTER_DOWN, 1, 900f, 100f, 120), panes))
        assertTrue(a.constraints.isEmpty())
        d.onSample(finger(PointerAction.POINTER_UP, 1, 900f, 100f, 130), panes)
        d.onSample(pen(PointerAction.UP, 120f, 100f, 150), panes)
    }

    @Test
    fun `constraint is released when the pen lifts first`() {
        a.constraintTool = true
        d.onSample(pen(PointerAction.DOWN, 100f, 100f, 100), panes)
        d.onSample(finger(PointerAction.POINTER_DOWN, 1, 900f, 100f, 120), panes)
        d.onSample(pen(PointerAction.UP, 110f, 100f, 130), panes)
        assertEquals(listOf(true, false), a.constraints)
    }
}
