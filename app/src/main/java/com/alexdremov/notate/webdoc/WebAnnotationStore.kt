package com.alexdremov.notate.webdoc

import com.alexdremov.notate.doc.Capability
import com.alexdremov.notate.doc.DocKind
import com.alexdremov.notate.doc.DocRef
import com.alexdremov.notate.doc.DocumentSession
import com.alexdremov.notate.doc.SaveState
import com.alexdremov.notate.doc.UndoController
import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs

/**
 * The one in-memory copy of a web document's notes (ink per page, reading position), shared by every
 * screen showing that document in this process: the full reader and the book pane of a split can no
 * longer hold diverging copies and overwrite each other's file.
 *
 * Mutation (adding strokes) happens on the main thread through the shared [WebInkPage]s; saving
 * takes a cheap snapshot there and serialises it on a background thread, in order, so the UI thread
 * never waits for JSON. [flushBlocking] is the lifecycle hook: it returns once the disk is current.
 *
 * It is the web document's [DocumentSession] (also known as [WebSession]); [WebDocSessionRegistry]
 * hands it out.
 */
class WebAnnotationStore internal constructor(
    val docId: String,
    private val library: WebDocLibrary,
    private val io: ExecutorService = sharedIo,
) : DocumentSession {
    companion object {
        private const val TAG = "WebAnnotationStore"

        /** Relative width change above which the layout is considered different. */
        const val LAYOUT_DRIFT_FRACTION = 0.05f

        private const val FLUSH_TIMEOUT_MS = 10_000L

        private val sharedIo: ExecutorService =
            Executors.newSingleThreadExecutor { r -> Thread(r, "web-annotations-io").apply { isDaemon = true } }

        private val registry = ConcurrentHashMap<String, Holder>()
        private val registryLock = Any()

        private class Holder(
            val store: WebAnnotationStore,
        ) {
            var count = 0
        }

        /** Returns the shared store for the document, creating it on first use. Pair with [release]. */
        fun acquire(
            library: WebDocLibrary,
            docId: String,
        ): WebAnnotationStore =
            synchronized(registryLock) {
                val key = library.path + "|" + docId
                val holder = registry.getOrPut(key) { Holder(WebAnnotationStore(docId, library)) }
                holder.count++
                holder.store
            }

        /** Drops one holder; the last one flushes to disk and lets the in-memory copy go. */
        fun release(store: WebAnnotationStore) {
            val last =
                synchronized(registryLock) {
                    val entry = registry.entries.firstOrNull { it.value.store === store } ?: return
                    entry.value.count--
                    if (entry.value.count <= 0) {
                        registry.remove(entry.key)
                        true
                    } else {
                        false
                    }
                }
            if (last) store.flushBlocking()
        }

        /** True if [current] differs from the [recorded] layout width by more than [LAYOUT_DRIFT_FRACTION]. */
        fun isLayoutDrift(
            recorded: Float?,
            current: Float,
        ): Boolean = recorded != null && recorded > 0f && current > 0f && abs(current - recorded) / recorded > LAYOUT_DRIFT_FRACTION
    }

    /** What the Ink diagnostics dialog shows about saving. */
    data class SaveStats(
        val saveCount: Int,
        val lastSaveAtMs: Long,
        val lastSaveDurationMs: Long,
        val lastError: String?,
        val unsaved: Boolean,
        val holders: Int,
    )

    private val lock = Any()

    // --- DocumentSession ---
    @Volatile var title: String = docId

    override val ref: DocRef get() = DocRef(DocKind.WEB, docId, title)
    override val capabilities: Set<Capability> = Capability.WEB

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Clean)
    override val saveState: StateFlow<SaveState> = _saveState.asStateFlow()
    private val undoChanges = MutableStateFlow(0)

    /** The page undo/redo act on; hosts set it when the visible page changes. */
    @Volatile var currentHref: String? = null

    override val undo =
        object : UndoController {
            private fun currentPage() = currentHref?.let { this@WebAnnotationStore.page(it) }

            override val canUndo get() = currentPage()?.canUndo == true
            override val canRedo get() = currentPage()?.canRedo == true
            override val changes: StateFlow<Int> = undoChanges.asStateFlow()

            override suspend fun undo(): Boolean = (currentPage()?.undo() == true).also { if (it) inkEdited() }

            override suspend fun redo(): Boolean = (currentPage()?.redo() == true).also { if (it) inkEdited() }
        }

    private fun inkEdited() {
        currentHref?.let { markInkChanged(it) }
    }

    override suspend fun flush() {
        withContext(Dispatchers.IO) { flushBlocking(FLUSH_TIMEOUT_MS) }
    }

    /** Flushes; the registry releases the memory when the last holder lets go. */
    override suspend fun close() = flush()

    private val disk = library.inkStore(docId)
    private val base = disk.load(openForSession = true)
    private val pages = HashMap<String, WebInkPage>()
    private val strokesByPage = HashMap(base.strokes)
    private val dirtyPages = HashSet<String>()

    /** Pages whose ink file on disk is out of date. */
    private val dirtyOnDisk = HashSet<String>()

    /** Per page, the strokes the journal already knows (identity comparison finds what to append). */
    private val journaled = HashMap<String, List<WebInkStroke>>()
    private val scroll = HashMap(base.scrollY)
    private val layout = HashMap(base.layoutWidth)

    /** Page the reader was on; hosts set it before saving. */
    @Volatile
    var lastPage: String? = base.lastPage

    /** Write / Interact for this document; shared by every holder, saved with the notes. */
    @Volatile
    var penMode: PenMode = PenMode.parse(base.penMode)
        set(value) {
            if (field == value) return
            field = value
            synchronized(lock) { changeSeq++ }
        }

    private class Pending(
        val snap: WebDocAnnotations,
        val seq: Long,
        val dirty: MutableSet<String>,
    )

    /** Latest snapshot not yet written, with the change counter it covers. */
    private var pending: Pending? = null
    private var changeSeq = 0L
    private var writtenSeq = 0L

    @Volatile private var saveCount = 0

    @Volatile private var lastSaveAtMs = 0L

    @Volatile private var lastSaveDurationMs = 0L

    @Volatile private var lastError: String? = null

    /** The ink on [href]; always the same instance for every holder. Main thread. */
    fun page(href: String): WebInkPage =
        synchronized(lock) {
            pages.getOrPut(href) { WebInkPage(strokesByPage[href].orEmpty()) }
        }

    fun scrollOf(href: String): Float = synchronized(lock) { scroll[href] ?: 0f }

    fun setScroll(
        href: String,
        y: Float,
    ) = synchronized(lock) { scroll[href] = y }

    fun layoutWidthOf(href: String): Float? = synchronized(lock) { layout[href] }

    /** Call after the page's strokes changed (added, erased, undone, cleared). */
    fun markInkChanged(
        href: String,
        layoutWidthCss: Float = 0f,
    ) = synchronized(lock) {
        dirtyPages.add(href)
        dirtyOnDisk.add(href)
        changeSeq++
        journalEditLocked(href, changeSeq)
        undoChanges.value++
        if (_saveState.value !is SaveState.Saving) _saveState.value = SaveState.Dirty
        if (layoutWidthCss > 0f && href !in layout && pages[href]?.strokes?.isNotEmpty() == true) layout[href] = layoutWidthCss
    }

    /** Queues a journal line describing how [href] changed since the last one (append, or replace). */
    private fun journalEditLocked(
        href: String,
        seq: Long,
    ) {
        val cur = pages[href]?.strokes?.toList().orEmpty()
        val prev = journaled[href] ?: strokesByPage[href].orEmpty()
        journaled[href] = cur
        val appended = cur.size >= prev.size && prev.indices.all { cur[it] === prev[it] }
        val op =
            if (appended) {
                if (cur.size == prev.size) return
                WebJournalOp(seq, href, "add", cur.subList(prev.size, cur.size))
            } else {
                WebJournalOp(seq, href, "set", cur)
            }
        io.execute {
            try {
                disk.journal.append(seq, WebInkStoreV2.opLine(WebDocLibrary.json, op))
            } catch (e: Exception) {
                Logger.w(TAG, "Journal append failed for $docId", e)
            }
        }
    }

    /** Immutable copy of everything (cheap: only pages touched since the last snapshot are re-listed). */
    fun snapshot(): WebDocAnnotations = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked(): WebDocAnnotations {
        for (href in dirtyPages) {
            val list = pages[href]?.strokes?.toList().orEmpty()
            if (list.isEmpty()) strokesByPage.remove(href) else strokesByPage[href] = list
        }
        dirtyPages.clear()
        return WebDocAnnotations(
            strokes = HashMap(strokesByPage),
            lastPage = lastPage,
            scrollY = HashMap(scroll),
            layoutWidth = HashMap(layout),
            penMode = penMode.name,
        )
    }

    /** Memory strokes on [href]. */
    fun memoryStrokeCount(href: String): Int = synchronized(lock) { pages[href]?.strokes?.size ?: strokesByPage[href]?.size ?: 0 }

    /** Strokes of [href] on disk right now (ink file plus journal), or null if the file cannot be read. Does disk IO. */
    fun diskStrokeCount(href: String): Int? = library.peekAnnotations(docId)?.let { it.strokes[href]?.size ?: 0 }

    /** Queues a background save of the current state; returns immediately. */
    fun save() {
        synchronized(lock) { pending = makePendingLocked() }
        _saveState.value = SaveState.Saving
        io.execute { writeLatest() }
    }

    /** Saves and waits until the disk is current (or [timeoutMs] passes). True if it finished in time. */
    fun flushBlocking(timeoutMs: Long = 2000): Boolean {
        synchronized(lock) { pending = makePendingLocked() }
        _saveState.value = SaveState.Saving
        val done = io.submit { writeLatest() }
        return try {
            done.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (e: TimeoutException) {
            Logger.w(TAG, "Flush of $docId did not finish in ${timeoutMs}ms; it continues in the background")
            false
        } catch (e: Exception) {
            Logger.w(TAG, "Flush of $docId failed", e)
            false
        }
    }

    /** Waits until queued journal appends reached the file. Tests only. */
    internal fun flushJournalForTest() {
        io.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun makePendingLocked(): Pending {
        val snap = snapshotLocked()
        val dirty = HashSet(dirtyOnDisk)
        dirtyOnDisk.clear()
        pending?.let { dirty.addAll(it.dirty) }
        return Pending(snap, changeSeq, dirty)
    }

    private fun writeLatest() {
        val p = synchronized(lock) { pending.also { pending = null } } ?: return
        val snap = p.snap
        val seq = p.seq
        val t0 = System.nanoTime()
        try {
            disk.write(snap, p.dirty, seq)
            synchronized(lock) {
                if (seq > writtenSeq) writtenSeq = seq
                _saveState.value = if (changeSeq > writtenSeq) SaveState.Dirty else SaveState.Clean
            }
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            _saveState.value = SaveState.Error(lastError ?: "save failed")
            Logger.e(TAG, "Failed to save notes of $docId", e, showToUser = true)
            // Keep the data pending so the next save retries it, unless something newer is already queued.
            synchronized(lock) { if (pending == null) pending = p else pending?.dirty?.addAll(p.dirty) }
        }
        lastSaveDurationMs = (System.nanoTime() - t0) / 1_000_000
        lastSaveAtMs = System.currentTimeMillis()
        saveCount++
    }

    fun stats(): SaveStats {
        val holders = synchronized(registryLock) { registry.values.firstOrNull { it.store === this }?.count ?: 0 }
        return synchronized(lock) {
            SaveStats(saveCount, lastSaveAtMs, lastSaveDurationMs, lastError, unsaved = changeSeq > writtenSeq || pending != null, holders = holders)
        }
    }
}
