package app.notate.export.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.model.ImageItem
import app.notate.core.model.PageInfo
import app.notate.render.image.AssetFiles
import app.notate.render.image.ImageCache
import app.notate.render.ink.GeometryCache
import app.notate.render.ink.InkRenderer
import app.notate.render.ink.PaintPool
import app.notate.render.paper.BackgroundRendererSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders one page to a PNG at 300 dpi (longest side at most 4096 px) with the same drawing code as the screen: template or PDF
 * page, then every object by (layer, order). An infinite canvas is cropped to its content plus a margin.
 * [backgrounds] supplies the renderers for a document (PDF documents register their page renderer there).
 */
class PngExporter(
    private val backgrounds: (DocumentHandle) -> BackgroundRendererSet,
    private val io: CoroutineDispatcher,
) {
    /** Writes [page] of [handle] to [out]. */
    suspend fun export(handle: DocumentHandle, page: PageInfo, out: File) {
        handle.save.flush()
        val items = handle.readItems(page.id)
        withContext(io) {
            val area = ExportPlan.area(page, items)
            val scale = min(DPI / 72f, MAX_SIDE_PX / max(area.width, area.height))
            val w = max(1, (area.width * scale).roundToInt())
            val h = max(1, (area.height * scale).roundToInt())
            // A direct executor makes the image cache decode on this thread, so every picture is ready when it is drawn.
            val images = ImageCache(AssetFiles { handle.assetFile(it) }, maxBytes = IMAGE_CACHE_BYTES, io = java.util.concurrent.Executor { it.run() })
            for (item in items) if (item is ImageItem) repeat(3) { images.get(item.asset, item.frame.width * scale) }
            val ink = InkRenderer(GeometryCache(), images)
            val paints = PaintPool()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val tmp = File(out.parentFile, out.name + ".part")
            try {
                bitmap.eraseColor(Color.WHITE)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                canvas.translate(-area.l, -area.t)
                canvas.clipRect(area.l, area.t, area.r, area.b)
                backgrounds(handle).draw(canvas, page, area)
                val job = currentCoroutineContext()
                for ((n, item) in items.sortedWith(ExportPlan.ITEM_ORDER).withIndex()) {
                    if (n % 64 == 0) job.ensureActive()
                    ink.draw(canvas, item, paints)
                }
                out.parentFile?.mkdirs()
                tmp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" } }
                if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)
            } finally {
                bitmap.recycle()
                tmp.delete()
            }
        }
    }

    companion object {
        const val DPI = 300f
        const val MAX_SIDE_PX = 4096f
        private const val IMAGE_CACHE_BYTES = 96L shl 20
    }
}
