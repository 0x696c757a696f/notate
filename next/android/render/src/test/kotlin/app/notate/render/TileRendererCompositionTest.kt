package app.notate.render

import android.graphics.Bitmap
import android.graphics.Canvas
import app.notate.core.doc.content.PageContent
import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.Background
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PaperPattern
import app.notate.render.ink.GeometryCache
import app.notate.render.ink.InkRenderer
import app.notate.render.paper.BackgroundRenderer
import app.notate.render.paper.BackgroundRendererSet
import app.notate.render.tile.BackgroundTileCache
import app.notate.render.tile.BackgroundTileSource
import app.notate.render.tile.TileBackgrounds
import app.notate.render.tile.TileGeometry
import app.notate.render.tile.TileKey
import app.notate.render.tile.TileRenderer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TileRendererCompositionTest {
    private class CountingPaper : BackgroundRenderer {
        var draws = 0
        override fun draw(canvas: Canvas, page: PageInfo, clipDoc: Rect) { draws++ }
    }

    private class FakePdf : BackgroundTileSource {
        val rendered = ArrayList<TileKey>()
        override fun handles(page: PageInfo) = page.background is Background.Pdf
        override suspend fun renderTile(key: TileKey, page: PageInfo, out: Bitmap) { rendered += key }
    }

    private val pdfPage = PageInfo(PageId(1), 1.0, 600f, 800f, Background.Pdf(AssetId("a"), 0), 0, 0)
    private val paperPage = PageInfo(
        PageId(2), 2.0, 600f, 800f, Background.Paper(PaperPattern.LINED, 20f), 0, 0,
    )
    private val paper = CountingPaper()
    private val pdf = FakePdf()
    private val cache = BackgroundTileCache(maxBytes = 8L * 1024 * 1024)
    private val renderer = TileRenderer(BackgroundRendererSet(paper), InkRenderer(GeometryCache()), TileBackgrounds(pdf, cache))

    private fun out() = Bitmap.createBitmap(TileGeometry.TILE_PX, TileGeometry.TILE_PX, Bitmap.Config.ARGB_8888)

    @Test fun cachedBackgroundIsNotRenderedAgain() = runBlocking {
        val key = TileKey(pdfPage.id, 0, 0, 0)
        val content = PageContent.of(pdfPage.id, emptyList(), 1L)
        renderer.render(key, pdfPage, content, out())
        renderer.render(key, pdfPage, PageContent.of(pdfPage.id, emptyList(), 2L), out()) // ink changed: new rev, same background
        assertThat(pdf.rendered).containsExactly(key)
        assertThat(paper.draws).isEqualTo(0)
        assertThat(cache.size).isEqualTo(1)
    }

    @Test fun eachTileGetsItsOwnBackground() = runBlocking {
        val content = PageContent.of(pdfPage.id, emptyList(), 1L)
        renderer.render(TileKey(pdfPage.id, 0, 0, 0), pdfPage, content, out())
        renderer.render(TileKey(pdfPage.id, 0, 1, 0), pdfPage, content, out())
        assertThat(pdf.rendered).hasSize(2)
    }

    @Test fun clearedCacheRendersAgain() = runBlocking {
        val key = TileKey(pdfPage.id, 0, 0, 0)
        val content = PageContent.of(pdfPage.id, emptyList(), 1L)
        renderer.render(key, pdfPage, content, out())
        cache.clear()
        renderer.render(key, pdfPage, content, out())
        assertThat(pdf.rendered).hasSize(2)
    }

    @Test fun paperPagesUseThePaperRenderer() = runBlocking {
        renderer.render(TileKey(paperPage.id, 0, 0, 0), paperPage, PageContent.of(paperPage.id, emptyList(), 1L), out())
        assertThat(pdf.rendered).isEmpty()
        assertThat(paper.draws).isEqualTo(1)
    }

    @Test fun cacheEvictsOldestAboveItsBudget() {
        val small = BackgroundTileCache(maxBytes = 2L * 512 * 512 * 4)
        val keys = (0..2).map { TileKey(pdfPage.id, 0, it, 0) }
        keys.forEach { small.put(it, out()) }
        assertThat(small.get(keys[0])).isNull()
        assertThat(small.get(keys[1])).isNotNull()
        assertThat(small.get(keys[2])).isNotNull()
        small.evictPage(pdfPage.id)
        assertThat(small.size).isEqualTo(0)
        assertThat(small.sizeBytes).isEqualTo(0)
    }
}
