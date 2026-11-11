package app.notate.render.tile

import android.graphics.Bitmap
import app.notate.core.model.AssetId
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.render.paper.BackgroundRenderer
import java.io.File

/**
 * Renders the *background* of a tile (a PDF page, for now) on a thread of its own. Tiles of the same [TileKey] always
 * line up with the ink tiles, so the compositor only has to draw them one below the other.
 */
interface BackgroundTileSource {
    /** True if [page]'s background comes from this source (otherwise the paper renderer draws it). Any thread. */
    fun handles(page: PageInfo): Boolean

    /** Fills [out] ([TileGeometry.TILE_PX] square, any content) with white and the page content of tile [key]. Suspends until done. */
    suspend fun renderTile(key: TileKey, page: PageInfo, out: Bitmap)
}

/**
 * Byte-bounded LRU of background tiles, separate from the ink [TileCache] so that writing only redraws ink (the PDF is not
 * rasterized again). Safe from any thread (the render thread fills it, the main thread clears it). Evicted bitmaps are
 * dropped, not recycled, because the render thread may still be drawing one.
 */
class BackgroundTileCache(val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val map = LinkedHashMap<TileKey, Bitmap>(16, 0.75f, true)
    private var bytes = 0L

    val sizeBytes: Long @Synchronized get() = bytes
    val size: Int @Synchronized get() = map.size

    @Synchronized fun get(key: TileKey): Bitmap? = map[key]

    @Synchronized fun put(key: TileKey, bitmap: Bitmap) {
        map.put(key, bitmap)?.let { bytes -= size(it) }
        bytes += size(bitmap)
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            bytes -= size(e.value)
            it.remove()
        }
    }

    /** Drops every tile of [page]. */
    @Synchronized fun evictPage(page: PageId) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.page == page) { bytes -= size(e.value); it.remove() }
        }
    }

    @Synchronized fun clear() {
        map.clear()
        bytes = 0
    }

    private fun size(b: Bitmap) = b.width.toLong() * b.height * 4

    companion object {
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
    }
}

/** A tile background source with the cache it fills. */
class TileBackgrounds(val source: BackgroundTileSource, val cache: BackgroundTileCache)

/** What the editor gets for one PDF document: tiles for the tile pipeline and a synchronous renderer for thumbnails. */
class PdfBackgroundHandle(val tiles: TileBackgrounds, val renderer: BackgroundRenderer)

/** Creates the PDF background of a document; [assetFile] resolves the document's PDF asset. Implemented by `:next:pdf`. */
fun interface PdfBackgroundFactory {
    fun create(assetFile: (AssetId) -> File?): PdfBackgroundHandle
}
