package app.notate.render

import android.graphics.Bitmap
import app.notate.core.doc.content.PageContent
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.model.Background
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PageLayoutMode
import app.notate.render.tile.TileCache
import app.notate.render.tile.TileKey
import app.notate.render.tile.TileRasterizer
import app.notate.render.tile.TileScheduler
import app.notate.render.tile.TileSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TileSchedulerTest {
    private val pages = (1L..3L).map { PageInfo(PageId(it), it.toDouble(), 600f, 800f, Background.None, 0, 0) }
    private val layout = DocumentLayout(pages, PageLayoutMode.CONTINUOUS, gapPt = 16f)

    /** Level 0 (scale 1): tiles are 512 pt, each 600×800 page has 2×2 tiles. */
    private fun viewport(scrollY: Float = 0f) = Viewport(0f, scrollY, 1f, 1f, 400, 600)

    private class Source(val pages: List<PageInfo>) : TileSource {
        val revs = HashMap<PageId, Long>()
        override fun pageInfo(page: PageId) = pages.firstOrNull { it.id == page }
        override fun content(page: PageId) = PageContent.of(page, emptyList(), revs[page] ?: 1L)
    }

    private class Fake : TileRasterizer {
        val started = ArrayList<TileKey>()
        val finished = ArrayList<TileKey>()
        val cancelled = ArrayList<TileKey>()
        val gates = HashMap<TileKey, CompletableDeferred<Unit>>()
        var gateAll = false
        var onRender: (TileKey) -> Unit = {}
        override suspend fun render(key: TileKey, page: PageInfo, content: PageContent, out: Bitmap) {
            started.add(key)
            onRender(key)
            try {
                if (gateAll || gates.containsKey(key)) gates.getOrPut(key) { CompletableDeferred() }.await()
            } catch (e: CancellationException) {
                cancelled.add(key); throw e
            }
            finished.add(key)
        }
        fun open(key: TileKey) = gates[key]?.complete(Unit)
        fun openAll() { gateAll = false; gates.values.forEach { it.complete(Unit) } }
    }

    private class Env(val scope: TestScope, val cache: TileCache, val fake: Fake, val source: Source, val ready: MutableList<TileKey>, val sched: TileScheduler)

    private fun env(maxInFlight: Int = 2): Env {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val cache = TileCache(maxBytes = 64L * 1024 * 1024)
        val fake = Fake()
        val source = Source(pages)
        val ready = ArrayList<TileKey>()
        val sched = TileScheduler(cache, fake, source, scope, dispatcher, { ready.add(it) }, maxInFlight)
        return Env(scope, cache, fake, source, ready, sched)
    }

    private fun key(page: Long, col: Int, row: Int) = TileKey(PageId(page), 0, col, row)

    @Test fun rendersVisibleTilesThenPrefetchRing() {
        val e = env()
        e.sched.request(viewport(), layout)
        e.scope.advanceUntilIdle()
        // Visible: page 1, col 0, rows 0..1. Prefetch: rest of page 1 (col 1) and the top row of page 2.
        val visible = setOf(key(1, 0, 0), key(1, 0, 1))
        val prefetch = setOf(key(1, 1, 0), key(1, 1, 1), key(2, 0, 0), key(2, 1, 0))
        assertThat(e.fake.finished.toSet()).isEqualTo(visible + prefetch)
        assertThat(e.fake.finished.take(2).toSet()).isEqualTo(visible)       // visible before prefetch
        assertThat(e.ready.toSet()).isEqualTo(visible + prefetch)
        assertThat(e.cache.size).isEqualTo(6)
    }

    @Test fun nearestToCenterFirst() {
        val e = env(maxInFlight = 1)
        e.sched.request(viewport(), layout)
        e.scope.advanceUntilIdle()
        // Viewport centre is (200, 300): tile (0,0) centre (256,256) is nearer than (0,1) centre (256,768).
        assertThat(e.fake.started.first()).isEqualTo(key(1, 0, 0))
        assertThat(e.fake.started[1]).isEqualTo(key(1, 0, 1))
    }

    @Test fun eachKeyIsRenderedOnce() {
        val e = env()
        e.fake.gateAll = true
        e.sched.request(viewport(), layout)
        e.scope.runCurrent()
        e.sched.request(viewport(), layout)       // same viewport while the first tiles are still rendering
        e.sched.request(viewport(1f), layout)     // nearly the same
        e.scope.runCurrent()
        e.fake.openAll()
        e.scope.advanceUntilIdle()
        assertThat(e.fake.started).containsNoDuplicates()
        // Fresh tiles are never queued again.
        val n = e.fake.started.size
        e.sched.request(viewport(), layout)
        e.scope.advanceUntilIdle()
        assertThat(e.fake.started).hasSize(n)
    }

    @Test fun viewportChangeCancelsTilesNoLongerWanted() {
        val e = env()
        e.fake.gateAll = true
        e.sched.request(viewport(), layout)
        e.scope.runCurrent()
        assertThat(e.sched.inFlight).isEqualTo(2)
        val first = e.fake.started.toList()
        e.sched.request(viewport(scrollY = 1700f), layout)       // far away: page 3
        e.scope.runCurrent()
        assertThat(e.fake.cancelled).containsExactlyElementsIn(first)
        e.fake.openAll()
        e.scope.advanceUntilIdle()
        assertThat(e.fake.finished.none { it.page == PageId(1) }).isTrue()
        assertThat(e.fake.finished.all { it.page == PageId(3) || it.page == PageId(2) }).isTrue()
        assertThat(e.cache.size).isEqualTo(e.fake.finished.size)
    }

    @Test fun contentChangedWhileRenderingIsDiscardedAndRequeued() {
        val e = env(maxInFlight = 1)
        val k = key(1, 0, 0)
        e.fake.gates[k] = CompletableDeferred()
        e.sched.request(viewport(), layout)
        e.scope.runCurrent()
        assertThat(e.fake.started).containsExactly(k)
        e.source.revs[PageId(1)] = 2L                // page edited while the tile renders
        e.fake.open(k)
        e.fake.gates.remove(k)                       // second attempt does not block
        e.scope.advanceUntilIdle()
        assertThat(e.fake.started.count { it == k }).isEqualTo(2)
        assertThat(e.cache.peek(k)!!.rev).isEqualTo(2L)
        assertThat(e.ready.count { it == k }).isEqualTo(1)
    }

    @Test fun invalidateRerendersStaleTilesOnly() {
        val e = env()
        e.sched.request(viewport(), layout)
        e.scope.advanceUntilIdle()
        val before = e.fake.started.size
        e.source.revs[PageId(1)] = 5L
        e.sched.invalidate(PageId(1), app.notate.core.geometry.Rect(10f, 10f, 20f, 20f))     // only tile (0,0)
        e.scope.advanceUntilIdle()
        assertThat(e.fake.started.drop(before)).containsExactly(key(1, 0, 0))
        assertThat(e.cache.peek(key(1, 0, 0))!!.rev).isEqualTo(5L)
        assertThat(e.cache.isFresh(key(1, 0, 0))).isTrue()
    }

    @Test fun failedTileIsNotRetriedUntilInvalidated() {
        val e = env()
        val bad = key(1, 0, 0)
        e.fake.onRender = { if (it == bad) throw IllegalStateException("boom") }
        e.sched.request(viewport(), layout)
        e.scope.advanceUntilIdle()
        assertThat(e.fake.started.count { it == bad }).isEqualTo(1)
        e.sched.request(viewport(), layout)
        e.scope.advanceUntilIdle()
        assertThat(e.fake.started.count { it == bad }).isEqualTo(1)
        assertThat(e.cache.peek(bad)).isNull()
        assertThat(e.cache.size).isEqualTo(5)
    }
}
