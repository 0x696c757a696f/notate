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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression for "strokes lost after reopening a note"
 * (UserSessionStressTest seed=99 on CI: missing=[600001, 600002, 600003]).
 *
 * An evicted dirty region is persisted by an async save that holds the
 * region's save gate. saveAll() used to SKIP gate-busy regions and return
 * while that save was still queued or mid-write; the caller then packed the
 * container / cleared the session (which aborts queued saves) without the
 * region's newest bytes. saveAll() must not return before they are on disk.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SaveAllInFlightSaveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Async (eviction) saves go through [saveRegion]; hold them until released. */
    private class GatedStorage(
        dir: File,
    ) : RegionStorage(dir) {
        val release = CountDownLatch(1)
        val blockedIds: MutableSet<RegionId> = ConcurrentHashMap.newKeySet()

        override fun saveRegion(data: RegionData): Boolean {
            blockedIds.add(data.id)
            release.await(30, TimeUnit.SECONDS)
            return super.saveRegion(data)
        }
    }

    private fun stroke(
        order: Long,
        x0: Float,
        y0: Float,
    ): Stroke {
        val pts = ArrayList<TouchPoint>(8)
        val path = android.graphics.Path()
        for (i in 0 until 8) {
            val x = x0 + i * 5f
            val y = y0 + i * 3f
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

    @Test
    fun `saveAll waits for an in-flight eviction save before returning`() {
        val dir = tmp.newFolder()
        val storage = GatedStorage(dir).apply { init() }
        val rm = RegionManager(storage, regionSize = 1000f, memoryLimitBytes = 16 * 1024L)
        val target = RegionId(0, 0)

        try {
            val targetOrders = (1L..5L).toSet()
            runBlocking { targetOrders.forEach { rm.addItem(stroke(it, 100f + it * 60f, 100f)) } }

            // Write elsewhere until the target region is evicted and its async
            // save is in flight (held by the gate).
            var next = 1000L
            var i = 1
            while (target !in storage.blockedIds && i <= 400) {
                runBlocking { rm.addItem(stroke(next++, i * 1000f + 100f, 100f)) }
                i++
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (target !in storage.blockedIds && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertTrue("setup: target region was never evicted", target in storage.blockedIds)

            // Let the held saves finish a bit later, from another thread.
            Thread {
                Thread.sleep(300)
                storage.release.countDown()
            }.start()

            runBlocking { rm.saveAll() }

            // saveAll returned: the target's strokes must already be on disk.
            val onDisk =
                RegionStorage(dir)
                    .loadRegion(target)
                    ?.items
                    ?.map { it.order }
                    ?.toSet()
                    .orEmpty()
            assertEquals("strokes not on disk when saveAll returned", targetOrders, onDisk)

            // And a fresh open after close sees everything.
            rm.clear()
            val reopened = RegionManager(RegionStorage(dir).apply { init() }, regionSize = 1000f)
            val all =
                runBlocking { reopened.queryItems(RectF(-1e6f, -1e6f, 1e6f, 1e6f)) }
                    .map { it.order }
                    .toSet()
            assertTrue("missing after reopen: ${targetOrders - all}", all.containsAll(targetOrders))
            assertEquals((targetOrders + (1000L until next)), all)
            reopened.clear()
        } finally {
            storage.release.countDown()
        }
    }
}
