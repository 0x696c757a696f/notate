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
class TileRenderer(backgrounds: BackgroundRendererSet, ink: InkRenderer) : TileRasterizer {
    private val drawer = PageDrawer(backgrounds, ink)

    /**
     * Clears [out] (white; transparent for Web pages), draws the background and the items intersecting the tile, clipped to
     * the page frame (fixed-size pages). [out] must be [TileGeometry.TILE_PX] square.
     */
    override suspend fun render(key: TileKey, page: PageInfo, content: PageContent, out: Bitmap) {
        val job: Job? = currentCoroutineContext()[Job]
        out.eraseColor(if (BackgroundRendererSet.isTransparent(page)) Color.TRANSPARENT else Color.WHITE)
        val rect = TileGeometry.rectOf(key)
        val canvas = Canvas(out)
        val s = Viewport.scaleOf(key.level)
        canvas.scale(s, s)
        canvas.translate(-rect.l, -rect.t)
        drawer.draw(canvas, page, content, rect) { job?.ensureActive() }
    }
}
