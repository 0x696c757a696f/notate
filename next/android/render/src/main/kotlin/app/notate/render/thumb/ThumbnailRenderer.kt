package app.notate.render.thumb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import app.notate.core.doc.content.PageContent
import app.notate.core.geometry.Rect
import app.notate.core.model.PageInfo
import app.notate.render.PageDrawer
import app.notate.render.ink.InkRenderer
import app.notate.render.paper.BackgroundRendererSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Renders whole-page thumbnails on [dispatcher] (the render thread), bypassing the tile cache. */
class ThumbnailRenderer(backgrounds: BackgroundRendererSet, ink: InkRenderer, private val dispatcher: CoroutineDispatcher) {
    private val drawer = PageDrawer(backgrounds, ink)

    /**
     * Bitmap [widthPx] wide showing the whole page (whiteboard pages: a 3:4 window at the origin). Transparent Web
     * pages are rendered on white.
     */
    suspend fun render(page: PageInfo, content: PageContent, widthPx: Int, view: Rect? = null): Bitmap = withContext(dispatcher) {
        val job: Job? = currentCoroutineContext()[Job]
        // Whiteboard pages show [view] (the framed content, see [whiteboardView]) when given.
        val area = if (page.height == null && view != null && !view.isEmpty) view else Rect(0f, 0f, page.width, page.height ?: (page.width * 4f / 3f))
        val heightPt = area.height
        val scale = widthPx / area.width
        val heightPx = maxOf(1, Math.round(heightPt * scale))
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.translate(-area.l, -area.t)
            drawer.draw(canvas, page, content, area) { job?.ensureActive() }
            bitmap
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
    }

    companion object {
        /** Thumbnail window for an infinite canvas: [bounds] plus 5 % padding grown to the 3:4 portrait shape; null/empty → a 3:4 window at the origin. */
        fun whiteboardView(bounds: Rect?, pageWidth: Float): Rect {
            if (bounds == null || bounds.isEmpty) return Rect(0f, 0f, pageWidth, pageWidth * 4f / 3f)
            val pad = maxOf(bounds.width, bounds.height) * 0.05f + 1f
            var w = bounds.width + 2 * pad
            var h = bounds.height + 2 * pad
            if (w * 4f > h * 3f) h = w * 4f / 3f else w = h * 3f / 4f
            val c = bounds.center
            return Rect(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)
        }
    }

    /** Writes [bitmap] as lossy WebP (quality 80). */
    fun saveWebp(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            check(bitmap.compress(format, 80, out)) { "WebP encoding failed for ${file.name}" }
        }
    }
}
