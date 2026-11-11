package app.notate.core.doc.registry

import app.notate.core.doc.edit.IdAllocator
import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.doc.save.SaveQueue
import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.DocumentMeta
import app.notate.core.model.PageInfo
import app.notate.core.store.DocStore
import app.notate.core.store.MaxIds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What an opened document gives the registry. */
class Opened(val store: DocStore, val meta: DocumentMeta, val pages: List<PageInfo>, val maxIds: MaxIds)

/** Opens a document's storage; implemented in the app on top of `SqliteDocStoreFactory`. */
interface DocumentOpener { suspend fun open(id: DocId): Opened }

class DocumentHandle internal constructor(
    val id: DocId,
    val editor: DocumentEditor,
    val save: SaveQueue,
    internal val store: DocStore,
) {
    /** The document's directory on disk (web documents keep their files in `assets/web`). */
    val dir: java.io.File get() = store.dir

    /** Copies [source] into the document's assets (deduplicated by content) and returns its id. Call off the main thread. */
    suspend fun putAsset(kind: app.notate.core.store.AssetKind, mime: String, source: java.io.File, ext: String): app.notate.core.model.AssetId =
        store.putAsset(kind, mime, source, ext)

    /** The file of an asset, or null when unknown. */
    fun assetFile(id: app.notate.core.model.AssetId): java.io.File? = store.assetFile(id)

    suspend fun readOutline(): List<app.notate.core.store.OutlineEntry> = store.readOutline()
}

class FlushFailure(val id: DocId, val error: Throwable)

/**
 * Reference-counted set of open documents: tabs, split panes, export and OCR share one handle per document.
 * The last [release] flushes and closes it in [scope]. A document whose final flush fails stays open (still retrying) until
 * it is acquired and released again, so edits are never dropped silently.
 */
class DocumentRegistry(
    private val opener: DocumentOpener,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
) {
    private class Entry(val handle: DocumentHandle) { var count = 0 }

    private val mutex = Mutex()
    private val open = HashMap<DocId, Entry>()

    /** Opens [id] on first use; concurrent callers share one open. */
    suspend fun acquire(id: DocId): DocumentHandle = mutex.withLock {
        val entry = open[id] ?: createEntry(id).also { open[id] = it }
        entry.count++
        entry.handle
    }

    /** Drops one reference; at zero the document is flushed and closed in the background unless acquired again meanwhile. */
    fun release(handle: DocumentHandle) {
        scope.launch {
            val entry = mutex.withLock {
                val e = open[handle.id]?.takeIf { it.handle === handle } ?: return@launch
                check(e.count > 0) { "release without acquire for ${handle.id}" }
                e.count--
                if (e.count > 0) return@launch
                e
            }
            try {
                handle.save.flush()
            } catch (_: Exception) {
                return@launch  // keep it open; the queue keeps retrying
            }
            mutex.withLock {
                if (entry.count == 0 && open[handle.id] === entry) {
                    open.remove(handle.id)
                    handle.save.close()
                    handle.store.close()
                }
            }
        }
    }

    /** Flushes every open document concurrently; one failure does not stop the others. */
    suspend fun flushAll(): List<FlushFailure> {
        val handles = mutex.withLock { open.values.map { it.handle } }
        return coroutineScope {
            handles.map { h ->
                async { runCatching { h.save.flush() }.exceptionOrNull()?.let { FlushFailure(h.id, it) } }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun createEntry(id: DocId): Entry {
        val o = opener.open(id)
        val save = SaveQueue(o.store, io, clock)
        val editor = DocumentEditor(o.meta, o.pages, o.store, save, IdAllocator(o.maxIds), clock, main, io)
        return Entry(DocumentHandle(id, editor, save, o.store))
    }
}
