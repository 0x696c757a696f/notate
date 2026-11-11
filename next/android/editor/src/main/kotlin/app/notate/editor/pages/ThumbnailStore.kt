package app.notate.editor.pages

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.log.Logger
import app.notate.render.thumb.ThumbnailRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Page thumbnails for the grid: memory LRU plus `thumbs/p<id>_<inkRev>.webp` on disk. The disk copy of a page is used only
 * until the page changes in this session (the in-memory page record does not track `inkRev`); pages edited in this session
 * are rendered again and kept in memory only. Main thread only; rendering runs on the render thread.
 */
class ThumbnailStore(
    private val editor: DocumentEditor,
    private val renderer: ThumbnailRenderer,
    private val dir: File?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val logger: Logger,
    val widthPx: Int = THUMB_WIDTH_PX,
    maxBytes: Int = 16 * 1024 * 1024,
) {
    private val cache = object : LruCache<PageId, Bitmap>(maxBytes) {
        override fun sizeOf(key: PageId, value: Bitmap): Int = value.byteCount
    }
    private val generation = HashMap<PageId, Int>()
    private val changed = HashSet<PageId>()
    private val inflight = HashSet<PageId>()
    private val gate = Semaphore(2)

    /** Called on the main thread when a thumbnail became available (or must be asked for again). */
    var onReady: ((PageId) -> Unit)? = null

    init {
        scope.launch {
            editor.changes.collect { c ->
                changed.add(c.page)
                generation[c.page] = (generation[c.page] ?: 0) + 1
                cache.remove(c.page)
            }
        }
    }

    /** The cached thumbnail of [page], or null. */
    fun get(page: PageId): Bitmap? = cache.get(page)

    /** Starts producing the thumbnail of [info] unless it is cached or already in progress. */
    fun request(info: PageInfo) {
        val id = info.id
        if (cache.get(id) != null || !inflight.add(id)) return
        val gen = generation[id] ?: 0
        scope.launch {
            try {
                gate.withPermit { produce(info, gen) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Thumbnail of page $id failed", e)
            } finally {
                inflight.remove(id)
            }
        }
    }

    private suspend fun produce(info: PageInfo, gen: Int) {
        val id = info.id
        val useDisk = dir != null && id !in changed
        val disk: Bitmap? = if (useDisk) withContext(io) { readDisk(info) } else null
        val bmp: Bitmap = disk ?: run {
            val wasLoaded = editor.content(id) != null
            val page = editor.pages.value.firstOrNull { it.id == id } ?: info
            val view = if (page.height == null) ThumbnailRenderer.whiteboardView(editor.contentBounds(id), page.width) else null
            val content = editor.ensureLoaded(id, view)
            val rendered = renderer.render(page, content, widthPx, view)
            if (!wasLoaded) editor.evict(id)
            rendered
        }
        if ((generation[id] ?: 0) == gen) {
            cache.put(id, bmp)
            if (disk == null && useDisk && id !in changed) withContext(io) { writeDisk(info, bmp) }
        }
        onReady?.invoke(id)
    }

    private fun file(info: PageInfo) = File(dir, "p${info.id.value}_${info.inkRev}.webp")

    private fun readDisk(info: PageInfo): Bitmap? =
        dir?.let { file(info).takeIf { f -> f.isFile }?.let { f -> BitmapFactory.decodeFile(f.path) } }

    private fun writeDisk(info: PageInfo, bmp: Bitmap) {
        try {
            val f = file(info)
            renderer.saveWebp(bmp, f)
            val prefix = "p${info.id.value}_"
            dir?.listFiles()?.forEach { o -> if (o.name.startsWith(prefix) && o != f) o.delete() }
        } catch (e: Exception) {
            logger.e(TAG, "Writing thumbnail of page ${info.id} failed", e)
        }
    }

    private companion object {
        const val TAG = "ThumbnailStore"
        const val THUMB_WIDTH_PX = 320
    }
}
