package app.notate.render.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.geometry.Rect
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.render.paper.BackgroundRendererSet
import app.notate.render.tile.TileCache
import app.notate.render.tile.TileGeometry
import app.notate.render.tile.TileKey

/**
 * Main-thread drawing helper for `PageCanvasView.onDraw`: page-outside background `#E0E0E0`, a 1 dp border per page, and
 * every visible tile. A missing tile at the current level is filled from the neighbouring levels (scaled); with none
 * available the page stays white. Web pages are not filled white (their tiles are transparent).
 */
class TileCompositor(private val cache: TileCache, private val density: Float, private val pageInfo: (PageId) -> PageInfo?) {
    private val white = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val border = Paint().apply { color = BORDER_COLOR; style = Paint.Style.STROKE; strokeWidth = density }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val src = android.graphics.Rect()

    fun draw(canvas: Canvas, viewport: Viewport, layout: DocumentLayout) {
        canvas.drawColor(OUTSIDE_COLOR)
        val toScreen = viewport.docToScreen()
        val level = Viewport.levelFor(viewport.scale)
        val visibleDoc = viewport.visibleDocRect()
        for (page in layout.visiblePages(visibleDoc)) {
            val info = pageInfo(page) ?: continue
            val frame = layout.frameOf(page)
            val s = toScreen.mapRect(frame)
            canvas.save()
            canvas.clipRect(s.l, s.t, s.r, s.b)
            if (!BackgroundRendererSet.isTransparent(info)) canvas.drawRect(s.l, s.t, s.r, s.b, white)
            val local = visibleDoc.offset(-frame.l, -frame.t)
            val bounds = info.height?.let { Rect(0f, 0f, info.width, it) }
            for (key in TileGeometry.cover(page, level, local, bounds)) drawTile(canvas, viewport, frame, key)
            canvas.restore()
            canvas.drawRect(s.l, s.t, s.r, s.b, border)
        }
    }

    private fun drawTile(canvas: Canvas, vp: Viewport, frame: Rect, key: TileKey) {
        val target = TileGeometry.rectOf(key)
        val own = cache.get(key)
        if (own != null) {
            blit(canvas, vp, frame, own.bitmap, target, target)
            return
        }
        for (dl in FALLBACK_LEVELS) {
            val lv = key.level + dl
            for (k in TileGeometry.cover(key.page, lv, target, null)) {
                val e = cache.peek(k) ?: continue
                val part = intersect(TileGeometry.rectOf(k), target) ?: continue
                blit(canvas, vp, frame, e.bitmap, TileGeometry.rectOf(k), part)
            }
        }
    }

    /** Draws the [part] (page coordinates) of the tile at [tileRect] into its place on screen. */
    private fun blit(canvas: Canvas, vp: Viewport, frame: Rect, bitmap: android.graphics.Bitmap, tileRect: Rect, part: Rect) {
        val k = bitmap.width / tileRect.width           // bitmap px per page pt
        src.set(
            ((part.l - tileRect.l) * k).toInt(), ((part.t - tileRect.t) * k).toInt(),
            Math.ceil(((part.r - tileRect.l) * k).toDouble()).toInt(), Math.ceil(((part.b - tileRect.t) * k).toDouble()).toInt(),
        )
        val sc = vp.scale
        dst.set(
            (frame.l + part.l - vp.scrollX) * sc, (frame.t + part.t - vp.scrollY) * sc,
            (frame.l + part.r - vp.scrollX) * sc, (frame.t + part.b - vp.scrollY) * sc,
        )
        canvas.drawBitmap(bitmap, src, dst, bitmapPaint)
    }

    private fun intersect(a: Rect, b: Rect): Rect? {
        val r = Rect(maxOf(a.l, b.l), maxOf(a.t, b.t), minOf(a.r, b.r), minOf(a.b, b.b))
        return if (r.isEmpty) null else r
    }

    companion object {
        const val OUTSIDE_COLOR = 0xFFE0E0E0.toInt()
        const val BORDER_COLOR = 0xFF9E9E9E.toInt()
        private val FALLBACK_LEVELS = intArrayOf(-1, 1, -2)
    }
}
