package app.notate.core.input

import app.notate.core.input.FingerGesture.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureClassifierTest {
    private val g = GestureClassifier(touchSlopPx = 10f, longPressMs = 500, multiTapMaxMs = 250, multiTapSlopPx = 20f)

    private fun s(
        a: PointerAction,
        id: Int,
        x: Float,
        y: Float,
        t: Long,
    ) = PointerSample(id, PointerKind.FINGER, a, x, y, timeMs = t)

    private fun feed(vararg ss: PointerSample) = ss.flatMap { g.onSample(it) }

    @Test
    fun `single finger drag pans`() {
        val out =
            feed(
                s(PointerAction.DOWN, 0, 100f, 100f, 0),
                s(PointerAction.MOVE, 0, 105f, 100f, 10),
                s(PointerAction.MOVE, 0, 130f, 100f, 20),
                s(PointerAction.MOVE, 0, 160f, 100f, 40),
                s(PointerAction.UP, 0, 160f, 100f, 50),
            )
        assertEquals(PanStart(100f, 100f), out[0])
        assertEquals(Pan(30f, 0f), out[1])
        assertEquals(Pan(30f, 0f), out[2])
        val end = out.last() as PanEnd
        assertTrue(end.vx > 500f)
        assertTrue(g.isIdle)
    }

    @Test
    fun `two finger pinch scales`() {
        val out =
            feed(
                s(PointerAction.DOWN, 0, 100f, 100f, 0),
                s(PointerAction.POINTER_DOWN, 1, 200f, 100f, 10),
                s(PointerAction.MOVE, 1, 300f, 100f, 50),
            )
        assertEquals(PinchStart(150f, 100f), out[0])
        val p = out[1] as Pinch
        assertEquals(2f, p.scale, 1e-4f)
        assertEquals(200f, p.fx, 1e-4f)
        assertEquals(50f, p.dx, 1e-4f)
        assertEquals(listOf(PinchEnd), feed(s(PointerAction.POINTER_UP, 1, 300f, 100f, 80)))
    }

    @Test
    fun `two finger twist reports the rotation`() {
        val out =
            feed(
                s(PointerAction.DOWN, 0, 100f, 100f, 0),
                s(PointerAction.POINTER_DOWN, 1, 200f, 100f, 10),
                s(PointerAction.MOVE, 1, 200f, 200f, 50), // second finger swings from east to south of the first: 45° clockwise (y down)
            )
        val p = out.filterIsInstance<Pinch>().first()
        assertEquals(45f, p.rotationDeg, 1e-3f)
        assertEquals(0f, (feed(s(PointerAction.MOVE, 1, 200f, 200f, 60)).filterIsInstance<Pinch>().firstOrNull()?.rotationDeg ?: 0f), 1e-3f)
    }

    @Test
    fun `two finger tap`() {
        val out =
            feed(
                s(PointerAction.DOWN, 0, 100f, 100f, 0),
                s(PointerAction.POINTER_DOWN, 1, 200f, 100f, 20),
                s(PointerAction.POINTER_UP, 1, 200f, 100f, 100),
                s(PointerAction.UP, 0, 100f, 100f, 110),
            )
        assertEquals(listOf(Tap(2, 150f, 100f)), out)
    }

    @Test
    fun `three finger tap`() {
        val out =
            feed(
                s(PointerAction.DOWN, 0, 100f, 100f, 0),
                s(PointerAction.POINTER_DOWN, 1, 200f, 100f, 20),
                s(PointerAction.POINTER_DOWN, 2, 300f, 100f, 40),
                s(PointerAction.POINTER_UP, 2, 300f, 100f, 100),
                s(PointerAction.POINTER_UP, 1, 200f, 100f, 110),
                s(PointerAction.UP, 0, 100f, 100f, 120),
            )
        assertEquals(1, out.size)
        assertEquals(3, (out[0] as Tap).fingers)
    }

    @Test
    fun `late second finger is not a multi tap`() {
        val out =
            feed(
                s(PointerAction.DOWN, 0, 100f, 100f, 0),
                s(PointerAction.POINTER_DOWN, 1, 200f, 100f, 300),
                s(PointerAction.POINTER_UP, 1, 200f, 100f, 350),
                s(PointerAction.UP, 0, 100f, 100f, 360),
            )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `single tap`() {
        val out = feed(s(PointerAction.DOWN, 0, 5f, 6f, 0), s(PointerAction.UP, 0, 5f, 6f, 120))
        assertEquals(listOf(Tap(1, 5f, 6f)), out)
    }

    @Test
    fun `long press fires after 500 ms`() {
        feed(s(PointerAction.DOWN, 0, 5f, 6f, 0))
        assertTrue(g.onTimer(499).isEmpty())
        assertEquals(listOf(LongPress(5f, 6f)), g.onTimer(500))
        assertTrue(g.onTimer(900).isEmpty())
        assertTrue(feed(s(PointerAction.UP, 0, 5f, 6f, 1000)).isEmpty())
    }

    @Test
    fun `moving past slop is not a tap`() {
        val out =
            feed(
                s(PointerAction.DOWN, 0, 0f, 0f, 0),
                s(PointerAction.MOVE, 0, 30f, 0f, 20),
                s(PointerAction.UP, 0, 0f, 0f, 60),
            )
        assertTrue(out.none { it is Tap })
        assertTrue(out.any { it is PanStart })
    }

    @Test
    fun `pan turns into pinch when second finger lands`() {
        val out =
            feed(
                s(PointerAction.DOWN, 0, 100f, 100f, 0),
                s(PointerAction.MOVE, 0, 150f, 100f, 20),
                s(PointerAction.POINTER_DOWN, 1, 250f, 100f, 40),
            )
        assertTrue(out.any { it is PanEnd })
        assertEquals(PinchStart(200f, 100f), out.last())
    }

    @Test
    fun `cancel emits Cancel only once a gesture began`() {
        feed(s(PointerAction.DOWN, 0, 0f, 0f, 0))
        assertTrue(g.cancel().isEmpty())
        feed(s(PointerAction.DOWN, 0, 0f, 0f, 0), s(PointerAction.MOVE, 0, 50f, 0f, 10))
        assertEquals(listOf(Cancel), g.cancel())
        assertTrue(g.isIdle)
    }
}
