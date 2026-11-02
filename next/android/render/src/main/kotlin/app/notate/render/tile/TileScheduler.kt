package app.notate.render.tile

import android.util.Log
import app.notate.core.doc.content.PageContent
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.geometry.Rect
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the scheduler renders from. Main thread. */
interface TileSource {
    fun pageInfo(page: PageId): PageInfo?

    /** Current content snapshot, or null while the page is not loaded yet (the caller re-requests after loading). */
    fun content(page: PageId): PageContent?
}

/**
 * Decides which tiles to render and in which order. Main thread only ([scope] must run on the main thread).
 *
 * [request] computes the visible tiles plus a one-tile prefetch ring around them; missing or stale ones are queued
 * (visible before prefetch, nearest to the viewport center first) and rendered on [renderDispatcher], at most
 * [maxInFlight] at a time, so a new viewport can re-prioritize everything that has not started. A key is queued once;
 * tiles that are no longer wanted are cancelled. If the page content changed while a tile was rendering, the result is
 * dropped and the tile re-queued. [onTileReady] runs on the main thread after a tile entered the cache.
 */
class TileScheduler(
    private val cache: TileCache,
    private val rasterizer: TileRasterizer,
    private val source: TileSource,
    private val scope: CoroutineScope,
    private val renderDispatcher: CoroutineDispatcher,
    private val onTileReady: (TileKey) -> Unit,
    private val maxInFlight: Int = 2,
) {
    private class Wanted(val key: TileKey, val prefetch: Boolean, val dist: Float)
    private class Running(val job: Job)

    private var wanted: List<Wanted> = emptyList()
    private val wantedKeys: MutableSet<TileKey> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    // Concurrent maps: tests (and a misconfigured scope) can resume tile jobs on other threads than the one calling cancelAll.
    private val running = java.util.concurrent.ConcurrentHashMap<TileKey, Running>()
    private val failed: MutableSet<TileKey> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var lastViewport: Viewport? = null
    private var lastLayout: DocumentLayout? = null

    /** Number of tiles currently rendering. */
    val inFlight: Int get() = running.size

    /** Recomputes the wanted set for [viewport] / [layout], cancels what is no longer wanted and starts rendering. */
    fun request(viewport: Viewport, layout: DocumentLayout) {
        lastViewport = viewport
        lastLayout = layout
        val level = Viewport.levelFor(viewport.scale)
        val tilePt = TileGeometry.sizePt(level)
        val visible = viewport.visibleDocRect()
        val ring = visible.inset(-tilePt)
        val cx = (visible.l + visible.r) / 2f
        val cy = (visible.t + visible.b) / 2f
        val out = ArrayList<Wanted>()
        for (page in layout.visiblePages(ring)) {
            val info = source.pageInfo(page) ?: continue
            val f = layout.frameOf(page)
            val bounds = info.height?.let { Rect(0f, 0f, info.width, it) }
            val visKeys = TileGeometry.cover(page, level, visible.offset(-f.l, -f.t), bounds)
            val visSet = visKeys.toHashSet()
            for (k in visKeys) out.add(Wanted(k, false, dist(k, f, cx, cy)))
            for (k in TileGeometry.cover(page, level, ring.offset(-f.l, -f.t), bounds)) {
                if (k !in visSet) out.add(Wanted(k, true, dist(k, f, cx, cy)))
            }
        }
        out.sortWith(compareBy<Wanted>({ it.prefetch }, { it.dist }))
        wanted = out
        wantedKeys.clear()
        out.forEach { wantedKeys.add(it.key) }
        failed.retainAll(wantedKeys)
        for (k in running.keys.filter { it !in wantedKeys }) cancel(k)
        pump()
    }

    /**
     * Marks tiles of [page] intersecting [pageRect] (page coordinates; null = whole page) stale, cancels renders of them
     * and re-runs the last [request]. Call when `ContentChange` arrives or the page background changed.
     */
    fun invalidate(page: PageId, pageRect: Rect?) {
        cache.invalidate(page, pageRect)
        failed.removeAll { it.page == page && (pageRect == null || TileGeometry.rectOf(it).intersects(pageRect)) }
        for (k in running.keys.filter { it.page == page && (pageRect == null || TileGeometry.rectOf(it).intersects(pageRect)) }) cancel(k)
        val vp = lastViewport
        val layout = lastLayout
        if (vp != null && layout != null) request(vp, layout) else pump()
    }

    /** Cancels everything (view detached). Cached tiles stay. */
    fun cancelAll() {
        for (k in running.keys.toList()) cancel(k)
        wanted = emptyList()
        wantedKeys.clear()
    }

    private fun dist(k: TileKey, frame: Rect, cx: Float, cy: Float): Float {
        val c = TileGeometry.rectOf(k).center
        val dx = frame.l + c.x - cx
        val dy = frame.t + c.y - cy
        return dx * dx + dy * dy
    }

    private fun needsRender(k: TileKey) = !cache.isFresh(k) && k !in failed && !running.containsKey(k)

    private fun pump() {
        if (running.size >= maxInFlight) return
        for (w in wanted) {
            if (running.size >= maxInFlight) break
            if (needsRender(w.key)) start(w.key)
        }
    }

    private fun cancel(key: TileKey) {
        running.remove(key)?.job?.cancel()
    }

    private fun start(key: TileKey) {
        val info = source.pageInfo(key.page) ?: return
        val content = source.content(key.page) ?: return
        val bitmap = cache.pool.obtain()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                withContext(renderDispatcher) { rasterizer.render(key, info, content, bitmap) }
            } catch (e: CancellationException) {
                cache.pool.release(bitmap)
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Tile render failed for $key", e)
                cache.pool.release(bitmap)
                if (running[key]?.job === self) running.remove(key)
                failed.add(key)
                pump()
                return@launch
            }
            if (running[key]?.job === self) running.remove(key)
            val current = source.content(key.page)
            if (current != null && current.rev == content.rev && key in wantedKeys) {
                cache.put(key, bitmap, content.rev)
                onTileReady(key)
            } else {
                cache.pool.release(bitmap)    // content moved on (or tile no longer wanted): drop; pump re-queues if still needed
            }
            pump()
        }
        running[key] = Running(job)
        job.start()
    }

    private companion object { const val TAG = "TileScheduler" }
}
