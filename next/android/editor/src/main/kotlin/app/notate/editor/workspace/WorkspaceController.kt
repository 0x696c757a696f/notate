package app.notate.editor.workspace

import android.content.Context
import android.graphics.Rect
import android.view.View
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.doc.save.SaveState
import app.notate.core.model.Capability
import app.notate.core.model.DocId
import app.notate.editor.EditorDeps
import app.notate.editor.R
import app.notate.editor.pages.PageActions
import app.notate.editor.pages.PageIndicator
import app.notate.editor.pane.NotebookPane
import app.notate.editor.pane.PaneHost
import app.notate.core.workspace.PaneViewStateDto
import app.notate.core.workspace.Tab
import app.notate.core.workspace.Workspace
import app.notate.core.workspace.WorkspaceOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Owns the open tabs of the editor window: the [Workspace], one registry reference (document handle, with its undo history)
 * per tab that has been shown, and the [PaneCache] of live panes. Switching to a cached tab swaps the pane's view in without
 * loading anything. Main thread only.
 */
class WorkspaceController(
    private val context: Context,
    private val deps: EditorDeps,
    private val scope: CoroutineScope,
    private val host: PaneHost,
    private val rectsFor: (View) -> List<Rect>,
    private val listener: Listener,
    maxPanes: Int = PaneCache.DEFAULT_MAX,
) {
    interface Listener {
        /** The visible pane changed: swap the views, rebind the toolbar. [old] has not been released yet. */
        fun onSessionChanged(old: PaneSession?, new: PaneSession?)
        fun onMessage(text: String)
        /** The last tab was closed. */
        fun onEmpty()
    }

    private val _workspace = MutableStateFlow(Workspace())
    val workspace: StateFlow<Workspace> get() = _workspace

    /** Save state of every tab whose document has been opened; tabs that were never shown have no entry. */
    private val _saveStates = MutableStateFlow<Map<DocId, SaveState>>(emptyMap())
    val saveStates: StateFlow<Map<DocId, SaveState>> get() = _saveStates

    /** Tabs that are waiting for their last write before they close. */
    private val _closing = MutableStateFlow<Set<DocId>>(emptySet())
    val closing: StateFlow<Set<DocId>> get() = _closing

    var current: PaneSession? = null
        private set

    private val handles = HashMap<DocId, DocumentHandle>()
    private val watchers = HashMap<DocId, List<Job>>()
    private val cache = PaneCache<PaneSession>(maxPanes) { _, s -> s.close() }
    private var generation = 0
    private var shutDown = false

    /** Takes over a workspace read from disk and shows its focused tab. */
    suspend fun restore(ws: Workspace) {
        _workspace.value = ws
        showFocused()
    }

    /** Opens [id] in a tab, or switches to its tab when it is already open. */
    suspend fun open(id: DocId) {
        val existing = _workspace.value.indexOf(id)
        if (existing >= 0) {
            select(existing)
            return
        }
        val h = acquire(id) ?: return
        val meta = h.editor.meta.value
        _workspace.update { WorkspaceOps.open(it, Tab(id, meta.kind, meta.title)) }
        showFocused()
    }

    /** Shows tab [index] in the focused pane. */
    fun select(index: Int) {
        val before = _workspace.value
        _workspace.update { WorkspaceOps.select(it, index) }
        if (before === _workspace.value && current?.doc == before.focusedTab?.doc) return
        scope.launch { showFocused() }
    }

    /** Closes tab [index] once its last edits are on disk (waits up to 3 s). Returns false when the tab stayed open. */
    suspend fun close(index: Int): Boolean {
        val tab = _workspace.value.tabs.getOrNull(index) ?: return false
        return closeDoc(tab.doc)
    }

    /** Closes every tab except [keep]; stops at the first one that cannot be saved. */
    suspend fun closeOthers(keep: Int) {
        val keepDoc = _workspace.value.tabs.getOrNull(keep)?.doc ?: return
        for (doc in _workspace.value.tabs.map { it.doc }) {
            if (doc != keepDoc && !closeDoc(doc)) return
        }
    }

    /** Remembers where the reader is in every live pane (call when the window stops). */
    fun persistViews() {
        for (s in cache.values()) s.persistView()
        val cur = current ?: return
        val index = _workspace.value.indexOf(cur.doc)
        val state = cur.pane.saveViewState()?.let { PaneViewStateDto.from(it) }
        if (index >= 0 && state != null) _workspace.update { WorkspaceOps.withView(it, index, state) }
    }

    /** Releases every pane and document reference; the workspace itself stays as it is. */
    fun shutdown() {
        if (shutDown) return
        shutDown = true
        persistViews()
        val old = current
        current = null
        old?.let { it.host.activate(false) }
        cache.drain().forEach { it.close() }
        for (job in watchers.values.flatten()) job.cancel()
        watchers.clear()
        for ((id, h) in handles) {
            deps.registry.release(h)
            deps.onDocumentClosed(id)
        }
        handles.clear()
    }

    // --- internals ------------------------------------------------------------------------------------------

    private suspend fun showFocused() {
        val gen = ++generation
        while (true) {
            val ws = _workspace.value
            val tab = ws.focusedTab
            if (tab == null) {
                setCurrent(null)
                return
            }
            cache[tab.doc]?.let { setCurrent(it); return }
            val h = acquire(tab.doc)
            if (gen != generation) return
            if (h == null) {       // could not be opened: drop the tab and try the one that takes its place
                _workspace.update { WorkspaceOps.close(it, it.indexOf(tab.doc)) }
                if (_workspace.value.isEmpty) { setCurrent(null); listener.onEmpty(); return }
                continue
            }
            val session = createSession(h)
            cache.put(tab.doc, session, keep = setOfNotNull(tab.doc, current?.doc))
            setCurrent(session)
            return
        }
    }

    private fun setCurrent(new: PaneSession?) {
        val old = current
        if (old === new) return
        old?.let {
            it.host.activate(false)
            it.pane.onFocusChanged(false)
            it.persistView()
        }
        current = new
        listener.onSessionChanged(old, new)
        if (new == null) {
            host.arbiter.setOwner(null)
        } else {
            new.host.activate(true)
            new.pane.onFocusChanged(true)
            // A pane that has been laid out before is attached already and takes the pen back here; a new one claims it when it
            // gets its size, so the old owner is let go meanwhile.
            host.arbiter.setOwner(if (new.shown) new.pane else null)
            new.shown = true
            new.pane.refreshExcludes()
        }
        if (old != null && !holds(old)) old.close()       // a closed tab's pane
    }

    private fun holds(s: PaneSession) = cache.peek(s.doc) === s

    private fun createSession(h: DocumentHandle): PaneSession {
        val sessionScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        val gated = GatedHost(host, rectsFor)
        val kind = h.editor.meta.value.kind
        val pane = deps.paneFactories[kind]?.create(context, h, deps, gated, sessionScope)
            ?: NotebookPane(context, h, deps, gated, sessionScope)
        gated.view = pane.view
        val indicator = if (Capability.PAGES in pane.capabilities) PageIndicator(context) { pageGridRequest?.invoke() } else null
        gated.indicator = indicator
        return PaneSession(h, pane, sessionScope, gated, PageActions(context, h.editor, sessionScope, deps.logger), indicator)
    }

    /** Set by the window: the page indicator of the visible pane was tapped. */
    var pageGridRequest: (() -> Unit)? = null

    private suspend fun acquire(id: DocId): DocumentHandle? {
        handles[id]?.let { return it }
        val h = try {
            deps.registry.acquire(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deps.logger.e(TAG, "Opening $id failed", e)
            listener.onMessage(context.getString(R.string.picker_open_failed))
            return null
        }
        if (shutDown) {
            deps.registry.release(h)
            return null
        }
        val have = handles[id]
        if (have != null) {            // another call got there first; keep one reference only
            deps.registry.release(h)
            return have
        }
        handles[id] = h
        watchers[id] = listOf(
            scope.launch { h.save.state.collect { st -> _saveStates.update { it + (id to st) } } },
            scope.launch { h.editor.meta.collect { m -> _workspace.update { WorkspaceOps.rename(it, id, m.title) } } },
        )
        return h
    }

    private suspend fun closeDoc(doc: DocId): Boolean {
        val h = handles[doc]
        if (h != null) {
            _closing.update { it + doc }
            cache.peek(doc)?.persistView()
            val saved = try {
                withTimeout(SAVE_WAIT_MS) { h.save.flush() }
                true
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) false else throw e
            } catch (e: Exception) {
                deps.logger.e(TAG, "Saving $doc before closing failed", e)
                false
            } finally {
                _closing.update { it - doc }
            }
            if (!saved) {
                listener.onMessage(context.getString(R.string.tab_save_failed))
                return false
            }
        }
        val session = cache.remove(doc)
        _workspace.update { WorkspaceOps.close(it, it.indexOf(doc)) }
        if (_workspace.value.isEmpty) {
            setCurrent(null)
            session?.close()
            release(doc)
            listener.onEmpty()
            return true
        }
        if (current === session) {
            showFocused()        // swaps the view; setCurrent closes the old pane because it is no longer cached
        } else {
            session?.close()
        }
        release(doc)
        return true
    }

    private fun release(doc: DocId) {
        watchers.remove(doc)?.forEach { it.cancel() }
        handles.remove(doc)?.let { deps.registry.release(it) }
        _saveStates.update { it - doc }
        deps.onDocumentClosed(doc)
    }

    private companion object {
        const val TAG = "WorkspaceController"
        const val SAVE_WAIT_MS = 3_000L
    }
}
