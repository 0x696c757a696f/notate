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
import app.notate.core.input.PaneId
import app.notate.core.workspace.Orientation
import app.notate.core.workspace.PaneViewStateDto
import app.notate.core.workspace.SplitSpec
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
 * per tab that has been shown, the (one or two) panes on screen and the [PaneCache] of panes that are open but hidden.
 * Switching to a cached tab swaps the pane's view in without loading anything; a document may be shown in both panes of a
 * split (two panes, one handle). Main thread only.
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
        /** The panes on screen or the split changed: lay the views out. A pane that left has not been released yet. */
        fun onPanesChanged(left: PaneSession?, right: PaneSession?, split: SplitSpec?)

        /** The focus moved to [focused] (null: nothing is open): rebind the toolbar, undo and redo. */
        fun onFocusChanged(focused: PaneSession?)
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

    var left: PaneSession? = null
        private set
    var right: PaneSession? = null
        private set

    /** The pane that has the focus (the left one when the window is not split). */
    val current: PaneSession? get() = if (_workspace.value.focusRight && right != null) right else left

    /** Both panes on screen, left first. */
    val displayed: List<PaneSession> get() = listOfNotNull(left, right)

    private val handles = HashMap<DocId, DocumentHandle>()
    private val watchers = HashMap<DocId, List<Job>>()
    private val cache = PaneCache<PaneSession>(maxPanes) { _, s -> s.close() }
    private var generation = 0
    private var shutDown = false
    private var paneSeq = 0
    private var announcedLeft: PaneSession? = null
    private var announcedRight: PaneSession? = null
    private var announcedSplit: SplitSpec? = null
    private var announcedFocus: PaneSession? = null

    /** Takes over a workspace read from disk and shows its panes. */
    suspend fun restore(ws: Workspace) {
        _workspace.value = ws
        sync()
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
        sync()
    }

    /** Opens [id] in a second pane next to the focused one (the other pane of a split is replaced). */
    suspend fun openBeside(id: DocId) {
        val h = acquire(id) ?: return
        val meta = h.editor.meta.value
        _workspace.update { WorkspaceOps.openBeside(it, Tab(id, meta.kind, meta.title)) }
        sync()
    }

    /** Shows tab [index] in the focused pane. */
    fun select(index: Int) {
        val before = _workspace.value
        _workspace.update { WorkspaceOps.select(it, index) }
        if (before === _workspace.value && current?.doc == before.focusedTab?.doc) return
        scope.launch { sync() }
    }

    /** Shows tab [index] in the pane that does not have the focus, opening a split when there is none. */
    fun openTabBeside(index: Int) {
        val tab = _workspace.value.tabs.getOrNull(index) ?: return
        _workspace.update { WorkspaceOps.openBeside(it, tab) }
        scope.launch { sync() }
    }

    /** Gives the focus to the pane with this id (the pen came down on it, hovered over it, or a finger tapped it). */
    fun focus(pane: PaneId) {
        val toRight = when (pane) {
            right?.pane?.id -> true
            left?.pane?.id -> false
            else -> return
        }
        _workspace.update { WorkspaceOps.focus(it, toRight) }
        applyFocus()
    }

    /** Exchanges the two panes. */
    fun swapSides() = change { WorkspaceOps.swap(it) }

    fun setOrientation(orientation: Orientation) = change { WorkspaceOps.setOrientation(it, orientation) }

    fun setRatio(ratio: Float) = change { WorkspaceOps.setRatio(it, ratio) }

    /** Closes one pane; its tab stays open. */
    fun closePane(right: Boolean) = change { WorkspaceOps.closePane(it, right) }

    /** Leaves the split and keeps only that pane. */
    fun maximize(right: Boolean) = change { WorkspaceOps.maximize(it, right) }

    private fun change(op: (Workspace) -> Workspace) {
        val before = _workspace.value
        _workspace.update(op)
        if (before !== _workspace.value) scope.launch { sync() }
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
        for (s in displayed) s.persistView()
        var ws = _workspace.value
        left?.pane?.saveViewState()?.let { ws = WorkspaceOps.withPaneView(ws, false, PaneViewStateDto.from(it)) }
        right?.pane?.saveViewState()?.let { ws = WorkspaceOps.withPaneView(ws, true, PaneViewStateDto.from(it)) }
        _workspace.value = ws
    }

    /** Releases every pane and document reference; the workspace itself stays as it is. */
    fun shutdown() {
        if (shutDown) return
        shutDown = true
        persistViews()
        val shown = displayed
        left = null
        right = null
        shown.forEach { it.host.activate(false); it.close() }
        cache.drain().forEach { it.close() }
        host.arbiter.setOwner(null)
        for (job in watchers.values.flatten()) job.cancel()
        watchers.clear()
        for ((id, h) in handles) {
            deps.registry.release(h)
            deps.onDocumentClosed(id)
        }
        handles.clear()
    }

    // --- internals ------------------------------------------------------------------------------------------

    /** Makes the panes on screen match the workspace: opens what is missing, hides what left, then settles the focus. */
    private suspend fun sync() {
        val gen = ++generation
        while (true) {
            val ws = _workspace.value
            val wanted = listOf(ws.left, ws.right).map { slot ->
                slot?.let { sl -> ws.tabs.getOrNull(sl.tab)?.let { it to sl.view } }
            }
            var failed: DocId? = null
            for (w in wanted) {
                val tab = w?.first ?: continue
                if (acquire(tab.doc) == null) { failed = tab.doc; break }
                if (gen != generation) return
            }
            val bad = failed
            if (bad != null) {       // could not be opened: drop the tab and try the one that takes its place
                _workspace.update { WorkspaceOps.close(it, it.indexOf(bad)) }
                if (_workspace.value.isEmpty) {
                    applyPanes(_workspace.value, listOf(null, null))
                    listener.onEmpty()
                    return
                }
                continue
            }
            if (gen != generation) return
            applyPanes(ws, wanted)
            return
        }
    }

    private fun applyPanes(ws: Workspace, wanted: List<Pair<Tab, PaneViewStateDto?>?>) {
        val pool = displayed.toMutableList()
        val next = arrayOfNulls<PaneSession>(2)
        for (i in 0..1) {                // a pane that already shows the document stays (also when the sides were swapped)
            val doc = wanted[i]?.first?.doc ?: continue
            val m = pool.firstOrNull { it.doc == doc } ?: continue
            pool.remove(m)
            next[i] = m
        }
        val keep = wanted.mapNotNull { it?.first?.doc }.toSet()
        for (i in 0..1) {
            val (tab, view) = wanted[i] ?: continue
            if (next[i] != null) continue
            next[i] = cache.remove(tab.doc) ?: createSession(handles.getValue(tab.doc), view)
        }
        for (s in pool) {                // panes that left the screen
            s.host.activate(false)
            s.host.focused = false
            if (s.focusAnnounced != false) { s.focusAnnounced = false; s.pane.onFocusChanged(false) }
            s.persistView()
            if (ws.indexOf(s.doc) < 0 || cache.peek(s.doc) != null) s.close() else cache.put(s.doc, s, keep)
        }
        left = next[0]
        right = next[1]
        left?.persistsMeta = true
        right?.persistsMeta = left?.doc != right?.doc
        val focused = if (ws.focusRight && right != null) right else left
        for (s in displayed) {
            s.host.focused = s === focused
            s.host.activate(true)
        }
        if (left !== announcedLeft || right !== announcedRight || ws.split != announcedSplit) {
            announcedLeft = left
            announcedRight = right
            announcedSplit = ws.split
            listener.onPanesChanged(left, right, ws.split)
        }
        applyFocus()
    }

    /** Tells the panes, the pen arbiter and the window which pane has the focus now. */
    private fun applyFocus() {
        val focused = current
        for (s in displayed) {
            val on = s === focused
            s.host.focused = on
            if (s.focusAnnounced != on) {
                s.focusAnnounced = on
                s.pane.onFocusChanged(on)
                if (!on) s.persistView()
            }
        }
        if (focused == null) {
            host.arbiter.setOwner(null)
        } else {
            // A pane that has a size is attached to the arbiter and takes the pen now (the old owner's driver closes first). One
            // that has not been laid out yet claims the pen when it gets its size, so the old owner is let go meanwhile.
            host.arbiter.setOwner(if (host.arbiter.isAttached(focused.pane)) focused.pane else null)
            focused.pane.refreshExcludes()
            focused.host.pushGrid()
        }
        if (focused !== announcedFocus) {
            announcedFocus = focused
            listener.onFocusChanged(focused)
        }
    }

    private fun createSession(h: DocumentHandle, view: PaneViewStateDto?): PaneSession {
        val sessionScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        val gated = GatedHost(host, rectsFor)
        val kind = h.editor.meta.value.kind
        val id = PaneId("pane-${++paneSeq}")
        val pane = deps.paneFactories[kind]?.create(context, h, deps, gated, sessionScope, id)
            ?: NotebookPane(context, h, deps, gated, sessionScope, id)
        view?.let { pane.setInitialView(it.toViewState()) }
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
            if (deps.onOpenFailed?.invoke(context, id) != true) listener.onMessage(context.getString(R.string.picker_open_failed))
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
            displayed.filter { it.doc == doc }.forEach { it.persistView() }
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
        cache.remove(doc)?.close()
        _workspace.update { WorkspaceOps.close(it, it.indexOf(doc)) }
        sync()          // panes that showed the tab move on or close; the ones of this document are released
        release(doc)
        if (_workspace.value.isEmpty) listener.onEmpty()
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
