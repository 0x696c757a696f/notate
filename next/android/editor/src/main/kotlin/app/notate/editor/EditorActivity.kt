package app.notate.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.View
import android.widget.Toast
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.doc.save.SaveState
import app.notate.core.doc.settings.FingerMode
import app.notate.core.input.GestureClassifier
import app.notate.core.input.HoverFocusDebouncer
import app.notate.core.input.InkArbiter
import app.notate.core.input.InputDispatcher
import app.notate.core.input.PalmRejector
import app.notate.core.input.PointerAction
import app.notate.core.input.PointerKind
import app.notate.core.input.PointerSample
import app.notate.core.model.DocId
import app.notate.core.input.EpdMode
import app.notate.core.input.EpdTiming
import app.notate.core.model.Capability
import app.notate.core.model.DocKind
import app.notate.core.model.PageId
import app.notate.core.model.PageLayoutMode
import app.notate.editor.pages.PageActions
import app.notate.editor.pages.PageGridHost
import app.notate.editor.pages.PageGridOverlay
import app.notate.editor.pages.PageIndicator
import app.notate.editor.pages.TemplatePicker
import app.notate.editor.pages.ThumbnailStore
import app.notate.editor.ui.dp
import app.notate.render.ink.GeometryCache
import app.notate.render.ink.InkRenderer
import app.notate.render.paper.BackgroundRendererSet
import app.notate.render.thumb.ThumbnailRenderer
import app.notate.core.input.BlockReason
import app.notate.editor.debug.DebugHud
import app.notate.editor.input.TouchAdapter
import app.notate.editor.chrome.EditorChrome
import app.notate.editor.pane.DocumentPane
import app.notate.editor.pane.NotebookPane
import app.notate.editor.pane.PaneHost
import app.notate.editor.popup.MenuEntry
import app.notate.editor.popup.MoreMenu
import app.notate.editor.toolbar.ToolbarActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Full-screen editor for one notebook: tab row and toolbar on top, one [NotebookPane] below. Main thread only. */
class EditorActivity : Activity(), PaneHost, ToolbarActions {
    override val arbiter = InkArbiter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val deps get() = EditorEnv.deps

    private lateinit var chrome: EditorChrome
    private lateinit var hud: DebugHud
    private lateinit var adapter: TouchAdapter
    private lateinit var palm: PalmRejector
    private lateinit var dispatcher: InputDispatcher
    private var handle: DocumentHandle? = null
    private var pane: DocumentPane? = null
    private var docId: DocId? = null
    private var pageActions: PageActions? = null
    private var indicator: PageIndicator? = null
    private var gridOverlay: PageGridOverlay? = null
    private var thumbs: ThumbnailStore? = null
    private var route = Route.NONE

    /** A touch sequence was handed on to the views because the pane leaves fingers to its own view (a web page). */
    private var forwardedToViews = false
    private val recentFingers = ArrayDeque<String>()
    private var ticking = false
    private val tickRunnable = object : Runnable {
        override fun run() {
            dispatcher.onTimer(SystemClock.uptimeMillis())
            if (ticking) main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_DOC_ID)
        if (id == null) {
            finish()
            return
        }
        docId = DocId(id)
        val density = resources.displayMetrics.density
        val xdpi = deps.device.xdpi
        adapter = TouchAdapter(xdpi)
        palm = PalmRejector(
            palmMinSizePx = PalmRejector.PALM_MIN_SIZE_MM * xdpi / 25.4f,
            pxPerCm = xdpi / 2.54f,
        )
        dispatcher = InputDispatcher(
            palm,
            GestureClassifier(touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat(), multiTapSlopPx = 24f * density),
            HoverFocusDebouncer(),
        ) { }
        buildViews()
        observeSettings()
        open()
    }

    private fun buildViews() {
        chrome = EditorChrome(
            this, deps.toolController, arbiter, deps.epd, deps.device.xdpi, this,
            onHome = { finish() }, onClose = { finish() },
        )
        chrome.popups.onRectChanged = { pane?.refreshExcludes() }
        chrome.onFocusChanged = { pane?.refreshExcludes() }
        hud = DebugHud(this) { hudLines() }
        setContentView(chrome)
    }

