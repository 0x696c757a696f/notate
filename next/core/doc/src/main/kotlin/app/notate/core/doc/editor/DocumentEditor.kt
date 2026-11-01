package app.notate.core.doc.editor

import app.notate.core.doc.content.PageContent
import app.notate.core.doc.edit.IdAllocator
import app.notate.core.doc.history.History
import app.notate.core.doc.save.SaveQueue
import app.notate.core.geometry.Rect
import app.notate.core.model.Clock
import app.notate.core.model.DocumentMeta
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.edit.Edit
import app.notate.core.store.DocStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Tells renderers what changed; [dirty] null means the whole page (background, size, insert, delete). */
data class ContentChange(val page: PageId, val dirty: Rect?, val rev: Long)

/**
 * The single owner of a document's in-memory state. **Main thread only** (every public member except [changes]/[meta]/[pages]
 * collection); content snapshots it hands out are immutable and may be read anywhere.
 */
class DocumentEditor(
    initialMeta: DocumentMeta,
    initialPages: List<PageInfo>,
    private val store: DocStore,
    private val save: SaveQueue,
    private val ids: IdAllocator,
    val clock: Clock,
    private val main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    historyLimit: Int = 200,
) {
    private val _meta = MutableStateFlow(initialMeta)
    private val _pages = MutableStateFlow(initialPages.sortedBy { it.ord })
    private val contents = HashMap<PageId, PageContent>()
    private val lastRevTouching = HashMap<PageId, Long>()
    private val loading = HashMap<PageId, CompletableDeferred<Unit>>()
    /** Ids edited while a page load is in flight; the stale loaded copy of these must not win. */
    private val touchedDuringLoad = HashMap<PageId, HashSet<Long>>()
    private val _changes = MutableSharedFlow<ContentChange>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var rev = 0L

    val meta: StateFlow<DocumentMeta> get() = _meta
    /** Pages ordered by `ord`. */
    val pages: StateFlow<List<PageInfo>> get() = _pages
    /** Emits after each applied edit. Slow collectors may miss events; compare [PageContent.rev] to self-heal. */
    val changes: SharedFlow<ContentChange> get() = _changes
    val history = History(historyLimit)

    /** Loaded snapshot of [page], or null when not loaded. */
    fun content(page: PageId): PageContent? = contents[page]

    fun newItemId(): ItemId = ids.nextItem()
    fun newPageId(): PageId = ids.nextPage()
    fun nextOrder(page: PageId): Long = ids.nextOrder(page)

    /**
     * Loads [page] (whiteboard pages: at least [region]) and returns the snapshot. Concurrent calls for one page share a load.
     * Anything edited locally meanwhile wins over the stored copy.
     */
    suspend fun ensureLoaded(page: PageId, region: Rect? = null): PageContent = withContext(main) { loadLoop(page, region) }

    private suspend fun loadLoop(page: PageId, region: Rect?): PageContent {
        val info = _pages.value.firstOrNull { it.id == page } ?: throw IllegalArgumentException("Unknown page $page")
        val whiteboard = info.height == null
        while (true) {
            val cur = contents[page]
            if (cur != null && covers(cur, whiteboard, region)) return cur
            val inflight = loading[page]
            if (inflight != null) {
                inflight.await()
                continue
            }
            val done = CompletableDeferred<Unit>()
            loading[page] = done
            touchedDuringLoad[page] = HashSet()
            try {
                // Fixed pages load whole. Whiteboards load the union with what is already loaded so loadedRegion stays exact.
                val target: Rect? = if (!whiteboard || region == null) null else cur?.loadedRegion?.union(region) ?: region
                val items = withContext(io) { store.readItems(page, target) }
                ids.observe(page, items)
                val skip: Set<Long> = touchedDuringLoad[page].orEmpty()
                val now = contents[page]
                contents[page] = if (now == null) {
                    PageContent.of(page, items.filter { it.id.value !in skip }, rev, target)
                } else {
                    now.merge(target, items, skip)
                }
            } finally {
                loading.remove(page)
                touchedDuringLoad.remove(page)
                done.complete(Unit)
            }
        }
    }

    private fun covers(c: PageContent, whiteboard: Boolean, region: Rect?): Boolean {
        val lr = c.loadedRegion ?: return true
        if (!whiteboard) return true
        return region != null && lr.contains(region)
    }

    /** Drops the in-memory content of [page] if everything touching it is already on disk; otherwise does nothing. */
    fun evict(page: PageId) {
        if (loading.containsKey(page)) return
        if (save.persistedRev.value >= (lastRevTouching[page] ?: 0L)) contents.remove(page)
    }

    /**
     * The only mutation entry point: changes memory, records history (when [undoable]), queues the write and notifies renderers.
     * @throws IllegalStateException when an [Edit.Items] targets a page that is not loaded.
     */
    fun apply(edit: Edit, undoable: Boolean = true) {
        validate(edit, HashSet(contents.keys))
        rev++
        applyInMemory(edit)
        if (undoable) history.record(edit)
        save.enqueue(edit, rev)
    }

    /** Undoes the latest recorded edit; false when there is nothing to undo. */
    fun undo(): Boolean = history.undo()?.let { apply(it, undoable = false); true } ?: false

    fun redo(): Boolean = history.redo()?.let { apply(it, undoable = false); true } ?: false

    private fun validate(edit: Edit, loaded: MutableSet<PageId>) {
        when (edit) {
            is Edit.Items -> check(edit.page in loaded) { "Page ${edit.page} is not loaded" }
            is Edit.InsertPage -> loaded.add(edit.page.id)
            is Edit.DeletePage -> loaded.remove(edit.page.id)
            is Edit.Batch -> edit.edits.forEach { validate(it, loaded) }
            is Edit.MovePage, is Edit.SetPage, is Edit.SetMeta -> Unit
        }
    }

    private fun applyInMemory(edit: Edit) {
        when (edit) {
            is Edit.Items -> {
                val c = contents.getValue(edit.page)
                contents[edit.page] = c.with(edit.removed, edit.added, rev)
                ids.observe(edit.page, edit.added)
                touch(edit.page)
                touchedDuringLoad[edit.page]?.let { s -> (edit.removed + edit.added).forEach { s.add(it.id.value) } }
                val dirty = (edit.removed + edit.added).fold(Rect.EMPTY) { acc, i -> acc.union(i.bounds) }
                _changes.tryEmit(ContentChange(edit.page, dirty.takeIf { !it.isEmpty }, rev))
            }
            is Edit.InsertPage -> {
                ids.observePage(edit.page.id)
                ids.observe(edit.page.id, edit.items)
                _pages.value = (_pages.value.filter { it.id != edit.page.id } + edit.page).sortedBy { it.ord }
                contents[edit.page.id] = PageContent.of(edit.page.id, edit.items, rev)
                touch(edit.page.id)
                _changes.tryEmit(ContentChange(edit.page.id, null, rev))
            }
            is Edit.DeletePage -> {
                _pages.value = _pages.value.filter { it.id != edit.page.id }
                contents.remove(edit.page.id)
                touch(edit.page.id)
                _changes.tryEmit(ContentChange(edit.page.id, null, rev))
            }
            is Edit.MovePage -> {
                _pages.value = _pages.value.map { if (it.id == edit.page) it.copy(ord = edit.toOrd) else it }.sortedBy { it.ord }
                _changes.tryEmit(ContentChange(edit.page, null, rev))
            }
            is Edit.SetPage -> {
                _pages.value = _pages.value.map { if (it.id == edit.after.id) edit.after else it }.sortedBy { it.ord }
                _changes.tryEmit(ContentChange(edit.after.id, null, rev))
            }
            is Edit.SetMeta -> _meta.value = edit.after
            is Edit.Batch -> edit.edits.forEach(::applyInMemory)
        }
    }

    private fun touch(page: PageId) { lastRevTouching[page] = rev }
}
