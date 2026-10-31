package com.alexdremov.notate.webdoc

import com.alexdremov.notate.util.Logger
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
 */
class WebAnnotationStore internal constructor(
    val docId: String,
    private val library: WebDocLibrary,
    private val io: ExecutorService = sharedIo,
) {
    companion object {
        private const val TAG = "WebAnnotationStore"

        /** Relative width change above which the layout is considered different. */
        const val LAYOUT_DRIFT_FRACTION = 0.05f

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
    private val base = library.loadAnnotations(docId)
    private val pages = HashMap<String, WebInkPage>()
    private val strokesByPage = HashMap(base.strokes)
    private val dirtyPages = HashSet<String>()
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

    /** Latest snapshot not yet written, with the change counter it covers. */
    private var pending: Pair<WebDocAnnotations, Long>? = null
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
        changeSeq++
        if (layoutWidthCss > 0f && href !in layout && pages[href]?.strokes?.isNotEmpty() == true) layout[href] = layoutWidthCss
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

    /** Strokes of [href] in annotations.json right now, or null if the file cannot be read. Does disk IO. */
    fun diskStrokeCount(href: String): Int? = library.peekAnnotations(docId)?.let { it.strokes[href]?.size ?: 0 }

    /** Queues a background save of the current state; returns immediately. */
    fun save() {
        synchronized(lock) { pending = snapshotLocked() to changeSeq }
        io.execute { writeLatest() }
    }

    /** Saves and waits until the disk is current (or [timeoutMs] passes). True if it finished in time. */
    fun flushBlocking(timeoutMs: Long = 2000): Boolean {
        synchronized(lock) { pending = snapshotLocked() to changeSeq }
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

    private fun writeLatest() {
        val (snap, seq) = synchronized(lock) { pending.also { pending = null } } ?: return
        val t0 = System.nanoTime()
        try {
            library.saveAnnotations(docId, snap)
            synchronized(lock) { if (seq > writtenSeq) writtenSeq = seq }
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Logger.e(TAG, "Failed to save notes of $docId", e, showToUser = true)
            // Keep the data pending so the next save retries it, unless something newer is already queued.
            synchronized(lock) { if (pending == null) pending = snap to seq }
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