    private fun observeSettings() {
        val d = deps
        scope.launch {
            d.settings.fingerMode.collect { dispatcher.fingersEnabled = it == FingerMode.SCROLL }
        }
        scope.launch {
            d.settings.handedness.collect {
                palm.handedness = if (it == app.notate.core.doc.settings.Handedness.LEFT) app.notate.core.input.Handedness.LEFT else app.notate.core.input.Handedness.RIGHT
            }
        }
        scope.launch { d.settings.debugHud.collect { hud.setShown(it) } }
        scope.launch {
            d.toolController.state.collect { chrome.toolbar.render(d.toolController.baseState()) }
        }
    }

    private fun open() {
        val id = docId ?: return
        scope.launch {
            val h = try {
                deps.registry.acquire(id)
            } catch (e: Exception) {
                deps.logger.e(TAG, "Opening $id failed", e)
                Toast.makeText(this@EditorActivity, R.string.open_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            if (isDestroyed || isFinishing) {
                deps.registry.release(h)
                return@launch
            }
            handle = h
            val kind = h.editor.meta.value.kind
            val p = deps.paneFactories[kind]?.create(this@EditorActivity, h, deps, this@EditorActivity, scope)
                ?: NotebookPane(this@EditorActivity, h, deps, this@EditorActivity, scope)
            pane = p
            val area = chrome.paneArea
            area.addView(p.view, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            area.addView(hud, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            pageActions = PageActions(this@EditorActivity, h.editor, scope, deps.logger)
            if (Capability.PAGES in p.capabilities) {
                val ind = PageIndicator(this@EditorActivity) { openPageGrid() }
                indicator = ind
                area.addView(
                    ind,
                    android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.END)
                        .apply { setMargins(0, 0, ind.dp(16), ind.dp(16)) },
                )
            }
            val reason = if (h.editor.meta.value.kind == DocKind.WEB) R.string.unavailable_books else R.string.unavailable_here
            chrome.toolbar.bind(p.capabilities, getString(reason))
            arbiter.block(app.notate.core.input.BlockReason.WINDOW_UNFOCUSED, !hasWindowFocus())
            dispatcher.focusedPane = p.id
            bind(h)
        }
    }

    private fun bind(h: DocumentHandle) {
        scope.launch { h.editor.meta.collect { chrome.tabs.setTitle(it.title) } }
        scope.launch { h.editor.history.canUndo.collect { chrome.toolbar.setUndoRedo(it, h.editor.history.canRedo.value) } }
        scope.launch { h.editor.history.canRedo.collect { chrome.toolbar.setUndoRedo(h.editor.history.canUndo.value, it) } }
        scope.launch {
            var busy = false
            h.save.state.collect { st ->
                chrome.tabs.setSaveState(st)
                if (st == SaveState.Clean && busy) pane?.onSaveIdle(SystemClock.uptimeMillis())
                busy = st != SaveState.Clean
            }
        }
    }

    // --- PaneHost -------------------------------------------------------------------------------------------

    override fun excludeRects(): List<android.graphics.Rect> = pane?.let { chrome.excludeRectsFor(it.view) } ?: emptyList()

    override fun onFocusModeRequested() = chrome.setFocusMode(!chrome.focusMode)

    override fun showPopup(anchor: View?, content: View, placement: app.notate.editor.popup.Placement) =
        chrome.popups.show(anchor, content, placement)

    override fun dismissPopup() = chrome.popups.dismiss()
    override fun onPageGrid(hasGrid: Boolean) { chrome.toolbar.gridAvailable = hasGrid }

    override fun onDriverStroke(begin: Boolean, timeMs: Long) = dispatcher.onDriverStroke(begin, timeMs)

    override fun onPageChanged(index: Int, count: Int) {
        indicator?.setPage(index, count)
    }

    // --- Toolbar --------------------------------------------------------------------------------------------

    override fun onUndo() { handle?.editor?.undo() }

    override fun onRedo() { handle?.editor?.redo() }

    override fun onPages() = openPageGrid()

    override fun onSplit() = Unit

    override fun onMore(anchor: View) {
        val items = ArrayList<MenuEntry>()
        val p = pane
        val h = handle
        if (p != null && h != null && Capability.PAGES in p.capabilities) {
            val info = h.editor.pages.value.firstOrNull { it.id == p.currentPage }
            items += MenuEntry(getString(R.string.more_add_page)) { pageActions?.addAfter(p.currentPage) { p.goToPage(it) } }
            items += MenuEntry(getString(R.string.more_page_template)) { showTemplatePicker(p.currentPage) }
            items += MenuEntry(getString(if (info?.bookmarked == true) R.string.more_remove_bookmark else R.string.more_bookmark_page)) {
                p.currentPage?.let { pageActions?.toggleBookmark(it) }
            }
            val single = p.layoutMode == PageLayoutMode.SINGLE
            items += MenuEntry((if (single) "" else "✓ ") + getString(R.string.more_continuous)) { p.setLayoutMode(PageLayoutMode.CONTINUOUS) }
            items += MenuEntry((if (single) "✓ " else "") + getString(R.string.more_single_page)) { p.setLayoutMode(PageLayoutMode.SINGLE) }
        }
        items += MenuEntry(getString(R.string.more_focus_mode)) { chrome.setFocusMode(true) }
        items += MenuEntry(getString(R.string.more_refresh_screen)) { refreshScreen() }
        items += pane?.moreEntries() ?: emptyList()
        MoreMenu(chrome.popups, anchor, items).show()
    }

    // --- Pages ----------------------------------------------------------------------------------------------

    private fun showTemplatePicker(page: PageId?) {
        val h = handle ?: return
        val actions = pageActions ?: return
        val target = page ?: return
        val current = h.editor.pages.value.firstOrNull { it.id == target }?.background
        TemplatePicker(chrome.popups, this, current) { bg, all -> actions.setTemplate(target, bg, all) }.show()
    }

    private fun thumbnails(h: DocumentHandle): ThumbnailStore = thumbs ?: ThumbnailStore(
        h.editor,
        ThumbnailRenderer(BackgroundRendererSet(), InkRenderer(GeometryCache()), deps.dispatchers.render),
        deps.thumbsDir?.invoke(h.id), scope, deps.dispatchers.io, deps.logger,
    ).also { thumbs = it }

    private fun openPageGrid() {
        val h = handle ?: return
        val actions = pageActions ?: return
        if (gridOverlay != null) return
        chrome.popups.dismiss()
        val overlay = PageGridOverlay(
            this, h.editor, thumbnails(h), actions, chrome.popups,
            object : PageGridHost {
                override fun onJump(page: PageId) {
                    closePageGrid()
                    pane?.goToPage(page)
                }

                override fun onClose() = closePageGrid()

                override fun onChangeTemplate(page: PageId) = showTemplatePicker(page)
            },
            scope, pane?.currentPage,
        )
        gridOverlay = overlay
        chrome.paneArea.addView(overlay, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        arbiter.block(BlockReason.PAGE_GRID, true)
        deps.epd.refresh(chrome.paneArea, EpdMode.GU, null, EpdTiming.AFTER_DRAW)
    }

    private fun closePageGrid() {
        val overlay = gridOverlay ?: return
        gridOverlay = null
        chrome.popups.dismiss()
        overlay.release()
        chrome.paneArea.removeView(overlay)
        arbiter.block(BlockReason.PAGE_GRID, false)
        deps.epd.refresh(chrome.paneArea, EpdMode.GU, null, EpdTiming.AFTER_DRAW)
    }

    private fun hitsPageUi(ev: MotionEvent): Boolean {
        if (gridOverlay != null) return true
        val v = indicator ?: return false
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return ev.x >= loc[0] && ev.x < loc[0] + v.width && ev.y >= loc[1] && ev.y < loc[1] + v.height
    }

    override fun onToolbarChanged() {
        if (::chrome.isInitialized) deps.epd.refresh(chrome.toolbar, EpdMode.GU, null, EpdTiming.AFTER_DRAW)
    }

    private fun refreshScreen() {
        val p = pane
        val full = { deps.epd.refresh(chrome, EpdMode.GC, null, EpdTiming.NOW) }
        if (p != null) arbiter.pausedDuring(p, app.notate.core.input.BlockReason.REFRESH, full) else full()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            chrome.popups.isShowing -> chrome.popups.dismiss()
            gridOverlay != null -> closePageGrid()
            else -> super.onBackPressed()
        }
    }

    // --- Input ----------------------------------------------------------------------------------------------

    private enum class Route { NONE, SWALLOW, VIEWS }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        val end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        if (action == MotionEvent.ACTION_DOWN) {
            route = Route.NONE
            val popups = chrome.popups
            if (popups.interceptOutside(ev)) {      // a finger outside an open popup only closes it
                route = Route.SWALLOW
                return true
            }
            if (popups.isShowing && popups.contains(ev)) route = Route.VIEWS
            else if (hitsPageUi(ev)) route = Route.VIEWS
        }
        when (route) {
            Route.SWALLOW -> { if (end) route = Route.NONE; return true }
            Route.VIEWS -> { val r = super.dispatchTouchEvent(ev); if (end) route = Route.NONE; return r }
            Route.NONE -> Unit
        }
        val p = pane ?: return super.dispatchTouchEvent(ev)
        val panes = listOf<app.notate.core.input.PaneTarget>(p)
        var consumed = false
        for (s in adapter.convert(ev)) {
            val c = dispatcher.onSample(s, panes)
            consumed = consumed || c
            if (s.kind == PointerKind.FINGER && s.action == PointerAction.DOWN) rememberFinger(s, c)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> startTicking()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ticking = false
        }
        if (consumed) {
            // The page's view saw the start of this touch sequence but the dispatcher took it over (palm detected): cancel it there.
            if (forwardedToViews && !p.consumesFingers) {
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            forwardedToViews = false
            return true
        }
        forwardedToViews = !p.consumesFingers && !end
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val p = pane ?: return super.dispatchGenericMotionEvent(ev)
        val panes = listOf<app.notate.core.input.PaneTarget>(p)
        var consumed = false
        for (s in adapter.convert(ev)) consumed = dispatcher.onSample(s, panes) || consumed
        return if (consumed) true else super.dispatchGenericMotionEvent(ev)
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        main.postDelayed(tickRunnable, TICK_MS)
    }

    private fun rememberFinger(s: PointerSample, consumed: Boolean) {
        val palmish = palm.shouldReject(s.timeMs, s.x, s.y)
        recentFingers.addLast("${if (palmish) "palm" else "ok"} ${"%.1f".format(s.majorMm)}mm ${if (consumed) "used" else "passed"}")
        while (recentFingers.size > RECENT_FINGERS) recentFingers.removeFirst()
    }

    private fun hudLines(): List<String> {
        val p = pane ?: return listOf("(no pane)")
        val save = handle?.save?.state?.value
        return p.debugLines() + "save=$save" + "touch: ${recentFingers.joinToString(" | ").ifEmpty { "-" }}"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        arbiter.block(app.notate.core.input.BlockReason.WINDOW_UNFOCUSED, !hasFocus)
    }

    override fun onStop() {
        super.onStop()
        saveViewState()
        deps.appScope.launch { deps.registry.flushAll() }
    }

    private fun saveViewState() {
        val h = handle ?: return
        val state = pane?.saveViewState() ?: return
        val meta = h.editor.meta.value
        if (meta.view != state) h.editor.apply(app.notate.core.model.edit.Edit.SetMeta(meta, meta.copy(view = state)), undoable = false)
    }

    override fun onDestroy() {
        ticking = false
        main.removeCallbacksAndMessages(null)
        saveViewState()
        gridOverlay?.release()
        gridOverlay = null
        thumbs = null
        pane?.release()
        pane = null
        scope.cancel()
        handle?.let { deps.registry.release(it) }
        handle = null
        docId?.let { deps.onDocumentClosed(it) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EditorActivity"
        private const val EXTRA_DOC_ID = "doc_id"
        private const val TICK_MS = 50L
        private const val RECENT_FINGERS = 5

        fun intent(context: Context, id: DocId): Intent =
            Intent(context, EditorActivity::class.java).putExtra(EXTRA_DOC_ID, id.value)
    }
}
