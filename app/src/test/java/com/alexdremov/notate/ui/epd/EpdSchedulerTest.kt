package com.alexdremov.notate.ui.epd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpdSchedulerTest {
    private class FakePort : EpdPort<String> {
        val calls = ArrayList<String>()
        val pendingDraw = ArrayList<() -> Unit>()
        val timers = LinkedHashMap<Int, () -> Unit>()
        private var nextTimer = 0

        override fun enterFast() {
            calls += "fast+"
        }

        override fun exitFast() {
            calls += "fast-"
        }

        override fun enterAnimation() {
            calls += "anim+"
        }

        override fun exitAnimation() {
            calls += "anim-"
        }

        override fun invalidate(
            view: String,
            rect: IntArray?,
            mode: EpdMode,
        ) {
            calls += "invalidate($view,$mode,${rect?.toList()})"
        }

        override fun afterDraw(
            view: String,
            action: () -> Unit,
        ) {
            pendingDraw += action
        }

        override fun postDelayed(
            delayMs: Long,
            action: () -> Unit,
        ): Any {
            val id = nextTimer++
            timers[id] = action
            return id
        }

        override fun cancel(handle: Any) {
            timers.remove(handle as Int)
        }
    }

    private val port = FakePort()
    private val epd = EpdScheduler(port)

    @Test
    fun `fast mode stays on until the last owner lets go`() {
        epd.acquireFast("pan")
        epd.acquireFast("selection")
        epd.releaseFast("pan")
        assertTrue(epd.isFast)
        epd.releaseFast("selection")
        assertFalse(epd.isFast)
        assertEquals(listOf("fast+", "fast-"), port.calls)
    }

    @Test
    fun `acquiring twice is one hold and releasing a stranger does nothing`() {
        epd.acquireFast("pan")
        epd.acquireFast("pan")
        epd.releaseFast("nobody")
        assertTrue(epd.isFast)
        epd.releaseFast("pan")
        assertEquals(listOf("fast+", "fast-"), port.calls)
    }

    @Test
    fun `animation and fast mode are independent`() {
        epd.acquireAnimation("pen")
        epd.acquireFast("pan")
        epd.releaseAnimation("pen")
        assertEquals(listOf("anim+", "fast+", "anim-"), port.calls)
        assertTrue(epd.isFast)
    }

    @Test
    fun `a hold nobody released expires`() {
        epd.acquireFast("leak")
        port.timers.values.single().invoke()
        assertFalse(epd.isFast)
        assertEquals(listOf("fast+", "fast-"), port.calls)
    }

    @Test
    fun `reacquiring restarts the watchdog instead of stacking them`() {
        epd.acquireFast("pan")
        epd.acquireFast("pan")
        assertEquals(1, port.timers.size)
        epd.releaseFast("pan")
        assertTrue(port.timers.isEmpty())
    }

    @Test
    fun `after-draw refresh waits for the draw`() {
        epd.refresh("page", EpdMode.GU, intArrayOf(1, 2, 3, 4), EpdTiming.AFTER_DRAW)
        assertTrue(port.calls.isEmpty())
        port.pendingDraw.single().invoke()
        assertEquals(listOf("invalidate(page,GU,[1, 2, 3, 4])"), port.calls)
    }

    @Test
    fun `an immediate refresh does not wait`() {
        epd.refresh("page", EpdMode.GC)
        assertEquals(listOf("invalidate(page,GC,null)"), port.calls)
        assertTrue(port.pendingDraw.isEmpty())
    }
}
