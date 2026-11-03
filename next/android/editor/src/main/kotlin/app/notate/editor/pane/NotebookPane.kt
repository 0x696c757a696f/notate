package app.notate.editor.pane

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import app.notate.core.doc.content.PageContent
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.PageTurn
import app.notate.core.doc.layout.Viewport
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.doc.select.PageGeometry
import app.notate.core.doc.select.SelectionController
import app.notate.core.doc.settings.Tap2Action
import app.notate.core.doc.settings.Tap3Action
import app.notate.core.doc.tools.GridSnapper
import app.notate.core.doc.tools.PenSession
import app.notate.core.doc.tools.ShapeDragSession
import app.notate.core.doc.tools.ToolContext
import app.notate.core.doc.tools.ToolSession
import app.notate.core.doc.tools.ToolSessionFactory
import app.notate.core.doc.zoom.ZoomWindowState
import app.notate.core.geometry.Affine
import app.notate.core.geometry.Vec2
import app.notate.core.model.Background
import app.notate.core.model.PaperPattern
import app.notate.editor.zoom.ZoomWindowView
import app.notate.core.doc.tools.ToolSettings
import app.notate.core.geometry.Rect
import app.notate.core.ink.driver.DriverInkPolicy
import app.notate.core.input.BlockReason
import app.notate.core.input.EpdMode
import app.notate.core.input.EpdTiming
import app.notate.core.input.FingerGesture
import app.notate.core.input.InputPenMode
import app.notate.core.input.PaneId
import app.notate.core.input.PenEvent
import app.notate.core.model.Capabilities
import app.notate.core.model.Capability
import app.notate.core.model.DocKind
import app.notate.core.model.InkPoint
import app.notate.core.model.InkStyle
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PageLayoutMode
import app.notate.core.model.edit.Edit
import app.notate.core.model.PenType
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import app.notate.core.model.ViewState
import app.notate.editor.EditorDeps
import app.notate.editor.R
import app.notate.editor.guide.GuideController
import app.notate.editor.pages.PageActions
import app.notate.editor.select.SelectionLayer
import app.notate.editor.select.SelectionUi
import app.notate.editor.view.LiveInkLayer
import app.notate.editor.view.PageCanvasView
import app.notate.platform.ink.ConfigurableDriverPort
import app.notate.platform.ink.NoDriverPort
import app.notate.platform.ink.OnyxDriverPort
import app.notate.platform.ink.RawPenSink
import app.notate.platform.ink.RawPoint
import app.notate.render.ink.GeometryCache
import app.notate.render.image.AssetFiles
import app.notate.render.image.ImageCache
import app.notate.render.image.StaticLayoutMetrics
import app.notate.render.ink.InkRenderer
import app.notate.render.paper.BackgroundRendererSet
import app.notate.render.tile.TileCache
import app.notate.render.tile.TileGeometry
import app.notate.render.tile.TileRenderer
import app.notate.render.tile.TileScheduler
import app.notate.render.tile.TileSource
import app.notate.render.view.TileCompositor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Notebook page view: pages in one scrolling column, finger pan/zoom, pen writing and erasing through the tool sessions.
 * Pen points come from the e-ink driver when it is active ([RawPenSink]) or from touch events otherwise. Main thread only.
 */
