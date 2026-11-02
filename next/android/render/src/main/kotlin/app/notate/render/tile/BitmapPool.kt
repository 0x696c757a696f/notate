package app.notate.render.tile

import android.graphics.Bitmap

/** Recycles 512×512 ARGB_8888 tile bitmaps. Main thread only. */
class BitmapPool(private val maxPooled: Int = 8) {
    private val free = ArrayDeque<Bitmap>()

    val pooled: Int get() = free.size

    /** A cleared-or-dirty tile bitmap (callers overwrite every pixel). */
    fun obtain(): Bitmap = free.removeLastOrNull() ?: Bitmap.createBitmap(TileGeometry.TILE_PX, TileGeometry.TILE_PX, Bitmap.Config.ARGB_8888)

    /** Gives [bitmap] back; non-tile bitmaps and overflow are recycled. */
    fun release(bitmap: Bitmap) {
        val tile = bitmap.width == TileGeometry.TILE_PX && bitmap.height == TileGeometry.TILE_PX && !bitmap.isRecycled
        if (tile && free.size < maxPooled) free.addLast(bitmap) else if (!bitmap.isRecycled) bitmap.recycle()
    }

    fun clear() {
        free.forEach { it.recycle() }
        free.clear()
    }
}
