package app.notate

import app.notate.core.doc.registry.DocumentRegistry
import app.notate.core.model.DocId
import app.notate.core.model.log.Logger
import app.notate.core.store.library.LibraryStore
import app.notate.platform.fs.AppPaths
import app.notate.platform.thread.DispatcherSet
import app.notate.render.thumb.ThumbnailRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps `thumbs/cover.webp` of every document current (04 §2): 3 s after an editor closes a document its first page is
 * rendered again if the page changed, and new notebooks get a cover immediately. Callable from any thread.
 */
class CoverService(
    private val paths: AppPaths,
    private val registry: DocumentRegistry,
    private val library: () -> LibraryStore,
    private val renderer: ThumbnailRenderer,
    private val dispatchers: DispatcherSet,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private val pending = ConcurrentHashMap<DocId, Job>()

    /** An editor closed [id]: refresh its cover after a quiet period (a new close restarts the wait). */
    fun onClosed(id: DocId) {
        pending.remove(id)?.cancel()
        pending[id] = scope.launch {
            delay(DEBOUNCE_MS)
            pending.remove(id)
            run(id, force = false)
        }
    }

    /** Renders the cover of [id] now, regardless of whether the first page changed. */
    fun generateNow(id: DocId) {
        scope.launch { run(id, force = true) }
    }

    private suspend fun run(id: DocId, force: Boolean) {
        try {
            generate(id, force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Cover for ${id.value} failed", e)
        }
    }

    private suspend fun generate(id: DocId, force: Boolean) {
        val store = library()
        val entry = store.get(id) ?: return
        val handle = registry.acquire(id)
        try {
            val pages = withContext(dispatchers.main) { handle.editor.pages.value }
            val first = pages.firstOrNull() ?: return
            val signature = "${first.inkRev}:${first.background.hashCode()}:${first.width}x${first.height}"
            val key = "cover.${id.value}"
            val file = paths.coverFile(id)
            if (!force && file.isFile && store.getKv(key) == signature) {
                if (entry.pageCount != pages.size) store.updateCover(id, entry.coverRev, pages.size)
                return
            }
            val view = if (first.height == null) ThumbnailRenderer.whiteboardView(handle.editor.contentBounds(first.id), first.width) else null
            val content = handle.editor.ensureLoaded(first.id, view)
            val bitmap = renderer.render(first, content, COVER_WIDTH_PX, view)
            try {
                withContext(dispatchers.io) { renderer.saveWebp(bitmap, file) }
            } finally {
                bitmap.recycle()
            }
            store.putKv(key, signature)
            store.updateCover(id, entry.coverRev + 1, pages.size)
        } finally {
            registry.release(handle)
        }
    }

    private companion object {
        const val TAG = "CoverService"
        const val DEBOUNCE_MS = 3_000L
        const val COVER_WIDTH_PX = 600
    }
}
