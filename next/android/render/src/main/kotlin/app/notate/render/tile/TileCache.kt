package app.notate.render.tile

import android.graphics.Bitmap
import app.notate.core.geometry.Rect
import app.notate.core.model.PageId

/**
 * Byte-bounded LRU of rendered tiles. Main thread only. [invalidate] marks tiles stale but keeps their bitmap, so the
 * compositor can keep showing the old image until the new one arrives (no white flash).
 */
class TileCache(val maxBytes: Long, val pool: BitmapPool = BitmapPool()) {

    /** A cached tile. [rev] is the `PageContent.rev` it was rendered from. */
    class Entry(val bitmap: Bitmap, val rev: Long, val stale: Boolean)

    private val entries = HashMap<TileKey, Entry>()
    /** Least recently used first. */
    private val lru = LinkedHashSet<TileKey>()

    var sizeBytes: Long = 0
        private set
    val size: Int get() = entries.size

    /** Entry for [key], marking it most recently used. */
    fun get(key: TileKey): Entry? {
        val e = entries[key] ?: return null
        lru.remove(key); lru.add(key)
        return e
    }

    /** Entry for [key] without touching the LRU order. */
    fun peek(key: TileKey): Entry? = entries[key]

    /** True when [key] is cached and not stale. */
    fun isFresh(key: TileKey): Boolean = entries[key]?.stale == false

    /** Stores a freshly rendered tile (replacing and releasing any previous bitmap), then evicts LRU tiles above [maxBytes]. */
    fun put(key: TileKey, bitmap: Bitmap, rev: Long) {
        entries.remove(key)?.let { drop(it) }
        lru.remove(key)
        entries[key] = Entry(bitmap, rev, stale = false)
        lru.add(key)
        sizeBytes += bytes(bitmap)
        val it = lru.iterator()
        while (sizeBytes > maxBytes && it.hasNext()) {
            val k = it.next()
            if (k == key) continue
            it.remove()
            entries.remove(k)?.let { e -> drop(e) }
        }
    }

    /**
     * Marks stale every tile of [page] whose extent intersects [pageRect] (page coordinates; null = the whole page).
     * Returns the keys marked.
     */
    fun invalidate(page: PageId, pageRect: Rect?): List<TileKey> {
        val hit = ArrayList<TileKey>()
        for ((k, e) in entries) {
            if (k.page != page || e.stale) continue
            if (pageRect == null || TileGeometry.rectOf(k).intersects(pageRect)) hit.add(k)
        }
        for (k in hit) entries.getValue(k).let { entries[k] = Entry(it.bitmap, it.rev, stale = true) }
        return hit
    }

    /** Drops every tile of [page] (page left the neighbourhood). */
    fun evictPage(page: PageId) {
        val keys = entries.keys.filter { it.page == page }
        for (k in keys) { lru.remove(k); entries.remove(k)?.let { drop(it) } }
    }

    fun clear() {
        entries.values.forEach { drop(it) }
        entries.clear()
        lru.clear()
    }

    private fun drop(e: Entry) {
        sizeBytes -= bytes(e.bitmap)
        pool.release(e.bitmap)
    }

    private fun bytes(b: Bitmap): Long = b.width.toLong() * b.height * 4

    companion object {
        /** Default budget: `min(64 MB, memoryClass / 4)`. */
        fun defaultMaxBytes(memoryClassMb: Int): Long = minOf(64L, memoryClassMb / 4L) * 1024 * 1024
    }
}