class NotebookPane(
    context: Context,
    override val handle: DocumentHandle,
    private val deps: EditorDeps,
    private val host: PaneHost,
    private val scope: CoroutineScope,
    override val id: PaneId = PaneId("main"),
) : DocumentPane, RawPenSink {
    private val editor = handle.editor
    private val density = context.resources.displayMetrics.density
    private val pxPerPt = deps.device.xdpi / 72f
    private val main = Handler(Looper.getMainLooper())
    private val factory = ToolSessionFactory()

    private val canvasView = PageCanvasView(context)
    override val view: View get() = canvasView
    override val capabilities: Set<Capability> = Capabilities.forKind(handle.editor.meta.value.kind)
    private val live = LiveInkLayer(canvasView, density) { editor.content(it) }

    /** Page offsets on the document plane for cross-page selection moves. */
    private val geometry = object : PageGeometry {
        override fun origin(page: PageId): Vec2 = layout.frameOf(page).let { Vec2(it.l, it.t) }
        override fun pageAt(docX: Float, docY: Float): PageId? = layout.pageAt(docX, docY)
    }
    private val selectionUi = SelectionUi(
        canvasView, editor, SelectionController(editor, geometry, live), deps.clipboard, { host.popups }, scope, { viewport }, { layout }, ::askLink,
    )
    private val selection: SelectionController get() = selectionUi.controller
    private var baseKind: ToolKind = deps.toolController.baseState().active
    private var hadSelection = false

    private val images = ImageCache(AssetFiles { handle.assetFile(it) }, onLoaded = { a -> main.post { onImageLoaded(a) } })
    private val cache = TileCache(TileCache.defaultMaxBytes((context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass))
    private val rasterizer = TileRenderer(BackgroundRendererSet(), InkRenderer(GeometryCache(), images))
    private val tileSource = object : TileSource {
        override fun pageInfo(page: PageId): PageInfo? = editor.pages.value.firstOrNull { it.id == page }
        override fun content(page: PageId): PageContent? = editor.content(page)
    }
    private val scheduler = TileScheduler(cache, rasterizer, tileSource, scope, deps.dispatchers.render, { onTileReady() })

    // The zoom writing window renders its own tiles (another zoom level) into the same cache, with its own scheduler.
    private val zoomScheduler = TileScheduler(cache, rasterizer, tileSource, scope, deps.dispatchers.render, { onTileReady() })
    private val zoomView = ZoomWindowView(context, TileCompositor(cache, density) { p -> editor.pages.value.firstOrNull { it.id == p } }, live)
    private val whiteboardReason = context.getString(R.string.unavailable_whiteboard)

    // "Low-latency pen" off (Settings): no driver at all, the pen arrives as touch events; takes effect when the document is reopened.
    override val driver: ConfigurableDriverPort =
        if (deps.device.hasRawDrawing && deps.settings.driverInk.value) OnyxDriverPort(canvasView.penSurface, this, deps.device) else NoDriverPort
    override val closesWhenNotOwner: Boolean = true
    override val penMode: InputPenMode = InputPenMode.WRITE
    override val takesConstraintFinger: Boolean get() = deps.toolController.baseState().active == ToolKind.SHAPE

    override fun onConstraint(on: Boolean) {
        val ctx = activeCtx ?: return
        if (ctx.constraint == on) return
        ctx.constraint = on
        (session as? ShapeDragSession)?.reapplyConstraint()
    }

    override var layoutMode: PageLayoutMode = editor.meta.value.view?.layout ?: deps.settings.pageLayout.value
        private set
    private val pageActions = PageActions(context, editor, scope, deps.logger)
    private val objects = ObjectsController(
        context, handle, scope, deps, host, this, canvasView,
        viewport = { viewport }, frameOf = { layout.frameOf(it) },
        pointAt = { x, y -> PaneCoords.toPage(viewport, layout, x, y) },
        currentPage = { currentPage }, goToPage = { goToPage(it) },
        refresh = { deps.epd.refresh(canvasView, EpdMode.GU, null, EpdTiming.AFTER_DRAW) },
    )
    private val guides = GuideController(
        density, deps.device.xdpi,
        viewport = { viewport }, layout = { layout }, currentPage = { currentPage },
        invalidate = { canvasView.invalidate() },
        onActiveChanged = { updateHandMode() },
        onDrag = { start -> if (start) beginFinger() else scheduleEndFinger() },
    )
    override val guideKind: app.notate.core.ink.guide.GuideKind? get() = guides.kind

    override fun setGuide(kind: app.notate.core.ink.guide.GuideKind?) {
        if (kind != null && Capability.RULER !in capabilities) return
        guides.show(kind)
    }

    private var addCard = false
    private var pageTurns = 0
    private var fullRefreshAfterTurn = false
    private var panDx = 0f
    private var panDy = 0f
    private var edgeBack = false
    private var edgeForward = false
    private var notifiedIndex = -1
    private var notifiedCount = -1

    var viewport: Viewport = Viewport(0f, 0f, 1f, pxPerPt, 0, 0)
        private set
    var layout: DocumentLayout = buildLayout()
        private set

    private var placed = false
    private var initialView: ViewState? = null
    private var attached = false
    private var released = false
    private var inkSuspended = false
    private val loadingPages = HashSet<PageId>()
    private val loc = IntArray(2)

    // --- debug / perf counters ---
    var lastUpToModelMs = -1L
        private set
    var lastUpToDiskMs = -1L
        private set
    private var lastUpAt = 0L

    // --- pen state ---
    private var session: ToolSession? = null
    private var sessionViaDriver = false
    private var rawBegins = 0
    private var penNote = "-"

    /** Last reason a pen-down did or did not start a stroke (debug HUD + log), so "nothing happens" can be diagnosed. */
    private fun note(text: String) {
        penNote = text
        deps.logger.i(TAG, text)
    }
    private var touchDowns = 0
    private var strokeViewport: Viewport = viewport
    private var strokeFrame: Rect = Rect.EMPTY
    private var strokePage: PageId? = null
    private var tempEraser = false
    private var lastLocalX = 0f
    private var lastLocalY = 0f
    private var lastPoint: InkPoint? = null
    private var activeCtx: ToolContext? = null
    private var strokeMap: Affine? = null           // screen → page for a stroke written in the zoom window
    private var zoomBounds: Rect = Rect.EMPTY       // page-coordinate bounds of that stroke
    private var zoomAdvance = false
    private var adjustSoftware = false
    private var strokeBounds: android.graphics.Rect? = null
    private val pollRunnable = object : Runnable {
        override fun run() {
            val s = session ?: return
            (s as? PenSession)?.let {
                it.poll()
                if (it.isAdjusting && sessionViaDriver) enterAdjust()
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    // --- refresh state ---
    private var refreshRect: android.graphics.Rect? = null
    private var refreshFirstAt = 0L
    private var refreshAfterDriver = false
    private val refreshRunnable = Runnable { tryRefresh() }
    private var fingerBusy = false
    private val endFingerRunnable = Runnable { endFinger() }
    private val requestTilesRunnable = Runnable { requestTiles() }
    private val overlayCheck = Runnable { checkOverlay() }
    private var overlayPage: PageId? = null
    private var overlayRect: Rect = Rect.EMPTY
    private var overlayDeadline = 0L

    init {
        canvasView.compositor = TileCompositor(cache, density) { p -> editor.pages.value.firstOrNull { it.id == p } }
        canvasView.live = live
        canvasView.zoom = zoomView
        live.projectionProvider = { zoomView.projection() }
        canvasView.overlay = { selectionUi.draw(it); guides.draw(it) }
        live.viewportProvider = { viewport }
        live.layoutProvider = { layout }
        canvasView.sizeListener = ::onViewSized
        canvasView.penSurface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) { if (attached && !released) host.arbiter.reopen(this@NotebookPane) }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {
                if (attached && !released) host.arbiter.reopen(this@NotebookPane)
            }
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { if (attached && !released) host.arbiter.closeSession(this@NotebookPane) }
        })
        if (Capability.PAGES in capabilities && editor.meta.value.kind != DocKind.WEB) {
            canvasView.addButtonLabel = context.getString(R.string.add_page_button)
            canvasView.addCardHint = context.getString(R.string.add_page_hint)
        }
        scope.launch {
            editor.changes.collect { c ->
                scheduler.invalidate(c.page, c.dirty)
                if (zoomView.isOpen) zoomScheduler.invalidate(c.page, c.dirty)
                canvasView.invalidate()
                selection.refresh()     // undo / redo may have removed the selected items
            }
        }
        scope.launch { selection.selection.collect { onSelectionChanged() } }
        scope.launch {
            editor.pages.collect { onPagesChanged() }
        }
        scope.launch { deps.toolController.state.collect { onToolChanged(it.preset()) } }
    }

    private fun buildLayout() = DocumentLayout(editor.pages.value, layoutMode)

    private var gridPage: PageId? = null

    private fun reportGrid() {
        val idx = layout.nearestIndex(viewport.scrollX + viewport.widthPx / viewport.scale / 2f, viewport.scrollY + viewport.heightPx / viewport.scale / 2f)
        if (idx < 0) return
        val id = layout.pageIdAt(idx)
        if (id == gridPage) return
        gridPage = id
        val bg = editor.pages.value.firstOrNull { it.id == id }?.background
        host.onPageGrid(GridSnapper(bg).available)
    }

    private fun publish() {
        reportGrid()
        zoomView.viewport = viewport
        zoomView.layout = layout
        syncZoomSize()
        canvasView.viewport = viewport
        canvasView.layout = layout
        canvasView.addCard = addCard
        canvasView.invalidate()
        if (selection.selection.value != null) selectionUi.update()    // bar follows scrolling and zooming
        notifyPage()
    }

    private fun currentIndex(): Int {
        val v = viewport
        if (layout.pageCount == 0 || v.widthPx == 0) return -1
        return layout.nearestIndex(v.scrollX + v.widthPx / v.scale / 2f, v.scrollY + v.heightPx / v.scale / 2f)
    }

    private fun notifyPage() {
        val i = currentIndex()
        if (i == notifiedIndex && layout.pageCount == notifiedCount) return
        notifiedIndex = i
        notifiedCount = layout.pageCount
        if (i >= 0) host.onPageChanged(i, layout.pageCount)
    }

    /** Pans by a finger drag, leaving room below the last page for the "Add page" button. */
    private fun panned(dxPx: Float, dyPx: Float, vp: Viewport = viewport): Viewport {
        val extra = if (canvasView.addButtonLabel != null && layoutMode == PageLayoutMode.CONTINUOUS) {
            (PageCanvasView.BUTTON_GAP_DP + PageCanvasView.BUTTON_HEIGHT_DP) * density / vp.scale
        } else {
            0f
        }
        // The zoom window covers the bottom third: the last lines of a page must be scrollable above it.
        val zoomExtra = if (zoomView.isOpen) zoomView.region.height() / vp.scale else 0f
        return vp.panBy(dxPx, dyPx, layout, extra + zoomExtra)
    }

    /** The page list changed (insert, delete, move, template): rebuild the layout and keep the page in view where it was. */
    private fun onPagesChanged() {
        val old = layout
        val vp = viewport
        val idx = currentIndex()
        val anchor = if (idx >= 0) old.pageIdAt(idx) else null
        val oldFrame = if (idx >= 0) old.frameAt(idx) else null
        layout = buildLayout()
        if (anchor != null && oldFrame != null) {
            if (layout.indexOf(anchor) >= 0) {
                val f = layout.frameOf(anchor)
                viewport = vp.copy(scrollX = f.l + (vp.scrollX - oldFrame.l), scrollY = f.t + (vp.scrollY - oldFrame.t))
            } else if (layout.pageCount > 0) {
                viewport = vp.fitWidth(layout, layout.pageIdAt(idx.coerceAtMost(layout.pageCount - 1)), FIT_MARGIN_DP * density)
            }
        }
        if (addCard && layout.pageCount != notifiedCount) addCard = false
        publish()
        requestTiles()
        refollowZoomPage()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Size, viewport, tiles
    // ---------------------------------------------------------------------------------------------------------

    private fun onViewSized(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || released) return
        var vp = viewport.copy(widthPx = w, heightPx = h)
        if (!placed && layout.pageCount > 0) {
            placed = true
            val saved = initialView ?: editor.meta.value.view
            vp = if (saved != null && layout.indexOf(saved.page) >= 0) {
                val f = layout.frameOf(saved.page)
                vp.copy(zoom = saved.zoom.coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM), scrollX = f.l + saved.scrollXPt, scrollY = f.t + saved.scrollYPt)
            } else {
                vp.fitWidth(layout, layout.pageIdAt(0), FIT_MARGIN_DP * density)
            }
        }
        viewport = vp
        zoomView.dock(w, h)
        publish()
        requestTiles()
        applyDriverLimits()
        updateDriverInk()
        if (!attached) {
            attached = true
            host.arbiter.attach(this)
            host.claimPen(this)
            updateHandMode()
            // The first config is sent while the window is still laying out; the Onyx service drops it. Re-sending
            // once the view is drawn is what switching "low-latency pen" off and on does by hand.
            for (delay in REAPPLY_DRIVER_MS) main.postDelayed({ reapplyDriver() }, delay)
        }
    }

    private fun reapplyDriver() {
        if (released || viewport.widthPx == 0) return
        updateDriverInk()
    }

    private fun setViewport(vp: Viewport, delayTiles: Boolean) {
        viewport = vp
        objects.onViewportChanged()
        publish()
        if (delayTiles) {
            main.removeCallbacks(requestTilesRunnable)
            main.postDelayed(requestTilesRunnable, ZOOM_RENDER_DELAY_MS)
        } else {
            requestTiles()
        }
    }

    private fun requestTiles() {
        if (released || viewport.widthPx == 0) return
        ensureVisibleLoaded()
        scheduler.request(viewport, layout)
        zoomView.zoomViewport()?.let { zoomScheduler.request(it, layout) }
    }

    private fun ensureVisibleLoaded() {
        val n = layout.pageCount
        if (n == 0) return
        val vis = viewport.visibleDocRect()
        var idx = layout.visiblePages(vis).map { layout.indexOf(it) }
        if (idx.isEmpty()) idx = listOf(layout.nearestIndex((vis.l + vis.r) / 2f, (vis.t + vis.b) / 2f))
        val lo = (idx.min() - 1).coerceAtLeast(0)
        val hi = (idx.max() + 1).coerceAtMost(n - 1)
        for (i in lo..hi) {
            val p = layout.pageIdAt(i)
            val info = editor.pages.value.firstOrNull { it.id == p } ?: continue
            val unbounded = info.height == null
            if ((editor.content(p) != null && !unbounded) || !loadingPages.add(p)) continue
            val region = if (unbounded) layout.frameOf(p).let { f -> vis.offset(-f.l, -f.t).inset(-TileGeometry.TILE_PX.toFloat()) } else null
            scope.launch {
                try {
                    editor.ensureLoaded(p, region)
                } catch (e: Exception) {
                    deps.logger.e(TAG, "Loading page $p failed", e)
                } finally {
                    loadingPages.remove(p)
                }
                if (!released) {
                    scheduler.request(viewport, layout)
                    canvasView.invalidate()
                }
            }
        }
        for (i in 0 until n) if (i < lo - EVICT_DISTANCE || i > hi + EVICT_DISTANCE) editor.evict(layout.pageIdAt(i))
    }

    private fun onTileReady() {
        canvasView.invalidate()
        checkOverlay()
        checkZoomRefresh(force = false)
    }

    /** Fraction of the visible tiles that are fresh, and tiles in flight (debug HUD). */
    fun tileStats(): Pair<Float, Int> {
        val level = Viewport.levelFor(viewport.scale)
        val vis = viewport.visibleDocRect()
        var total = 0
        var fresh = 0
        for (p in layout.visiblePages(vis)) {
            val info = editor.pages.value.firstOrNull { it.id == p } ?: continue
            val f = layout.frameOf(p)
            val bounds = info.height?.let { Rect(0f, 0f, info.width, it) }
            for (k in TileGeometry.cover(p, level, vis.offset(-f.l, -f.t), bounds)) {
                total++
                if (cache.isFresh(k)) fresh++
            }
        }
        return (if (total == 0) 1f else fresh.toFloat() / total) to scheduler.inFlight
    }

    // ---------------------------------------------------------------------------------------------------------
    // PaneTarget
    // ---------------------------------------------------------------------------------------------------------

    override fun hitTest(x: Float, y: Float): Boolean {
        canvasView.getLocationInWindow(loc)
        return x >= loc[0] && y >= loc[1] && x < loc[0] + canvasView.width && y < loc[1] + canvasView.height
    }

    private fun localX(x: Float): Float { canvasView.getLocationInWindow(loc); return x - loc[0] }
    private fun localY(y: Float): Float { canvasView.getLocationInWindow(loc); return y - loc[1] }

    override fun onFingerGesture(g: FingerGesture) {
        if (released) return
        // The pen owns the page while it is down: pans, pinches and taps are ignored (ends still arrive).
        if (session != null && (g is FingerGesture.Pan || g is FingerGesture.Pinch || g is FingerGesture.Tap ||
                g is FingerGesture.PanStart || g is FingerGesture.PinchStart)
        ) return
        if (guides.onGesture(g, ::localX, ::localY)) return
        when (g) {
            is FingerGesture.PanStart -> {
                panDx = 0f
                panDy = 0f
                edgeBack = addCard || PageTurn.atEdge(viewport, layout, -1)
                edgeForward = addCard || PageTurn.atEdge(viewport, layout, 1)
                zoomGesture = zoomGestureAt(localX(g.x), localY(g.y))
                if (zoomGesture == ZoomGesture.BOX) noteBoxRefresh()
                beginFinger()
            }
            is FingerGesture.PinchStart -> {
                zoomGesture = if (zoomView.contains(localX(g.fx), localY(g.fy))) ZoomGesture.SWALLOW else ZoomGesture.NONE
                beginFinger()
            }
            is FingerGesture.Pan -> when (zoomGesture) {
                ZoomGesture.BOX -> dragZoomBox(g.dx, g.dy)
                ZoomGesture.SWALLOW -> Unit
                ZoomGesture.NONE -> {
                    panDx += g.dx
                    panDy += g.dy
                    if (!addCard) setViewport(panned(g.dx, g.dy), delayTiles = false)
                }
            }
            is FingerGesture.PanEnd -> when (zoomGesture) {
                ZoomGesture.BOX -> { zoomGesture = ZoomGesture.NONE; noteBoxRefresh(); onZoomMoved(); scheduleEndFinger() }
                ZoomGesture.SWALLOW -> { zoomGesture = ZoomGesture.NONE; scheduleEndFinger() }
                ZoomGesture.NONE -> if (!(layoutMode == PageLayoutMode.SINGLE && swipeEnded(g.vx))) scheduleEndFinger()
            }
            FingerGesture.PinchEnd, FingerGesture.Cancel -> { zoomGesture = ZoomGesture.NONE; scheduleEndFinger() }
            is FingerGesture.Pinch -> if (zoomGesture == ZoomGesture.NONE) {
                val fx = localX(g.fx)
                val fy = localY(g.fy)
                setViewport(panned(g.dx, g.dy, viewport.zoomAround(g.scale, fx, fy)), delayTiles = true)
            }
            is FingerGesture.Tap -> onTap(g)
            is FingerGesture.LongPress -> if (!objects.onLongPress(localX(g.x), localY(g.y))) onLongPress(g)
        }
    }

    private fun onTap(g: FingerGesture.Tap) {
        when (g.fingers) {
            1 -> {
                val x = localX(g.x)
                val y = localY(g.y)
                if (zoomView.contains(x, y)) {
                    zoomView.controlAt(x, y)?.let(::onZoomControl)
                    return
                }
                if (objects.editingText) { objects.finishTextEdit(); return }
                if (objects.onTap(x, y)) return
                if (selection.selection.value != null) selection.clear()
                if (canvasView.addCardRect()?.contains(x, y) == true || canvasView.addButtonRect()?.contains(x, y) == true) addPageAtEnd()
            }
            2 -> when (deps.settings.tap2.value) {
                Tap2Action.UNDO -> editor.undo()
                Tap2Action.REDO -> editor.redo()
                Tap2Action.OFF -> Unit
            }
            3 -> when (deps.settings.tap3.value) {
                Tap3Action.REDO -> editor.redo()
                Tap3Action.REFRESH -> deps.epd.refresh(canvasView, EpdMode.GC, null, EpdTiming.AFTER_DRAW)
                Tap3Action.FOCUS_MODE -> host.onFocusModeRequested()
                Tap3Action.OFF -> Unit
            }
        }
    }

    private fun beginFinger() {
        main.removeCallbacks(endFingerRunnable)
        if (fingerBusy) return
        fingerBusy = true
        host.arbiter.block(this, BlockReason.FINGER_GESTURE, true)
        deps.epd.acquireFast(FAST_OWNER)
    }

    private fun scheduleEndFinger(settleMs: Long = FINGER_SETTLE_MS) {
        main.removeCallbacks(endFingerRunnable)
        main.postDelayed(endFingerRunnable, settleMs)
        main.removeCallbacks(requestTilesRunnable)
        main.postDelayed(requestTilesRunnable, ZOOM_RENDER_DELAY_MS)
    }

    private fun endFinger() {
        if (!fingerBusy) return
        fingerBusy = false
        deps.epd.releaseFast(FAST_OWNER)
        host.arbiter.block(this, BlockReason.FINGER_GESTURE, false)
        updateDriverInk()
        requestTiles()
        val full = fullRefreshAfterTurn
        fullRefreshAfterTurn = false
        deps.epd.refresh(canvasView, if (full) EpdMode.GC else EpdMode.GU, null, EpdTiming.AFTER_DRAW)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pen: touch-event path
    // ---------------------------------------------------------------------------------------------------------

    /** The e-ink driver really inks (a missing driver always leaves the pen to the touch-event path). */
    private val driverActive: Boolean get() = driver !== NoDriverPort && host.arbiter.isDriverActive(this)

    override fun onPen(ev: PenEvent) {
        if (released) return
        if (ev is PenEvent.Hover) {
            onHover(ev)
            return
        }
        if (handMode) {
            if (ev is PenEvent.Down) note("hand tool: pen pans")
            panWithPen(ev)
            return
        }
        // The driver reports its own points; touch events only feed palm rejection. Two exceptions: a stroke that began from touch
        // events goes on that way, and a pen that came down on an unfocused pane has opened the driver just now, too late for this stroke.
        val touchStroke = session != null && !sessionViaDriver
        // A driver that has never delivered a stroke is not trusted: until it does, touch events ink (nothing may swallow a pen).
        if (ev is PenEvent.Down) {
            touchDowns++
            if (touchDowns <= LOG_FIRST) deps.logger.i(TAG, "pen down (touch) driverActive=$driverActive rawBegins=$rawBegins")
        }
        if (!app.notate.core.input.PenRouting.inkFromTouch(driverActive && rawBegins > 0, touchStroke, ev)) return
        val x = localX(ev.x)
        val y = localY(ev.y)
        when (ev) {
            is PenEvent.Down -> beginPen(x, y, ev.pressure, ev.tiltX, ev.tiltY, ev.tMs, ev.eraser, viaDriver = false, lasso = ev.lasso)
            is PenEvent.Move -> movePen(listOf(InkPoint(0f, 0f, ev.pressure, ev.tiltX, ev.tiltY, ev.tMs)), x, y)
            is PenEvent.Up -> endPen(x, y, ev.tMs, 0f)
            is PenEvent.Cancel -> cancelPen()
            is PenEvent.Hover -> Unit
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pen: driver path
    // ---------------------------------------------------------------------------------------------------------

    override fun onRawBegin(p: RawPoint, eraser: Boolean) {
        rawBegins++
        if (rawBegins <= LOG_FIRST) deps.logger.i(TAG, "pen down (driver) eraser=$eraser")
        if (session != null && !sessionViaDriver) return     // the touch events are already inking this stroke
        host.onDriverStroke(true, SystemClock.uptimeMillis())
        beginPen(p.x, p.y, p.pressure, p.tiltX, p.tiltY, p.tMs, eraser, viaDriver = true)
    }

    override fun onRawMove(batch: List<RawPoint>) {
        if (session == null || !sessionViaDriver || batch.isEmpty()) return
        val last = batch.last()
        movePen(batch.map { InkPoint(0f, 0f, it.pressure, it.tiltX, it.tiltY, it.tMs) }, last.x, last.y, batch.map { it.x to it.y })
    }

    override fun onRawEnd(p: RawPoint, eraser: Boolean) {
        if (session != null && !sessionViaDriver) return
        endPen(p.x, p.y, p.tMs, p.pressure)
        host.onDriverStroke(false, SystemClock.uptimeMillis())
    }

    override fun onInkSuspended(suspended: Boolean) {
        inkSuspended = suspended
        if (suspended && session != null && !sessionViaDriver) cancelPen()
    }

    override fun onDriverDeactivated() {
        // The driver was switched off mid-stroke and will never report its end: keep what was written.
        if (session != null && sessionViaDriver) endPen(lastLocalX, lastLocalY, lastPoint?.tMs ?: 0L, 0f)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pen: common
    // ---------------------------------------------------------------------------------------------------------

    private fun beginPen(x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float, tMs: Long, eraser: Boolean, viaDriver: Boolean, lasso: Boolean = false) {
        if (session != null) cancelPen()
        if (objects.editingText) objects.finishTextEdit()      // the pen came down outside the text editor: that ends the edit
        if (inkSuspended) {
            note("ignored: ink suspended by ${host.arbiter.let { a -> BlockReason.entries.filter { a.isBlockedBy(it) || a.isBlockedBy(this, it) } }}")
            return
        }
        if (fingerBusy && !viaDriver) {
            main.removeCallbacks(endFingerRunnable)
            endFinger()
        }
        if (eraser) {
            tempEraser = true
            deps.toolController.temporaryEraser(true)
        } else if (lasso) {
            tempEraser = true
            deps.toolController.temporaryTool(ToolKind.LASSO)
        }
        val vp = viewport
        // With the zoom window open the pen writes into its writing area only, and points map through the box.
        val zmap = zoomView.pointMap()
        val hit = if (zmap == null) {
            PaneCoords.toPage(vp, layout, x, y)
        } else if (zoomView.writing.contains(x, y)) {
            zoomView.state?.let { st -> zmap.map(x, y).let { PagePoint(st.page, it.x, it.y) } }
        } else {
            null
        }
        val content = hit?.let { editor.content(it.page) }
        val info = hit?.let { h -> editor.pages.value.firstOrNull { it.id == h.page } }
        if (hit == null || content == null || info == null) {
            note("ignored: page=${hit?.page?.value} loaded=${content != null} xy=${x.toInt()},${y.toInt()} view=${vp.widthPx}x${vp.heightPx}")
            restoreEraser()
            return
        }
        note("stroke started (${if (viaDriver) "driver" else "touch"})")
        strokeViewport = vp
        strokeFrame = layout.frameOf(hit.page)
        strokePage = hit.page
        strokeMap = zmap
        zoomBounds = Rect(hit.x, hit.y, hit.x, hit.y)
        zoomAdvance = zmap != null && deps.toolController.state.value.preset().kind.let { it == ToolKind.PEN || it == ToolKind.HIGHLIGHTER }
        sessionViaDriver = viaDriver
        var preset = deps.toolController.state.value.preset()
        // A pen-down on the selection's box or a handle drags the selection whatever the tool is; elsewhere it ends the selection.
        if (selection.selection.value != null) {
            if (zmap == null && selectionUi.hits(hit.page, x, y)) {
                preset = ToolPreset(ToolKind.LASSO, lassoMode = deps.toolController.baseState().lassoMode)
            } else if (preset.kind != ToolKind.LASSO) {
                selection.clear()
            }
        }
        val holdMs = deps.settings.holdShapeMs.value.takeIf { it > 0 }?.toLong()
        val ctx = ToolContext(
            editor = editor,
            page = hit.page,
            pageBounds = info.height?.let { Rect(0f, 0f, info.width, it) },
            pxPerPt = if (zmap != null) zoomView.scale else vp.scale,
            preview = live,
            settings = ToolSettings(
                holdMs, (preset.eraserRadiusDp ?: 0f) * density,
                handleHitPx = selectionUi.layer.hitHalfPx(), rotateOffsetPx = SelectionLayer.ROTATE_DP * density,
            ),
            clock = deps.clock,
            background = info.background,
            selection = selection,
            textMetrics = StaticLayoutMetrics,
            guides = guides.stateFor(hit.page, vp),
        )
        ctx.onEditText = { id, isNew -> objects.startTextEdit(hit.page, id, isNew) }
        activeCtx = ctx
        live.page = hit.page
        live.unclippedGhost = preset.kind == ToolKind.LASSO
        selStartDirty = selectionDirty()
        live.driverDraws = viaDriver && preset.kind.let { it == ToolKind.PEN || it == ToolKind.HIGHLIGHTER }
        val s = factory.create(preset, ctx)
        session = s
        strokeBounds = null
        growBounds(x, y)
        lastLocalX = x
        lastLocalY = y
        if (!viaDriver) deps.epd.acquireFast(INK_OWNER)
        val p = InkPoint(hit.x, hit.y, pressure, tiltX, tiltY, tMs)
        lastPoint = p
        s.begin(p)
        main.removeCallbacks(pollRunnable)
        main.postDelayed(pollRunnable, POLL_MS)
    }

    private fun movePen(points: List<InkPoint>, lastX: Float, lastY: Float, xs: List<Pair<Float, Float>>? = null) {
        val s = session ?: return
        val out = ArrayList<InkPoint>(points.size)
        for ((i, p) in points.withIndex()) {
            val sx = xs?.get(i)?.first ?: lastX
            val sy = xs?.get(i)?.second ?: lastY
            val pt = strokePoint(sx, sy)
            out.add(InkPoint(pt.x, pt.y, p.pressure, p.tiltX, p.tiltY, p.tMs))
            growZoomBounds(pt)
            growBounds(sx, sy)
        }
        lastLocalX = lastX
        lastLocalY = lastY
        lastPoint = out.last()
        s.move(out)
    }

    /**
     * A held shape replaced the stroke while the e-ink driver was inking it: hand the rest of the gesture to the software
     * preview. The driver is blocked (its ink is cleared by one partial refresh) and the touch events carry the pen from here;
     * the block lifts after pen-up.
     */
    private fun enterAdjust() {
        sessionViaDriver = false
        adjustSoftware = true
        live.driverDraws = false
        deps.epd.acquireFast(INK_OWNER)
        host.arbiter.block(this, BlockReason.TOOL_NEEDS_SOFTWARE, true)
        strokeBounds?.let { r ->
            val pending = refreshRect
            if (pending == null) refreshRect = android.graphics.Rect(r) else pending.union(r)
            if (refreshFirstAt == 0L) refreshFirstAt = SystemClock.uptimeMillis()
            armRefresh(0L)
        }
    }

    private fun leaveAdjust() {
        if (!adjustSoftware) return
        adjustSoftware = false
        updateHandMode()
    }

    private fun endPen(x: Float, y: Float, tMs: Long, pressure: Float) {
        val s = session ?: return
        session = null
        main.removeCallbacks(pollRunnable)
        growBounds(x, y)
        val pt = strokePoint(x, y)
        growZoomBounds(pt)
        val t0 = SystemClock.uptimeMillis()
        s.end(InkPoint(pt.x, pt.y, pressure, 0f, 0f, tMs))
        lastUpToModelMs = SystemClock.uptimeMillis() - t0
        lastUpAt = t0
        val viaDriver = sessionViaDriver
        if (!viaDriver) deps.epd.releaseFast(INK_OWNER)
        activeCtx?.constraint = false
        restoreEraser()
        val page = strokePage
        val rect = strokeBounds
        if (rect != null) {
            // A moved / scaled selection leaves a ghost at its old and new place: repaint both.
            selStartDirty?.let { rect.union(it) }
            selectionDirty()?.let { rect.union(it) }
        }
        selStartDirty = null
        if (page != null && rect != null) afterCommit(page, rect, viaDriver)
        leaveAdjust()
        if (strokeMap != null) afterZoomStroke()
    }

    private fun cancelPen() {
        val s = session ?: return
        session = null
        main.removeCallbacks(pollRunnable)
        s.cancel()
        live.discardCurrent()
        strokeMap = null
        leaveAdjust()
        if (!sessionViaDriver) deps.epd.releaseFast(INK_OWNER)
        activeCtx?.constraint = false
        restoreEraser()
    }

    private fun restoreEraser() {
        if (tempEraser) {
            tempEraser = false
            deps.toolController.temporaryTool(null)
        }
    }

    private fun growBounds(x: Float, y: Float) {
        val pad = (BOUNDS_PAD_DP * density).toInt()
        val l = floor(x).toInt() - pad
        val t = floor(y).toInt() - pad
        val r = ceil(x).toInt() + pad
        val b = ceil(y).toInt() + pad
        val cur = strokeBounds
        if (cur == null) strokeBounds = android.graphics.Rect(l, t, r, b) else cur.union(l, t, r, b)
    }

    // ---------------------------------------------------------------------------------------------------------
    // After a stroke: keep the overlay until tiles are fresh, then refresh the panel once
    // ---------------------------------------------------------------------------------------------------------

    private fun afterCommit(page: PageId, screenRect: android.graphics.Rect, viaDriver: Boolean) {
        val vp = strokeViewport
        val f = strokeFrame
        val s = vp.scale
        // Screen rect → page rect, to ask whether the tiles under it were re-rendered.
        val pageRect = strokeMap?.mapRect(Rect(screenRect.left.toFloat(), screenRect.top.toFloat(), screenRect.right.toFloat(), screenRect.bottom.toFloat()))
            ?: Rect(
                screenRect.left / s + vp.scrollX - f.l, screenRect.top / s + vp.scrollY - f.t,
                screenRect.right / s + vp.scrollX - f.l, screenRect.bottom / s + vp.scrollY - f.t,
            )
        overlayRect = if (overlayPage == page) overlayRect.union(pageRect) else pageRect
        overlayPage = page
        overlayDeadline = SystemClock.uptimeMillis() + OVERLAY_MAX_MS
        val pending = refreshRect
        if (pending == null) refreshRect = android.graphics.Rect(screenRect) else pending.union(screenRect)
        if (refreshFirstAt == 0L) refreshFirstAt = SystemClock.uptimeMillis()
        refreshAfterDriver = refreshAfterDriver || viaDriver
        main.removeCallbacks(overlayCheck)
        main.post(overlayCheck)
        main.postDelayed(overlayCheck, OVERLAY_MAX_MS)
        if (refreshAfterDriver) armRefresh(HOVER_QUIET_MS) else armRefresh(0L)
    }

    private fun overlayFresh(): Boolean {
        val page = overlayPage ?: return true
        val info = editor.pages.value.firstOrNull { it.id == page } ?: return true
        val bounds = info.height?.let { Rect(0f, 0f, info.width, it) }
        val levels = listOfNotNull(Viewport.levelFor(viewport.scale), zoomView.zoomViewport()?.let { Viewport.levelFor(it.scale) })
        return levels.all { level -> TileGeometry.cover(page, level, overlayRect, bounds).all { cache.isFresh(it) } }
    }

    private fun checkOverlay() {
        if (overlayPage == null) return
        if (overlayFresh() || SystemClock.uptimeMillis() >= overlayDeadline) {
            overlayPage = null
            main.removeCallbacks(overlayCheck)
            live.releaseOverlay()
        }
    }

    private fun armRefresh(delayMs: Long) {
        main.removeCallbacks(refreshRunnable)
        main.postDelayed(refreshRunnable, delayMs)
    }

    private fun tryRefresh() {
        val rect = refreshRect ?: return
        if (session != null) { armRefresh(HOVER_QUIET_MS); return }
        val waited = SystemClock.uptimeMillis() - refreshFirstAt
        if (overlayPage != null && waited < REFRESH_MAX_MS) { armRefresh(POLL_MS); return }
        refreshRect = null
        refreshFirstAt = 0L
        refreshAfterDriver = false
        val box = boxRefresh
        boxRefresh = null
        rect.intersect(0, 0, canvasView.width, canvasView.height)
        if (rect.isEmpty) return
        host.arbiter.block(this, BlockReason.REFRESH, true)
        deps.epd.afterDraw(canvasView) {
            deps.epd.refresh(canvasView, EpdMode.GU, intArrayOf(rect.left, rect.top, rect.right, rect.bottom), EpdTiming.NOW)
            if (box != null && box.intersect(0, 0, canvasView.width, canvasView.height)) {
                deps.epd.refresh(canvasView, EpdMode.GU, intArrayOf(box.left, box.top, box.right, box.bottom), EpdTiming.NOW)
            }
            host.arbiter.block(this, BlockReason.REFRESH, false)
        }
    }

    private fun onHover(ev: PenEvent.Hover) {
        if (refreshRect == null || !refreshAfterDriver) return
        val waited = SystemClock.uptimeMillis() - refreshFirstAt
        when {
            ev.exit -> armRefresh(0L)
            waited < REFRESH_MAX_MS -> armRefresh(HOVER_QUIET_MS)
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tool / driver configuration
    // ---------------------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------------------
    // Selection
    // ---------------------------------------------------------------------------------------------------------

    private var selStartDirty: android.graphics.Rect? = null

    /** Selection action "Link…": choose a target, then add a link over the selection (other documents need the picker: not offered here). */
    private fun askLink(page: PageId, bounds: Rect) {
        LinkDialog(host, handle.id, editor.pages.value.map { it.id }, null, { _, _, done -> done(null) }) { target ->
            objects.createLink(page, bounds, target)
            selection.clear()
        }.show(canvasView.context)
    }

    override fun clearSelection() = selection.clear()

    /** Screen rectangle (view-local px) to repaint for the current selection, or null. */
    private fun selectionDirty(): android.graphics.Rect? =
        selection.selection.value?.let { selectionUi.layer.dirtyRect(it, viewport, layout) }

    private fun onSelectionChanged() {
        if (released) return
        val has = selection.selection.value != null
        val dirty = selectionUi.update()
        if (has != hadSelection) {
            hadSelection = has
            updateHandMode()
        }
        if (dirty != null && session == null) {
            // Nothing is drawing: show the box (or its absence) with one partial refresh.
            dirty.intersect(0, 0, canvasView.width, canvasView.height)
            if (!dirty.isEmpty) {
                val pending = refreshRect
                if (pending == null) refreshRect = android.graphics.Rect(dirty) else pending.union(dirty)
                if (refreshFirstAt == 0L) refreshFirstAt = SystemClock.uptimeMillis()
                armRefresh(0L)
            }
        }
    }

    /** Finger long-press on empty paper opens the Paste menu at that point. */
    private fun onLongPress(g: FingerGesture.LongPress) {
        if (session != null || handMode) return
        val x = localX(g.x)
        val y = localY(g.y)
        val hit = PaneCoords.toPage(viewport, layout, x, y) ?: return
        if (editor.content(hit.page) == null || selectionUi.hits(hit.page, x, y)) return
        selectionUi.showPasteMenu(hit.page, hit.x, hit.y, g.x.toInt(), g.y.toInt())
    }

    override fun onToolChanged(tool: ToolPreset) {
        val kind = deps.toolController.baseState().active
        if (kind != baseKind) {
            baseKind = kind
            selection.clear()
        }
        updateHandMode()
        updateDriverInk()
    }

    override fun refreshExcludes() {
        if (viewport.widthPx == 0) return
        applyDriverLimits()
    }

    /** The pen driver inks in the whole pane, or only in the zoom window's writing area while that is open. */
    private fun applyDriverLimits() {
        val w = zoomView.writing
        val area = if (zoomView.isOpen) {
            android.graphics.Rect(floor(w.left).toInt(), ceil(w.top).toInt(), ceil(w.right).toInt(), ceil(w.bottom).toInt())
        } else {
            android.graphics.Rect(0, 0, viewport.widthPx, viewport.heightPx)
        }
        driver.setLimits(area, host.excludeRects())
    }

    // --- Hand tool: the pen pans instead of writing; the driver stays off so that the pen arrives as touch events ---

    private val handMode: Boolean get() = deps.toolController.baseState().active == ToolKind.HAND

    /** The shape tool draws a software ghost, so the driver stays off for it (like the Hand tool). */
    private val shapeMode: Boolean get() = deps.toolController.baseState().active == ToolKind.SHAPE
    /** The lasso draws a dashed software path and a ghost, so the driver stays off for it as well. */
    private val lassoMode: Boolean get() = deps.toolController.baseState().active == ToolKind.LASSO
    private val textMode: Boolean get() = deps.toolController.baseState().active == ToolKind.TEXT
    private var handing = false
    private var handX = 0f
    private var handY = 0f

    private fun updateHandMode() {
        val on = handMode
        if (!on && handing) endHand()
        host.arbiter.block(this, BlockReason.TOOL_NEEDS_SOFTWARE, on || shapeMode || lassoMode || textMode || adjustSoftware || guides.active || selection.selection.value != null)
    }

    private fun panWithPen(ev: PenEvent) {
        when (ev) {
            is PenEvent.Down -> {
                handing = true
                handX = ev.x
                handY = ev.y
                beginFinger()
            }
            is PenEvent.Move -> if (handing) {
                setViewport(panned(ev.x - handX, ev.y - handY), delayTiles = false)
                handX = ev.x
                handY = ev.y
            }
            is PenEvent.Up, is PenEvent.Cancel -> if (handing) endHand()
            is PenEvent.Hover -> Unit
        }
    }

    private fun endHand() {
        handing = false
        scheduleEndFinger()
    }

    override fun onFocusChanged(focused: Boolean) { if (!focused) selection.clear() }

    private fun updateDriverInk() {
        val preset = deps.toolController.state.value.preset()
        val ink = if (!deps.settings.driverInk.value || viewport.widthPx == 0) {
            null
        } else if (preset.kind == ToolKind.PEN || preset.kind == ToolKind.HIGHLIGHTER) {
            val pen = if (preset.kind == ToolKind.HIGHLIGHTER) PenType.HIGHLIGHTER else preset.pen ?: PenType.BALLPOINT
            DriverInkPolicy.forPen(
                InkStyle(pen, preset.color ?: 0xFF000000.toInt(), preset.widthPt ?: DEFAULT_WIDTH_PT),
                if (zoomView.isOpen) zoomView.scale else viewport.scale,
            )
        } else {
            null    // erasers and other tools: the driver draws nothing
        }
        driver.setInk(ink)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pages: jump, single-page turns, layout mode, "Add page"
    // ---------------------------------------------------------------------------------------------------------

    override val currentPage: PageId?
        get() = currentIndex().takeIf { it >= 0 }?.let { layout.pageIdAt(it) }

    override fun goToPage(page: PageId) {
        if (layout.indexOf(page) < 0) layout = buildLayout()
        if (layout.indexOf(page) < 0 || viewport.widthPx == 0 || released) return
        addCard = false
        val vp = if (layoutMode == PageLayoutMode.SINGLE) {
            viewport.fitWidth(layout, page, FIT_MARGIN_DP * density)
        } else {
            panned(0f, 0f, viewport.copy(scrollY = layout.frameOf(page).t - GO_TO_MARGIN_DP * density / viewport.scale))
        }
        if (layoutMode == PageLayoutMode.SINGLE) countPageTurn()
        beginFinger()
        setViewport(vp, delayTiles = false)
        scheduleEndFinger(PAGE_TURN_SETTLE_MS)
    }

    /** Settings → Full refresh every N page turns (single-page mode): every Nth turn ends with a full refresh. */
    private fun countPageTurn() {
        val n = deps.settings.gcEvery.value
        if (n <= 0) { pageTurns = 0; return }
        pageTurns++
        if (pageTurns >= n) {
            pageTurns = 0
            fullRefreshAfterTurn = true
        }
    }

    override fun setLayoutMode(mode: PageLayoutMode) {
        if (mode == layoutMode || released) return
        val page = currentPage
        layoutMode = mode
        addCard = false
        layout = buildLayout()
        if (page != null && viewport.widthPx > 0) {
            beginFinger()
            setViewport(viewport.fitWidth(layout, page, FIT_MARGIN_DP * density), delayTiles = false)
            scheduleEndFinger(PAGE_TURN_SETTLE_MS)
        } else {
            publish()
        }
        val state = saveViewState() ?: return
        val meta = editor.meta.value
        if (meta.view != state) editor.apply(Edit.SetMeta(meta, meta.copy(view = state)), undoable = false)
    }

    /** A one-finger drag ended in single-page mode: turns the page when it was far or fast enough and started at the page edge. */
    private fun swipeEnded(vx: Float): Boolean {
        val dir = PageTurn.direction(panDx, panDy, vx, viewport.widthPx, density)
        if (dir == 0 || !(if (dir > 0) edgeForward else edgeBack)) return false
        val i = currentIndex()
        if (i < 0) return false
        if (addCard) {
            if (dir < 0) {
                addCard = false
                beginFinger()
                publish()
                scheduleEndFinger(PAGE_TURN_SETTLE_MS)
                return true
            }
            return false
        }
        val target = i + dir
        return when {
            target < 0 -> false
            target >= layout.pageCount -> {
                if (canvasView.addButtonLabel == null) return false
                addCard = true
                beginFinger()
                publish()
                scheduleEndFinger(PAGE_TURN_SETTLE_MS)
                true
            }
            else -> {
                goToPage(layout.pageIdAt(target))
                true
            }
        }
    }

    private fun addPageAtEnd() {
        val last = layout.pageCount.takeIf { it > 0 }?.let { layout.pageIdAt(it - 1) }
        pageActions.addAfter(last) { goToPage(it) }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Zoom writing window (04 §3.10)
    // ---------------------------------------------------------------------------------------------------------

    private enum class ZoomGesture { NONE, BOX, SWALLOW }

    private var zoomGesture = ZoomGesture.NONE
    private var zoomRefreshPending = false
    private var boxRefresh: android.graphics.Rect? = null
    private val zoomRefreshFallback = Runnable { checkZoomRefresh(force = true) }

    override val zoomWindowOpen: Boolean get() = zoomView.isOpen

    /** The zoom window (tests and the debug HUD read its state). */
    internal val zoomWindow: ZoomWindowView get() = zoomView

    override fun zoomUnavailableReason(): String? {
        val info = currentPage?.let { p -> editor.pages.value.firstOrNull { it.id == p } }
        return if (info != null && info.height == null) whiteboardReason else null
    }

    override fun setZoomWindowOpen(open: Boolean): Boolean {
        if (released) return false
        if (!open) {
            if (zoomView.isOpen) closeZoom()
            return true
        }
        if (zoomView.isOpen) return true
        if (Capability.ZOOM_WINDOW !in capabilities || viewport.widthPx == 0 || zoomView.writing.isEmpty) return false
        val page = currentPage ?: return false
        val info = editor.pages.value.firstOrNull { it.id == page } ?: return false
        val height = info.height ?: return false
        val w = zoomView.writing
        val st = ZoomWindowState(page, Rect(0f, 0f, info.width, height), w.width() / viewport.scale, w.width() / w.height(), lineSpacingOf(info))
        val f = layout.frameOf(page)
        val vis = viewport.visibleDocRect()
        st.moveTo(maxOf(vis.l - f.l, 0f) + st.marginPt, maxOf(vis.t - f.t, 0f) + st.marginPt)
        cancelPen()
        zoomView.state = st
        zoomView.viewport = viewport
        zoomView.layout = layout
        if (editor.content(page) == null) {
            scope.launch {
                editor.ensureLoaded(page, null)
                if (!released) requestTiles()
            }
        }
        applyDriverLimits()
        updateDriverInk()
        host.onZoomWindowChanged(true)
        keepBoxVisible()
        queueRegionRefresh()
        onZoomMoved()
        return true
    }

    private fun closeZoom() {
        noteBoxRefresh()
        zoomView.state = null
        zoomScheduler.cancelAll()
        zoomRefreshPending = false
        main.removeCallbacks(zoomRefreshFallback)
        applyDriverLimits()
        updateDriverInk()
        queueRegionRefresh()
        canvasView.invalidate()
        host.onZoomWindowChanged(false)
    }

    private fun lineSpacingOf(info: PageInfo): Float? =
        (info.background as? Background.Paper)?.takeIf { it.pattern == PaperPattern.LINED || it.pattern == PaperPattern.CORNELL }?.spacingPt

    /** The writing area or the pane's zoom changed: the box gets the matching size. */
    private fun syncZoomSize() {
        val st = zoomView.state ?: return
        val w = zoomView.writing
        if (w.isEmpty || viewport.scale <= 0f) return
        st.resize(w.width() / viewport.scale, w.width() / w.height())
    }

    /** The page list changed: the zoom window follows its page (new paper, new size) or closes when the page is gone. */
    private fun refollowZoomPage() {
        val st = zoomView.state ?: return
        val info = editor.pages.value.firstOrNull { it.id == st.page }
        val height = info?.height
        if (info == null || height == null) {
            closeZoom()
            return
        }
        st.updatePage(Rect(0f, 0f, info.width, height), lineSpacingOf(info))
        requestTiles()
    }

    private fun zoomGestureAt(x: Float, y: Float): ZoomGesture {
        if (!zoomView.isOpen) return ZoomGesture.NONE
        if (zoomView.contains(x, y)) return ZoomGesture.SWALLOW
        val box = zoomView.boxOnScreen() ?: return ZoomGesture.NONE
        val slop = BOX_GRAB_DP * density
        return if (box.left - slop <= x && x <= box.right + slop && box.top - slop <= y && y <= box.bottom + slop) ZoomGesture.BOX else ZoomGesture.NONE
    }

    private fun dragZoomBox(dxPx: Float, dyPx: Float) {
        val st = zoomView.state ?: return
        val b = st.box
        st.moveTo(b.l + dxPx / viewport.scale, b.t + dyPx / viewport.scale)
        canvasView.invalidate()
        main.removeCallbacks(requestTilesRunnable)
        main.postDelayed(requestTilesRunnable, ZOOM_RENDER_DELAY_MS)
    }

    private fun onZoomControl(c: ZoomWindowView.Control) {
        val st = zoomView.state ?: return
        noteBoxRefresh()
        when (c) {
            ZoomWindowView.Control.BACK -> st.step(-ZoomWindowState.BUTTON_STEP_FRACTION)
            ZoomWindowView.Control.FORWARD -> st.step(ZoomWindowState.BUTTON_STEP_FRACTION)
            ZoomWindowView.Control.NEW_LINE -> st.newLine()
            ZoomWindowView.Control.SMALLER -> st.setMagnification(st.magnification - ZoomWindowState.MAGNIFICATION_STEP)
            ZoomWindowView.Control.LARGER -> st.setMagnification(st.magnification + ZoomWindowState.MAGNIFICATION_STEP)
            ZoomWindowView.Control.CLOSE -> {
                closeZoom()
                return
            }
        }
        keepBoxVisible()
        onZoomMoved()
        updateDriverInk()
    }

    /** The box moved or changed size: show the page area it covers once its tiles are there, then refresh the panel. */
    private fun onZoomMoved() {
        noteBoxRefresh()
        canvasView.invalidate()
        requestTiles()
        zoomRefreshPending = true
        main.removeCallbacks(zoomRefreshFallback)
        main.postDelayed(zoomRefreshFallback, ZOOM_REFRESH_MAX_MS)
        checkZoomRefresh(force = false)
    }

    private fun zoomTilesFresh(): Boolean {
        val st = zoomView.state ?: return true
        val zv = zoomView.zoomViewport() ?: return true
        val info = editor.pages.value.firstOrNull { it.id == st.page } ?: return true
        val bounds = info.height?.let { Rect(0f, 0f, info.width, it) }
        return TileGeometry.cover(st.page, Viewport.levelFor(zv.scale), st.box, bounds).all { cache.isFresh(it) }
    }

    private fun checkZoomRefresh(force: Boolean) {
        if (!zoomRefreshPending || !zoomView.isOpen) return
        if (!force && !zoomTilesFresh()) return
        zoomRefreshPending = false
        main.removeCallbacks(zoomRefreshFallback)
        queueRegionRefresh()
    }

    private fun queueRegionRefresh() {
        val r = zoomView.region
        val rect = android.graphics.Rect(floor(r.left).toInt(), floor(r.top).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())
        val pending = refreshRect
        if (pending == null) refreshRect = rect else pending.union(rect)
        if (refreshFirstAt == 0L) refreshFirstAt = SystemClock.uptimeMillis()
        armRefresh(0L)
    }

    /** Remembers the dashed box's screen area (old and new position) for the next panel refresh. */
    private fun noteBoxRefresh() {
        val b = zoomView.boxOnScreen() ?: return
        val pad = (BOX_GRAB_DP * density / 2).toInt()
        val r = android.graphics.Rect(floor(b.left).toInt() - pad, floor(b.top).toInt() - pad, ceil(b.right).toInt() + pad, ceil(b.bottom).toInt() + pad)
        val pending = boxRefresh
        if (pending == null) boxRefresh = r else pending.union(r)
    }

    /** Scrolls the page so that the dashed box stays visible above the zoom window. */
    private fun keepBoxVisible() {
        val b = zoomView.boxOnScreen() ?: return
        val m = BOX_MARGIN_DP * density
        val availH = zoomView.region.top
        var dx = 0f
        var dy = 0f
        if (b.height() > availH - 2 * m || b.top < m) dy = m - b.top
        else if (b.bottom > availH - m) dy = availH - m - b.bottom
        if (b.left < m) dx = m - b.left else if (b.right > viewport.widthPx - m) dx = viewport.widthPx - m - b.right
        if (dx != 0f || dy != 0f) setViewport(panned(dx, dy), delayTiles = false)
    }

    private fun strokePoint(sx: Float, sy: Float): Vec2 = strokeMap?.map(sx, sy) ?: PaneCoords.toPageIn(strokeViewport, strokeFrame, sx, sy)

    private fun growZoomBounds(p: Vec2) {
        if (strokeMap == null) return
        val b = zoomBounds
        zoomBounds = Rect(minOf(b.l, p.x), minOf(b.t, p.y), maxOf(b.r, p.x), maxOf(b.b, p.y))
    }

    /** A stroke in the writing area was committed: the page shows it too, and the box may move on. */
    private fun afterZoomStroke() {
        strokeMap = null
        noteBoxRefresh()
        val advance = zoomAdvance
        zoomAdvance = false
        if (advance && zoomView.state?.advanceAfterStroke(zoomBounds) == true) {
            keepBoxVisible()
            onZoomMoved()
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // View state, lifecycle
    // ---------------------------------------------------------------------------------------------------------

    override fun setInitialView(s: ViewState?) { initialView = s }

    override fun saveViewState(): ViewState? {
        if (layout.pageCount == 0 || viewport.widthPx == 0) return null
        val vp = viewport
        val idx = layout.nearestIndex(vp.scrollX + vp.widthPx / vp.scale / 2f, vp.scrollY + vp.heightPx / vp.scale / 2f)
        if (idx < 0) return null
        val f = layout.frameAt(idx)
        return ViewState(layout.pageIdAt(idx), vp.zoom, vp.scrollX - f.l, vp.scrollY - f.t, layout.mode)
    }

    override fun restoreViewState(s: ViewState) {
        if (layout.indexOf(s.page) < 0 || viewport.widthPx == 0) return
        val f = layout.frameOf(s.page)
        setViewport(
            viewport.copy(zoom = s.zoom.coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM), scrollX = f.l + s.scrollXPt, scrollY = f.t + s.scrollYPt),
            delayTiles = false,
        )
    }

    /** Debug lines for the HUD. */
    override fun debugLines(): List<String> {
        val (cov, inFlight) = tileStats()
        val a = host.arbiter
        val reasons = BlockReason.entries.filter { a.isBlockedBy(it) || a.isBlockedBy(this, it) }
        return listOf(
            "owner=${a.isOwner(this)} active=${a.isDriverActive(this)} suspended=${a.isSuspended(this)}",
            "blocked=${reasons.joinToString().ifEmpty { "-" }}",
            "level=${Viewport.levelFor(viewport.scale)} zoom=${"%.2f".format(viewport.zoom)}",
            "tiles fresh=${(cov * 100).toInt()}% inFlight=$inFlight",
            "pen strokes: driver=$rawBegins touch=$touchDowns",
            "last pen: $penNote",
            "driver: ${driver.debugStatus()}",
            "up->model=${lastUpToModelMs}ms up->disk=${lastUpToDiskMs}ms",
        )
    }

    /** Called by the HUD when the save queue went idle after a stroke. */
    override fun onSaveIdle(nowUptime: Long) {
        if (lastUpAt != 0L) {
            lastUpToDiskMs = nowUptime - lastUpAt
            lastUpAt = 0L
        }
    }

    override fun importImage(uri: android.net.Uri) = objects.importImage(uri)

    override fun hitsOwnUi(x: Float, y: Float): Boolean = objects.hitsEditor(x, y)

    /** Adds a link over [frame] of [page] (the selection's "Link…" action, R2-4). */
    fun createLink(page: PageId, frame: Rect, target: app.notate.core.model.LinkTarget) = objects.createLink(page, frame, target)

    /** Marks tiles that show [asset] stale once its bitmap has been decoded. */
    private fun onImageLoaded(asset: app.notate.core.model.AssetId) {
        if (released) return
        for (info in editor.pages.value) {
            val c = editor.content(info.id) ?: continue
            for (item in c.all()) if (item is app.notate.core.model.ImageItem && item.asset == asset) scheduler.invalidate(info.id, item.bounds)
        }
        canvasView.invalidate()
    }

    override fun release() {
        if (released) return
        objects.release()
        cancelPen()
        selectionUi.release()
        released = true
        main.removeCallbacksAndMessages(null)
        deps.epd.releaseFast(FAST_OWNER)
        deps.epd.releaseFast(INK_OWNER)
        scheduler.cancelAll()
        zoomScheduler.cancelAll()
        host.arbiter.detach(this)
        cache.clear()
    }

    private companion object {
        const val TAG = "NotebookPane"
        const val LOG_FIRST = 5
        const val FIT_MARGIN_DP = 12f
        const val ZOOM_RENDER_DELAY_MS = 150L
        val REAPPLY_DRIVER_MS = longArrayOf(400L, 1500L)
        const val FINGER_SETTLE_MS = 700L
        const val POLL_MS = 50L
        const val HOVER_QUIET_MS = 400L
        const val REFRESH_MAX_MS = 1500L
        const val OVERLAY_MAX_MS = 700L
        const val BOUNDS_PAD_DP = 6f
        const val EVICT_DISTANCE = 5
        const val GO_TO_MARGIN_DP = 8f
        const val PAGE_TURN_SETTLE_MS = 250L
        const val DEFAULT_WIDTH_PT = 1.134f
        const val BOX_GRAB_DP = 16f
        const val BOX_MARGIN_DP = 12f
        const val ZOOM_REFRESH_MAX_MS = 800L
        const val FAST_OWNER = "pane-finger"
        const val INK_OWNER = "pane-ink"
    }
}
