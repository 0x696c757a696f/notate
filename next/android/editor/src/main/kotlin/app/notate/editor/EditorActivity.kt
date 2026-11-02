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
import app.notate.core.workspace.DividerDrop
import app.notate.core.workspace.Orientation
import app.notate.core.workspace.SplitSpec
import app.notate.editor.split.PaneFrame
import app.notate.editor.split.SplitPaneLayout
import app.notate.editor.chrome.DocumentPickerDialog
import app.notate.editor.chrome.TabStripView
import app.notate.editor.workspace.PaneSession
import app.notate.editor.workspace.WorkspaceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
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
import kotlinx.coroutines.withContext

/**
 * The editor window (single instance): tab row and toolbar on top, the visible document's pane below. Which documents are open
 * is the [WorkspaceController]'s business; opening a document from anywhere ends up in [onNewIntent]. Main thread only.
 */
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
    private lateinit var controller: WorkspaceController
    private val pane: DocumentPane? get() = controller.current?.pane
    private val handle: DocumentHandle? get() = controller.current?.handle
    private val pageActions: PageActions? get() = controller.current?.pageActions
    private lateinit var splitLayout: SplitPaneLayout
    private var gridOverlay: PageGridOverlay? = null
    private var bindJob: Job? = null
    private var workspaceStore: app.notate.core.workspace.WorkspaceStore? = null
    private val ready = CompletableDeferred<Unit>()
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
        ) { id -> controller.focus(id) }
        buildViews()
        observeSettings()
        scope.launch {
            val store = withContext(deps.dispatchers.io) { deps.workspaceStore?.invoke() }
            workspaceStore = store
            val ws = store?.load() ?: app.notate.core.workspace.Workspace()
            controller.restore(ws)
            // Only now: saving the still-empty initial state would wipe what was just read.
            scope.launch { controller.workspace.collect { store?.save(it) } }
            ready.complete(Unit)
            handleIntent(intent)
            if (controller.workspace.value.isEmpty) goHome()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        scope.launch {
            ready.await()
            handleIntent(intent)
        }
    }

    private suspend fun handleIntent(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_DOC_ID) ?: return
        intent.removeExtra(EXTRA_DOC_ID)
        controller.open(DocId(id))
    }

    private fun buildViews() {
        controller = WorkspaceController(
            this, deps, scope, this, { v -> chrome.excludeRectsFor(v) },
            object : WorkspaceController.Listener {
                override fun onPanesChanged(left: PaneSession?, right: PaneSession?, split: SplitSpec?) = layoutPanes(left, right, split)
                override fun onFocusChanged(focused: PaneSession?) = bindFocused(focused)
                override fun onMessage(text: String) { Toast.makeText(this@EditorActivity, text, Toast.LENGTH_LONG).show() }
                override fun onEmpty() = goHome()
            },
        )
        controller.pageGridRequest = { openPageGrid() }
        chrome = EditorChrome(this, deps.toolController, arbiter, deps.epd, deps.device.xdpi, this, tabCallbacks())
        splitLayout = SplitPaneLayout(this, dividerListener())
        chrome.paneArea.addView(splitLayout, 0, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chrome.popups.onRectChanged = { controller.displayed.forEach { it.pane.refreshExcludes() } }
        chrome.onFocusChanged = { controller.displayed.forEach { it.pane.refreshExcludes() } }
        hud = DebugHud(this) { hudLines() }
        chrome.paneArea.addView(hud, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(chrome)
        scope.launch {
            combine(controller.workspace, controller.saveStates, controller.closing) { ws, st, cl -> Triple(ws, st, cl) }
                .collect { (ws, st, cl) -> chrome.tabs.render(ws, st, cl) }
        }
    }

    private fun tabCallbacks() = object : TabStripView.Callbacks {
        override fun onHome() = startActivity(libraryIntent())
        override fun onSelect(index: Int) = controller.select(index)
        override fun onClose(index: Int) { scope.launch { controller.close(index) } }
        override fun onNew(anchor: View) = showPicker()
        override fun onLongPress(index: Int, anchor: View) {
            MoreMenu(
                chrome.popups, anchor,
                listOf(
                    MenuEntry(getString(R.string.tab_menu_close)) { scope.launch { controller.close(index) } },
                    MenuEntry(getString(R.string.tab_menu_close_others)) { scope.launch { controller.closeOthers(index) } },
                    MenuEntry(getString(R.string.tab_menu_split)) { controller.openTabBeside(index) },
                ),
            ).show()
        }
    }

    private fun showPicker() {
        val library = deps.libraryStore?.invoke() ?: return
        DocumentPickerDialog(chrome.popups, library, scope) { id -> scope.launch { controller.open(id) } }.show(this)
    }

    /** "Open beside": the picker, but the document opens in a second pane. */
    private fun showBesidePicker() {
        val library = deps.libraryStore?.invoke() ?: return
        DocumentPickerDialog(chrome.popups, library, scope, R.string.split_open_beside) { id -> scope.launch { controller.openBeside(id) } }.show(this)
    }

    private fun dividerListener() = object : SplitPaneLayout.Listener {
        override fun onDividerDragStarted() {
            chrome.popups.dismiss()
            arbiter.block(BlockReason.DIVIDER_DRAG, true)
        }

        override fun onDividerDropped(drop: DividerDrop) {
            arbiter.block(BlockReason.DIVIDER_DRAG, false)
            when (drop) {
                is DividerDrop.Ratio -> controller.setRatio(drop.value)
                DividerDrop.CloseFirst -> controller.closePane(right = false)
                DividerDrop.CloseSecond -> controller.closePane(right = true)
            }
        }

        override fun onDividerDragCancelled() = arbiter.block(BlockReason.DIVIDER_DRAG, false)
    }

    private fun libraryIntent(): Intent = deps.libraryIntent?.invoke(this) ?: Intent()

    /** Leaves the editor for the bookshelf; the bookshelf is started first when the editor is the only screen. */
    private fun goHome() {
        if (isFinishing) return
        if (isTaskRoot) startActivity(libraryIntent())
        finish()
    }

    // --- Switching documents --------------------------------------------------------------------------------

    /** The frame (bar, focus strip) around a session's pane; built once, when the session is first shown. */
    private fun frameOf(s: PaneSession): PaneFrame = s.frame ?: PaneFrame(this, s.pane.ownsPaneBar).also { f ->
        s.frame = f
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        f.content.addView(s.pane.view, 0, android.widget.FrameLayout.LayoutParams(match, match))
        s.indicator?.let { ind ->
            f.content.addView(
                ind, 1,
                android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.END)
                    .apply { setMargins(0, 0, ind.dp(16), ind.dp(16)) },
            )
        }
        s.host.onPage = { index, count -> f.setPage(index, count) }
        s.scope.launch { s.handle.editor.meta.collect { f.setTitle(it.title) } }
    }

    private fun layoutPanes(left: PaneSession?, right: PaneSession?, split: SplitSpec?) {
        closePageGrid()
        chrome.popups.dismiss()
        val lf = left?.let(::frameOf)
        val rf = right?.let(::frameOf)
        if (split != null) {
            splitLayout.orientation = split.orientation
            splitLayout.ratio = split.ratio
        }
        splitLayout.setPanes(lf, rf)
        val isSplit = rf != null
        lf?.setSplit(isSplit, { controller.maximize(right = false) }, { controller.closePane(right = false) })
        rf?.setSplit(true, { controller.maximize(right = true) }, { controller.closePane(right = true) })
        val focused = controller.current
        lf?.setFocused(left === focused)
        rf?.setFocused(right === focused)
        deps.epd.refresh(chrome.paneArea, EpdMode.GU, null, EpdTiming.AFTER_DRAW)
    }

    /** The focus moved: toolbar, undo and redo follow the focused pane; only the bars and the toolbar are refreshed. */
    private fun bindFocused(new: PaneSession?) {
        for (s in controller.displayed) s.frame?.setFocused(s === new)
        bindJob?.cancel()
        bindJob = null
        dispatcher.focusedPane = new?.pane?.id
        if (new == null) return
        val kind = new.handle.editor.meta.value.kind
        val reason = if (kind == DocKind.WEB) R.string.unavailable_books else R.string.unavailable_here
        chrome.toolbar.bind(new.pane.capabilities, getString(reason))
        arbiter.block(BlockReason.WINDOW_UNFOCUSED, !hasWindowFocus())
        val h = new.handle
        bindJob = scope.launch {
            launch { combine(h.editor.history.canUndo, h.editor.history.canRedo) { u, r -> u to r }.collect { (u, r) -> chrome.toolbar.setUndoRedo(u, r) } }
            launch {
                var busy = false
                h.save.state.collect { st ->
                    if (st == app.notate.core.doc.save.SaveState.Clean && busy) new.pane.onSaveIdle(SystemClock.uptimeMillis())
                    busy = st != app.notate.core.doc.save.SaveState.Clean
                }
            }
        }
        deps.epd.refresh(chrome.toolbar, EpdMode.GU, null, EpdTiming.AFTER_DRAW)
        for (s in controller.displayed) {
            val f = s.frame ?: continue
            if (f.split) deps.epd.refresh(f, EpdMode.GU, intArrayOf(0, 0, f.width, f.dp(PaneFrame.BAR_DP + 3)), EpdTiming.AFTER_DRAW)
        }
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
        scope.launch {
            d.settings.penButton.collect {
                dispatcher.penButton = when (it) {
                    app.notate.core.doc.settings.PenButton.ERASER -> app.notate.core.input.PenButtonAction.ERASER
                    app.notate.core.doc.settings.PenButton.LASSO -> app.notate.core.input.PenButtonAction.LASSO
                    app.notate.core.doc.settings.PenButton.NONE -> app.notate.core.input.PenButtonAction.NONE
                }
            }
        }
        scope.launch {
            val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            d.settings.toolbarPos.collect { chrome.toolbarLeft = landscape && it == app.notate.core.doc.settings.ToolbarPos.LEFT }
        }
        scope.launch { d.settings.debugHud.collect { hud.setShown(it) } }
        scope.launch {
            d.toolController.state.collect { chrome.toolbar.render(d.toolController.baseState()) }
        }
    }

    // --- PaneHost -------------------------------------------------------------------------------------------

    override fun excludeRects(): List<android.graphics.Rect> = pane?.let { chrome.excludeRectsFor(it.view) } ?: emptyList()

    override fun onFocusModeRequested() = chrome.setFocusMode(!chrome.focusMode)

    override fun showPopup(anchor: View?, content: View, placement: app.notate.editor.popup.Placement) =
        chrome.popups.show(anchor, content, placement)

    override fun dismissPopup() = chrome.popups.dismiss()
    override fun onPageGrid(hasGrid: Boolean) { chrome.toolbar.gridAvailable = hasGrid }

    override fun openDocumentAt(doc: DocId, page: app.notate.core.model.PageId) {
        scope.launch {
            controller.open(doc)
            // The new tab's pane gets its size on the next layout pass; jump once it has one.
            main.postDelayed({ if (controller.current?.doc == doc) pane?.goToPage(page) }, OPEN_AT_DELAY_MS)
        }
    }

    override fun onDriverStroke(begin: Boolean, timeMs: Long) = dispatcher.onDriverStroke(begin, timeMs)

    override fun onPageChanged(index: Int, count: Int) = Unit      // each pane's own indicator follows it (GatedHost)

    // --- Toolbar --------------------------------------------------------------------------------------------

    override fun onInsertImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(i, REQ_IMAGE)
        } catch (e: android.content.ActivityNotFoundException) {
            deps.logger.e(TAG, "No image picker", e)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK) data?.data?.let { pane?.importImage(it) }
    }

    override fun onUndo() { handle?.editor?.undo() }

    override fun onRedo() { handle?.editor?.redo() }

    override fun onPages() = openPageGrid()

    override fun onSplit(anchor: View) {
        val split = controller.workspace.value.split
        if (split == null) {
            showBesidePicker()
            return
        }
        val horizontal = split.orientation == Orientation.HORIZONTAL
        MoreMenu(
            chrome.popups, anchor,
            listOf(
                MenuEntry(getString(R.string.split_swap)) { controller.swapSides() },
                MenuEntry(getString(if (horizontal) R.string.split_stack else R.string.split_side_by_side)) {
                    controller.setOrientation(if (horizontal) Orientation.VERTICAL else Orientation.HORIZONTAL)
                },
                MenuEntry(getString(if (horizontal) R.string.split_close_right else R.string.split_close_bottom)) { controller.closePane(right = true) },
            ),
        ).show()
    }

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
        items += MenuEntry(getString(R.string.more_settings)) { deps.openSettings(this) }
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

    private fun thumbnails(s: PaneSession): ThumbnailStore = s.thumbs ?: ThumbnailStore(
        s.handle.editor,
        ThumbnailRenderer(BackgroundRendererSet(), InkRenderer(GeometryCache()), deps.dispatchers.render),
        deps.thumbsDir?.invoke(s.handle.id), s.scope, deps.dispatchers.io, deps.logger,
    ).also { s.thumbs = it }

    private fun openPageGrid() {
        val session = controller.current ?: return
        val h = session.handle
        val actions = session.pageActions
        if (gridOverlay != null) return
        chrome.popups.dismiss()
        val overlay = PageGridOverlay(
            this, h.editor, thumbnails(session), actions, chrome.popups,
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
        if (splitLayout.hitsDivider(ev.x, ev.y)) return true
        val loc = IntArray(2)
        for (s in controller.displayed) {
            val f = s.frame
            if (f != null && f.split && f.hitsBar(ev.x, ev.y)) return true
            if (s.pane.hitsOwnUi(ev.x, ev.y)) return true
            val v = s.indicator ?: continue
            v.getLocationInWindow(loc)
            if (ev.x >= loc[0] && ev.x < loc[0] + v.width && ev.y >= loc[1] && ev.y < loc[1] + v.height) return true
        }
        return false
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
        val panes = controller.displayed.map { it.pane as app.notate.core.input.PaneTarget }
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
        if (pane == null) return super.dispatchGenericMotionEvent(ev)
        val panes = controller.displayed.map { it.pane as app.notate.core.input.PaneTarget }
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
        controller.persistViews()
        deps.appScope.launch { deps.registry.flushAll() }
    }

    override fun onDestroy() {
        ticking = false
        main.removeCallbacksAndMessages(null)
        gridOverlay?.release()
        gridOverlay = null
        // Written on the app scope because this window's scope ends with it; the last state must still reach the disk.
        controller.shutdown()
        val last = controller.workspace.value
        if (ready.isCompleted) workspaceStore?.let { store -> deps.appScope.launch { store.save(last) } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EditorActivity"
        private const val EXTRA_DOC_ID = "doc_id"
        private const val TICK_MS = 50L
        private const val REQ_IMAGE = 71
        private const val OPEN_AT_DELAY_MS = 350L
        private const val RECENT_FINGERS = 5

        /** Opens [id] in a tab of the editor window (starting the window if needed). */
        fun intent(context: Context, id: DocId): Intent =
            Intent(context, EditorActivity::class.java).putExtra(EXTRA_DOC_ID, id.value)

        /** Brings the editor window back with the tabs it had. */
        fun continueIntent(context: Context): Intent = Intent(context, EditorActivity::class.java)
    }
}
