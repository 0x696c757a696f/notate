package app.notate.render.tile

import app.notate.core.doc.layout.Viewport
import app.notate.core.geometry.Rect
import app.notate.core.model.PageId
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One 512 px tile of a page. [level] is the √2 zoom step (`Viewport.levelFor(scale)`); the tile's scale comes from the
 * level, not from the exact zoom. [col]/[row] index tiles in **page** coordinates (pt), so they survive layout changes;
 * whiteboard pages may have negative indices.
 */
data class TileKey(val page: PageId, val level: Int, val col: Int, val row: Int)

/** Pure tile arithmetic. */
object TileGeometry {
    const val TILE_PX = 512

    /** Side of a tile in page pt at [level]. */
    fun sizePt(level: Int): Float = TILE_PX / Viewport.scaleOf(level)

    /** Page-coordinate extent of [key]. */
    fun rectOf(key: TileKey): Rect {
        val s = sizePt(key.level)
        return Rect(key.col * s, key.row * s, (key.col + 1) * s, (key.row + 1) * s)
    }

    /**
     * Tiles of [page] at [level] that intersect [region] (page coordinates), limited to [bounds] (page extent; null for
     * whiteboard pages). Row-major order.
     */
    fun cover(page: PageId, level: Int, region: Rect, bounds: Rect?): List<TileKey> {
        val r = if (bounds == null) region else Rect(
            maxOf(region.l, bounds.l), maxOf(region.t, bounds.t), minOf(region.r, bounds.r), minOf(region.b, bounds.b),
        )
        if (r.isEmpty) return emptyList()
        val s = sizePt(level)
        val c0 = floor(r.l / s).toInt(); val c1 = ceil(r.r / s).toInt() - 1
        val r0 = floor(r.t / s).toInt(); val r1 = ceil(r.b / s).toInt() - 1
        val out = ArrayList<TileKey>((c1 - c0 + 1).coerceAtLeast(0) * (r1 - r0 + 1).coerceAtLeast(0))
        for (row in r0..r1) for (col in c0..c1) out.add(TileKey(page, level, col, row))
        return out
    }
}
