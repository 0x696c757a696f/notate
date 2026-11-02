package app.notate.render.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.notate.core.model.AssetId
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Finds the file of an image asset (the document's store). Any thread. */
fun interface AssetFiles { fun file(id: AssetId): File? }

/** Decoding seam of [ImageCache]; the default uses [BitmapFactory]. */
interface ImageDecoder {
    /** Pixel size (w, h) of [file], or null when unreadable. */
    fun bounds(file: File): IntArray?
    fun decode(file: File, sampleSize: Int): Bitmap?
}

object BitmapFactoryDecoder : ImageDecoder {
    override fun bounds(file: File): IntArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) intArrayOf(o.outWidth, o.outHeight) else null
    }

    override fun decode(file: File, sampleSize: Int): Bitmap? =
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
}

/**
 * Decoded images by (asset, sample size), 32 MB LRU. [get] never blocks: it returns the best bitmap it has (a coarser or finer
 * one than wanted, or null) and decodes the wanted size on [io]; [onLoaded] then tells the caller to redraw. Bitmaps are never
 * recycled, so a renderer may keep drawing one that was evicted meanwhile. Thread-safe.
 */
class ImageCache(
    private val files: AssetFiles,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val decoder: ImageDecoder = BitmapFactoryDecoder,
    private val io: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "image-decode").apply { isDaemon = true } },
    private val onLoaded: (AssetId) -> Unit = {},
) {
    private data class Key(val asset: AssetId, val sample: Int)

    private val lock = Any()
    private val bitmaps = LinkedHashMap<Key, Bitmap>(16, 0.75f, true)
    private val sizes = HashMap<AssetId, IntArray?>()
    private val pending = HashSet<Key>()
    private var bytes = 0L

    /** Pixel size of [asset] once known (after the first load), else null. */
    fun pixelSize(asset: AssetId): IntArray? = synchronized(lock) { sizes[asset] }

    /** Bitmap for drawing [asset] about [targetPx] pixels wide on screen; see the class comment. */
    fun get(asset: AssetId, targetPx: Float): Bitmap? = synchronized(lock) {
        if (!sizes.containsKey(asset)) {
            request(asset, null)
            return@synchronized null
        }
        val size = sizes[asset] ?: return@synchronized null            // unreadable file: placeholder forever
        val key = Key(asset, sampleFor(size[0], targetPx))
        bitmaps[key] ?: run {
            request(asset, key.sample)
            bitmaps.entries.filter { it.key.asset == asset }.minByOrNull { it.key.sample }?.value
        }
    }

    private fun request(asset: AssetId, sample: Int?) {
        val key = Key(asset, sample ?: 0)
        if (!pending.add(key)) return
        io.execute {
            try {
                val file = files.file(asset)
                var s = sample
                if (s == null) {
                    val b = file?.let { decoder.bounds(it) }
                    synchronized(lock) { sizes[asset] = b }
                    s = if (b == null) null else sampleFor(b[0], DEFAULT_FIRST_PX)
                }
                val bmp = if (s != null && file != null) decoder.decode(file, s) else null
                if (bmp != null) store(Key(asset, s!!), bmp)
            } catch (e: Exception) {
                synchronized(lock) { if (!sizes.containsKey(asset)) sizes[asset] = null }   // unreadable: the placeholder stays
            } finally {
                synchronized(lock) { pending.remove(key) }
            }
            onLoaded(asset)
        }
    }

    private fun store(key: Key, bmp: Bitmap) = synchronized(lock) {
        bitmaps.put(key, bmp)?.let { bytes -= it.allocationByteCount }
        bytes += bmp.allocationByteCount
        val it = bitmaps.entries.iterator()
        while (bytes > maxBytes && bitmaps.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            bytes -= e.value.allocationByteCount
            it.remove()
        }
    }

    /** Drops everything (document closed). */
    fun clear() = synchronized(lock) { bitmaps.clear(); sizes.clear(); bytes = 0 }

    fun cachedBytes(): Long = synchronized(lock) { bytes }

    companion object {
        const val DEFAULT_MAX_BYTES = 32L shl 20
        private const val DEFAULT_FIRST_PX = 600f

        /** Largest power-of-two sample size that still leaves the decoded width at least [targetPx]; 1 when the image is small. */
        fun sampleFor(srcWidth: Int, targetPx: Float): Int {
            if (targetPx <= 0f || srcWidth <= 0) return 1
            var s = 1
            while (srcWidth / (s * 2f) >= targetPx) s *= 2
            return s
        }
    }
}
