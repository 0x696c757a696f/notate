package com.alexdremov.notate.doc

import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place saves are scheduled from. Screens say "this document changed" ([markDirty]); the
 * coordinator debounces, runs at most one save per document at a time, remembers failures (and
 * retries them with a growing delay), and can save everything now ([flushAll], on low memory or
 * when the app leaves the screen). [states] feeds the tab strip's saved / unsaved / failed marks.
 */
class SaveCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val debounceMs: Long = 1000,
    private val retryBaseMs: Long = 2000,
    private val retryMaxMs: Long = 30_000,
    /** Where `prepare` callbacks run (they touch views). */
    private val prepareContext: CoroutineDispatcher = Dispatchers.Main,
) {
    companion object {
        private const val TAG = "SaveCoordinator"

        /** How long closing a tab waits for its save before giving up and letting it finish in the background. */
        const val CLOSE_WAIT_MS = 3000L

        val instance: SaveCoordinator by lazy { SaveCoordinator() }
    }

    private class Entry(
        @Volatile var target: Saveable,
    ) {
        val mutex = Mutex()

        @Volatile var generation = 0L

        @Volatile var prepare: (() -> Unit)? = null
        var timer: Job? = null
        var failures = 0
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val _states = MutableStateFlow<Map<String, SaveState>>(emptyMap())

    /** Save state per document id (note path / web doc id). Missing means nothing to save. */
    val states: StateFlow<Map<String, SaveState>> = _states.asStateFlow()

    private fun setState(
        id: String,
        state: SaveState,
    ) {
        _states.value = _states.value + (id to state)
    }

    /** The latest target wins: a screen that reopens hands in its own save routine and the old one is dropped. */
    private fun entryFor(target: Saveable) = entries.getOrPut(target.ref.id) { Entry(target) }.also { it.target = target }

    /**
     * The document changed. The save runs after [debounceMs] of quiet; [prepare] (main thread)
     * runs right before it to copy view state (scroll, last page) into the document.
     */
    fun markDirty(
        target: Saveable,
        prepare: (() -> Unit)? = null,
    ) {
        val e = entryFor(target)
        synchronized(e) {
            e.generation++
            if (prepare != null) e.prepare = prepare
            if (_states.value[target.ref.id] !is SaveState.Saving) setState(target.ref.id, SaveState.Dirty)
            schedule(e, debounceMs)
        }
    }

    private fun schedule(
        e: Entry,
        delayMs: Long,
    ) {
        e.timer?.cancel()
        e.timer =
            scope.launch {
                delay(delayMs)
                runSave(e)
            }
    }

    private suspend fun runSave(e: Entry) {
        val id = e.target.ref.id
        e.mutex.withLock {
            val startGen: Long
            synchronized(e) { startGen = e.generation }
            val state = _states.value[id]
            if (state == null || state is SaveState.Clean) return
            setState(id, SaveState.Saving)
            try {
                e.prepare?.let { p -> withContext(prepareContext) { p() } }
                e.target.flush()
                e.failures = 0
                synchronized(e) {
                    setState(id, if (e.generation != startGen) SaveState.Dirty else SaveState.Clean)
                    if (e.generation != startGen) schedule(e, debounceMs)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Gave up waiting (tab closing, scope ending): the work is not lost, just not confirmed.
                synchronized(e) {
                    setState(id, SaveState.Dirty)
                    schedule(e, debounceMs)
                }
                throw ce
            } catch (ex: Exception) {
                e.failures++
                Logger.w(TAG, "Saving $id failed (attempt ${e.failures})", ex)
                synchronized(e) {
                    setState(id, SaveState.Error(ex.message ?: ex.javaClass.simpleName))
                    schedule(e, minOf(retryMaxMs, retryBaseMs shl minOf(e.failures - 1, 5)))
                }
                // The retry must run even though the state is Error, not Dirty.
            }
        }
    }

    /** Saves [target] now (cancelling its debounce). Returns true if it ended Clean. */
    suspend fun flush(target: Saveable): Boolean {
        val e = entries[target.ref.id] ?: return true
        e.timer?.cancel()
        if (_states.value[target.ref.id] is SaveState.Error) setState(target.ref.id, SaveState.Dirty)
        runSave(e)
        return _states.value[target.ref.id].let { it == null || it is SaveState.Clean }
    }

    /** True if the document has unsaved edits, a save running, or a failed save. */
    fun isPending(id: String): Boolean = _states.value[id].let { it != null && it !is SaveState.Clean }

    /** Saves the document registered under [id] and waits at most [timeoutMs]; true if it ended Clean (or had nothing to save). */
    suspend fun flushWithin(
        id: String,
        timeoutMs: Long = CLOSE_WAIT_MS,
    ): Boolean {
        val e = entries[id] ?: return true
        return flushWithin(e.target, timeoutMs)
    }

    /** [flush] with a deadline, for closing a tab: false means "still saving or failed". */
    suspend fun flushWithin(
        target: Saveable,
        timeoutMs: Long = CLOSE_WAIT_MS,
    ): Boolean = withTimeoutOrNull(timeoutMs) { flush(target) } ?: false

    /** Saves every document that is not clean, in parallel, and waits for all. */
    suspend fun flushAll() {
        val jobs =
            entries.values.map { e ->
                scope.launch {
                    e.timer?.cancel()
                    val id = e.target.ref.id
                    if (_states.value[id] is SaveState.Error) setState(id, SaveState.Dirty)
                    runSave(e)
                }
            }
        jobs.joinAll()
    }

    /** Fire-and-forget [flushAll] for callbacks that cannot suspend (onTrimMemory). */
    fun flushAllAsync() {
        scope.launch { flushAll() }
    }

    /** Forget a document (closed for good). Pending work is flushed first by the caller. */
    fun unregister(id: String) {
        entries.remove(id)?.timer?.cancel()
        _states.value = _states.value - id
    }
}
