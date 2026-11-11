package app.notate.render.tile

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import app.notate.core.doc.content.PageContent
import app.notate.core.doc.layout.Viewport
import app.notate.core.model.PageInfo
import app.notate.render.PageDrawer
import app.notate.render.ink.InkRenderer
import app.notate.render.paper.BackgroundRendererSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Rasterizes one tile; the seam [TileScheduler] is tested against. */
interface TileRasterizer {
    suspend fun render(key: TileKey, page: PageInfo, content: PageContent, out: Bitmap)
}

/** Renders tiles on the render thread. Not thread-safe: use from one thread only. */
class TileRenderer(
    backgrounds: BackgroundRendererSet,
    ink: InkRenderer,
    private val tileBackgrounds: TileBackgrounds? = null,
) : TileRasterizer {
    private val drawer = PageDrawer(backgrounds, ink)

    /**
     * Clears [out] (white; transparent for Web pages), draws the background and the items intersecting the tile, clipped to
     * the page frame (fixed-size pages). [out] must be [TileGeometry.TILE_PX] square.
     * A page whose background comes from [tileBackgrounds] (PDF) gets its background tile from the cache, rendered by the
     * source only when missing, so ink changes never rasterize the PDF again.
     */
    override suspend fun render(key: TileKey, page: PageInfo, content: PageContent, out: Bitmap) {
        val job: Job? = currentCoroutineContext()[Job]
        out.eraseColor(if (BackgroundRendererSet.isTransparent(page)) Color.TRANSPARENT else Color.WHITE)
        val rect = TileGeometry.rectOf(key)
        val canvas = Canvas(out)
        val tb = tileBackgrounds?.takeIf { it.source.handles(page) }
        if (tb != null) canvas.drawBitmap(backgroundTile(tb, key, page), 0f, 0f, null)
        val s = Viewport.scaleOf(key.level)
        canvas.scale(s, s)
        canvas.translate(-rect.l, -rect.t)
        drawer.draw(canvas, page, content, rect, drawBackground = tb == null) { job?.ensureActive() }
    }

    private suspend fun backgroundTile(tb: TileBackgrounds, key: TileKey, page: PageInfo): Bitmap {
        tb.cache.get(key)?.let { return it }
        val bg = Bitmap.createBitmap(TileGeometry.TILE_PX, TileGeometry.TILE_PX, Bitmap.Config.ARGB_8888)
        try {
            tb.source.renderTile(key, page, bg)
        } catch (t: Throwable) {
            bg.recycle()
            throw t
        }
        tb.cache.put(key, bg)
        return bg
    }
}
