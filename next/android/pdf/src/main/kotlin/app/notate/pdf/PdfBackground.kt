package app.notate.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.Background
import app.notate.core.model.PageInfo
import app.notate.render.paper.BackgroundRenderer
import app.notate.render.tile.BackgroundTileCache
import app.notate.render.tile.BackgroundTileSource
import app.notate.render.tile.PdfBackgroundFactory
import app.notate.render.tile.PdfBackgroundHandle
import app.notate.render.tile.TileBackgrounds
import app.notate.render.tile.TileGeometry
import app.notate.render.tile.TileKey
import app.notate.core.doc.layout.Viewport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Draws the pages of one PDF document: tiles for the tile pipeline ([BackgroundTileSource]) and a synchronous region
 * renderer for thumbnails ([BackgroundRenderer]). Every access to `PdfRenderer` is queued on [pdfDispatcher] (single thread).
 * Any thread.
 */
class PdfBackground(
    private val assetFile: (AssetId) -> File?,
    private val documents: PdfDocumentCache,
    private val pdfDispatcher: CoroutineDispatcher,
) : BackgroundTileSource, BackgroundRenderer {
    private val white = Paint().apply { color = Color.WHITE }
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun handles(page: PageInfo) = page.background is Background.Pdf

    override suspend fun renderTile(key: TileKey, page: PageInfo, out: Bitmap) {
        val scale = Viewport.scaleOf(key.level)
        val rect = TileGeometry.rectOf(key)
        withContext(pdfDispatcher) { rasterize(page, rect.l, rect.t, scale, out) }
    }

    /** Thumbnails and exports: [canvas] carries the page→target matrix; draws the part of the page inside [clipDoc]. Blocks on the PDF thread. */
    override fun draw(canvas: Canvas, page: PageInfo, clipDoc: Rect) {
        val h = page.height ?: return
        val area = Rect(maxOf(clipDoc.l, 0f), maxOf(clipDoc.t, 0f), minOf(clipDoc.r, page.width), minOf(clipDoc.b, h))
        if (area.isEmpty) return
        val m = Matrix().also { canvas.getMatrix(it) }
        val v = FloatArray(9).also { m.getValues(it) }
        val scale = hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y]).takeIf { it > 0f } ?: 1f
        val w = ceil(area.width * scale).toInt().coerceIn(1, MAX_SIDE)
        val ht = ceil(area.height * scale).toInt().coerceIn(1, MAX_SIDE)
        val bitmap = Bitmap.createBitmap(w, ht, Bitmap.Config.ARGB_8888)
        try {
            runBlocking(pdfDispatcher) { rasterize(page, area.l, area.t, w / area.width, bitmap) }
            canvas.drawBitmap(bitmap, null, RectF(area.l, area.t, area.r, area.b), blit)
        } catch (e: Exception) {
            canvas.drawRect(area.l, area.t, area.r, area.b, white) // unreadable page: show it blank rather than failing the thumbnail
        } finally {
            bitmap.recycle()
        }
    }

    /** Renders the part of [page] whose top-left is ([left], [top]) pt at [scale] px per pt into [out]. PDF thread only. */
    private fun rasterize(page: PageInfo, left: Float, top: Float, scale: Float, out: Bitmap) {
        out.eraseColor(Color.WHITE)
        val bg = page.background as? Background.Pdf ?: return
        val file = assetFile(bg.asset) ?: return
        val renderer: PdfRenderer = documents.get(file)
        if (bg.pageIndex !in 0 until renderer.pageCount) return
        renderer.openPage(bg.pageIndex).use { p ->
            val m = Matrix()
            m.setScale(scale, scale)
            m.postTranslate(-left * scale, -top * scale)
            p.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    private companion object { const val MAX_SIDE = 4096 }
}

/**
 * App-wide factory of [PdfBackground]s (one [PdfDocumentCache] shared, LRU 2) and owner of every background tile cache,
 * so that low memory can empty them all.
 */
class PdfBackgrounds(private val pdfDispatcher: CoroutineDispatcher, private val cacheBytes: Long = BackgroundTileCache.DEFAULT_MAX_BYTES) : PdfBackgroundFactory {
    private val documents = PdfDocumentCache(capacity = 2)
    private val caches: MutableSet<BackgroundTileCache> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<BackgroundTileCache, Boolean>()))

    override fun create(assetFile: (AssetId) -> File?): PdfBackgroundHandle {
        val source = PdfBackground(assetFile, documents, pdfDispatcher)
        val cache = BackgroundTileCache(cacheBytes)
        caches += cache
        return PdfBackgroundHandle(TileBackgrounds(source, cache), source)
    }

    /** `onTrimMemory(RUNNING_LOW)`: empties every background tile cache (they are re-rendered when scrolled to). */
    fun trimMemory() {
        synchronized(caches) { caches.forEach { it.clear() } }
    }
}
