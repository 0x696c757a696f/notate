package com.alexdremov.notate.data.region

import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.onyx.android.sdk.data.note.TouchPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Lost-stroke race: a region's dirty flag must never be cleared while its newest
 * mutation is not on disk (see plans/storage-race-fix.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegionDirtyRaceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val all = RectF(-1e7f, -1e7f, 1e7f, 1e7f)

    private fun stroke(
        order: Long,
        x0: Float,
        y0: Float,
    ): Stroke {
        val pts = ArrayList<TouchPoint>(6)
        val path = android.graphics.Path()
        for (i in 0 until 6) {
            val x = x0 + i * 2f
            val y = y0 + i
            pts.add(TouchPoint(x, y, 0.5f, 4f, i.toLong()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val bounds = RectF()
        path.computeBounds(bounds, true)
        return Stroke(
            path,
            pts,
            0xFF000000.toInt(),
            width = 2f,
            style = StrokeType.FOUNTAIN,
            bounds = bounds,
            strokeOrder = order,
        )
    }

    private fun orders(rm: RegionManager): Set<Long> = runBlocking { rm.queryItems(all) }.map { it.order }.toSet()

    private fun reopenedOrders(dir: File): Set<Long> {
        val rm = RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f)
        return try {
            orders(rm)
        } finally {
            rm.clear()
        }
    }

    /** Records which region the current thread is saving, so a hook can mutate exactly that one. */
    private class TrackingStorage(
        dir: File,
    ) : RegionStorage(dir) {
        val lastSaved = ThreadLocal<RegionId?>()
        val savedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<RegionId>()

        override fun saveRegion(data: RegionData): Boolean {
            lastSaved.set(data.id)
            savedIds.add(data.id)
            return super.saveRegion(data)
        }
    }

    @Test
    fun `mutation landing between async write and dirty clear is not lost`() {
        val dir = tmp.newFolder()
        val storage = TrackingStorage(dir).apply { init() }
        val rm = RegionManager(storage, regionSize = 1000f, memoryLimitBytes = 8 * 1024L)
        val expected = HashSet<Long>()
        val fired = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>(null)
        val hookOrder = 900_000L
        rm.saveHookBeforeDirtyClear = {
            val id = storage.lastSaved.get()
            if (id != null && fired.compareAndSet(false, true)) {
                try {
                    // No lock is held here: a normal mutation on the IO thread.
                    runBlocking { rm.addItem(stroke(hookOrder, id.x * 1000f + 500f, id.y * 1000f + 500f)) }
                } catch (t: Throwable) {
                    error.set(t)
                }
            }
        }
        try {
            var next = 1L
            var i = 0
            while (!fired.get() && i < 400) {
                runBlocking { rm.addItem(stroke(next, i * 1000f + 100f, 100f)) }
                expected.add(next++)
                i++
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (!fired.get() && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertTrue("setup: async save never ran", fired.get())
            Thread.sleep(300)
            error.get()?.let { throw it }
            expected.add(hookOrder)

            rm.saveAll()
            assertEquals("in-memory content", expected, orders(rm))
            rm.clear()
            assertEquals("after reopen", expected, reopenedOrders(dir))
        } finally {
            rm.saveHookBeforeDirtyClear = null
        }
    }

    @Test
    fun `saveAll hook between write and dirty clear keeps late mutation`() {
        val dir = tmp.newFolder()
        val storage = TrackingStorage(dir).apply { init() }
        val rm = RegionManager(storage, regionSize = 1000f, memoryLimitBytes = 8 * 1024L)
        val expected = HashSet<Long>()
        val fired = AtomicBoolean(false)
        rm.saveAllHookAfterWrite = {
            if (fired.compareAndSet(false, true)) {
                runBlocking { rm.addItem(stroke(950_000L, 500f, 500f)) }
                expected.add(950_000L)
            }
        }
        for (k in 1L..5L) {
            runBlocking { rm.addItem(stroke(k, 100f + k * 20f, 100f)) }
            expected.add(k)
        }
        rm.saveAll()
        assertTrue("hook never ran", fired.get())
        rm.saveAll()
        assertEquals(expected, orders(rm))
        rm.clear()
        assertEquals(expected, reopenedOrders(dir))
    }

    @Test
    fun `concurrent adds saveAll and eviction lose nothing`() {
        repeat(20) { iter ->
            val dir = tmp.newFolder()
            val storage = RegionStorage(dir).apply { init() }
            val rm = RegionManager(storage, regionSize = 1000f, memoryLimitBytes = 8 * 1024L)
            val done = AtomicBoolean(false)
            val failure = AtomicReference<Throwable?>(null)
            val n = 300

            val p =
                thread {
                    try {
                        for (i in 1..n) runBlocking { rm.addItem(stroke(i.toLong(), 100f + (i % 40) * 10f, 100f + (i % 7) * 10f)) }
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                }
            val q =
                thread {
                    try {
                        while (!done.get()) {
                            rm.saveAll()
                            runBlocking { rm.queryItems(RectF(0f, 0f, 1000f, 1000f)) }
                        }
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                }
            val r =
                thread {
                    try {
                        var k = 0
                        while (!done.get() && k < 150) {
                            // Strokes in other regions force eviction/parking of the hot region.
                            runBlocking { rm.addItem(stroke(100_000L + k, (k % 12 + 1) * 1000f + 50f, 50f)) }
                            k++
                        }
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                }
            p.join()
            r.join()
            done.set(true)
            q.join()
            failure.get()?.let { throw it }

            rm.saveAll()
            val hot = (1..n).map { it.toLong() }.toSet()
            val mem = orders(rm)
            assertTrue("iter=$iter missing in memory: ${hot - mem}", mem.containsAll(hot))
            val expectedAll = mem
            rm.clear()
            val disk = reopenedOrders(dir)
            assertEquals("iter=$iter after reopen", expectedAll, disk)
        }
    }
}
