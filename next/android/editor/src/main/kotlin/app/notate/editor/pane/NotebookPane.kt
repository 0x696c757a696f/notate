package app.notate.editor.pane

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import app.notate.core.doc.content.PageContent
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.doc.settings.Tap2Action
import app.notate.core.doc.settings.Tap3Action
import app.notate.core.doc.tools.PenSession
import app.notate.core.doc.tools.ToolContext
import app.notate.core.doc.tools.ToolSession
import app.notate.core.doc.tools.ToolSessionFactory
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
import app.notate.core.model.InkPoint
import app.notate.core.model.InkStyle
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PenType
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import app.notate.core.model.ViewState
import app.notate.editor.EditorDeps
import app.notate.editor.view.LiveInkLayer
import app.notate.editor.view.PageCanvasView
import app.notate.platform.ink.ConfigurableDriverPort
import app.notate.platform.ink.NoDriverPort
import app.notate.platform.ink.OnyxDriverPort
import app.notate.platform.ink.RawPenSink
import app.notate.platform.ink.RawPoint
import app.notate.render.ink.GeometryCache
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
    private val live = LiveInkLayer(canvasView, density) { editor.content(it) }

    private val cache = TileCache(TileCache.defaultMaxBytes((context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass))
    private val scheduler = TileScheduler(
        cache = cache,
        rasterizer = TileRenderer(BackgroundRendererSet(), InkRenderer(GeometryCache())),
        source = object : TileSource {
            override fun pageInfo(page: PageId): PageInfo? = editor.pages.value.firstOrNull { it.id == page }
            override fun content(page: PageId): PageContent? = editor.content(page)
        },
        scope = scope,
        renderDispatcher = deps.dispatchers.render,
        onTileReady = { onTileReady() },
    )

    override val driver: ConfigurableDriverPort =
        if (deps.device.hasRawDrawing) OnyxDriverPort(canvasView, this, deps.device) else NoDriverPort
    override val closesWhenNotOwner: Boolean = true
    override val penMode: InputPenMode = InputPenMode.WRITE

    var viewport: Viewport = Viewport(0f, 0f, 1f, pxPerPt, 0, 0)
        private set
    var layout: DocumentLayout = buildLayout()
        private set

    private var placed = false
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
    private var strokeViewport: Viewport = viewport
    private var strokeFrame: Rect = Rect.EMPTY
    private var strokePage: PageId? = null
    private var tempEraser = false
    private var lastLocalX = 0f
    private var lastLocalY = 0f
    private var lastPoint: InkPoint? = null
    private var strokeBounds: android.graphics.Rect? = null
    private val pollRunnable = object : Runnable {
        override fun run() {
            val s = session ?: return
            (s as? PenSession)?.poll()
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
        live.viewportProvider = { viewport }
        live.layoutProvider = { layout }
        canvasView.sizeListener = ::onViewSized
        scope.launch {
            editor.changes.collect { c ->
                scheduler.invalidate(c.page, c.dirty)
                canvasView.invalidate()
            }
        }
        scope.launch {
            editor.pages.collect {
                layout = buildLayout()
                publish()
                requestTiles()
            }
        }
        scope.launch { deps.toolController.state.collect { onToolChanged(it.preset()) } }
        scope.launch { deps.settings.driverInk.collect { updateDriverInk() } }
    }

    private fun buildLayout() = DocumentLayout(editor.pages.value, deps.settings.pageLayout.value)

    private fun publish() {
        canvasView.viewport = viewport
        canvasView.layout = layout
        canvasView.invalidate()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Size, viewport, tiles
    // ---------------------------------------------------------------------------------------------------------

    private fun onViewSized(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || released) return
        var vp = viewport.copy(widthPx = w, heightPx = h)
        if (!placed && layout.pageCount > 0) {
            placed = true
            val saved = editor.meta.value.view
            vp = if (saved != null && layout.indexOf(saved.page) >= 0) {
                val f = layout.frameOf(saved.page)
                vp.copy(zoom = saved.zoom.coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM), scrollX = f.l + saved.scrollXPt, scrollY = f.t + saved.scrollYPt)
            } else {
                vp.fitWidth(layout, layout.pageIdAt(0), FIT_MARGIN_DP * density)
            }
        }
        viewport = vp
        publish()
        requestTiles()
        driver.setLimits(android.graphics.Rect(0, 0, w, h), host.excludeRects())
        updateDriverInk()
        if (!attached) {
            attached = true
            host.arbiter.attach(this)
            host.arbiter.setOwner(this)
        }
    }

    private fun setViewport(vp: Viewport, delayTiles: Boolean) {
        viewport = vp
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
        when (g) {
            is FingerGesture.PanStart, is FingerGesture.PinchStart -> beginFinger()
            is FingerGesture.Pan -> setViewport(viewport.panBy(g.dx, g.dy, layout), delayTiles = false)
            is FingerGesture.PanEnd, FingerGesture.PinchEnd, FingerGesture.Cancel -> scheduleEndFinger()
            is FingerGesture.Pinch -> {
                val fx = localX(g.fx)
                val fy = localY(g.fy)
                setViewport(viewport.zoomAround(g.scale, fx, fy).panBy(g.dx, g.dy, layout), delayTiles = true)
            }
            is FingerGesture.Tap -> onTap(g.fingers)
            is FingerGesture.LongPress -> Unit
        }
    }

    private fun onTap(fingers: Int) {
        when (fingers) {
            2 -> when (deps.settings.tap2.value) {
                Tap2Action.UNDO -> editor.undo()
                Tap2Action.REDO -> editor.redo()
                Tap2Action.OFF -> Unit
            }
            3 -> when (deps.settings.tap3.value) {
                Tap3Action.REDO -> editor.redo()
                Tap3Action.REFRESH -> deps.epd.refresh(canvasView, EpdMode.GC, null, EpdTiming.AFTER_DRAW)
                Tap3Action.FOCUS_MODE, Tap3Action.OFF -> Unit
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

    private fun scheduleEndFinger() {
        main.removeCallbacks(endFingerRunnable)
        main.postDelayed(endFingerRunnable, FINGER_SETTLE_MS)
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
        deps.epd.refresh(canvasView, EpdMode.GU, null, EpdTiming.AFTER_DRAW)
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
        if (driverActive) return    // the driver reports its own points; touch events only feed palm rejection
        val x = localX(ev.x)
        val y = localY(ev.y)
        when (ev) {
            is PenEvent.Down -> beginPen(x, y, ev.pressure, ev.tiltX, ev.tiltY, ev.tMs, ev.eraser, viaDriver = false)
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
        host.onDriverStroke(true, SystemClock.uptimeMillis())
        beginPen(p.x, p.y, p.pressure, p.tiltX, p.tiltY, p.tMs, eraser, viaDriver = true)
    }

    override fun onRawMove(batch: List<RawPoint>) {
        if (session == null || batch.isEmpty()) return
        val last = batch.last()
        movePen(batch.map { InkPoint(0f, 0f, it.pressure, it.tiltX, it.tiltY, it.tMs) }, last.x, last.y, batch.map { it.x to it.y })
    }

    override fun onRawEnd(p: RawPoint, eraser: Boolean) {
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

    private fun beginPen(x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float, tMs: Long, eraser: Boolean, viaDriver: Boolean) {
        if (session != null) cancelPen()
        if (inkSuspended) return
        if (fingerBusy && !viaDriver) {
            main.removeCallbacks(endFingerRunnable)
            endFinger()
        }
        if (eraser) {
            tempEraser = true
            deps.toolController.temporaryEraser(true)
        }
        val vp = viewport
        val hit = PaneCoords.toPage(vp, layout, x, y)
        val content = hit?.let { editor.content(it.page) }
        val info = hit?.let { h -> editor.pages.value.firstOrNull { it.id == h.page } }
        if (hit == null || content == null || info == null) {
            restoreEraser()
            return
        }
        strokeViewport = vp
        strokeFrame = layout.frameOf(hit.page)
        strokePage = hit.page
        sessionViaDriver = viaDriver
        val preset = deps.toolController.state.value.preset()
        val holdMs = deps.settings.holdShapeMs.value.takeIf { it > 0 }?.toLong()
        val ctx = ToolContext(
            editor = editor,
            page = hit.page,
            pageBounds = info.height?.let { Rect(0f, 0f, info.width, it) },
            pxPerPt = vp.scale,
            preview = live,
            settings = ToolSettings(holdMs, (preset.eraserRadiusDp ?: 0f) * density),
            clock = deps.clock,
        )
        live.page = hit.page
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
            val pt = PaneCoords.toPageIn(strokeViewport, strokeFrame, sx, sy)
            out.add(InkPoint(pt.x, pt.y, p.pressure, p.tiltX, p.tiltY, p.tMs))
            growBounds(sx, sy)
        }
        lastLocalX = lastX
        lastLocalY = lastY
        lastPoint = out.last()
        s.move(out)
    }

    private fun endPen(x: Float, y: Float, tMs: Long, pressure: Float) {
        val s = session ?: return
        session = null
        main.removeCallbacks(pollRunnable)
        growBounds(x, y)
        val pt = PaneCoords.toPageIn(strokeViewport, strokeFrame, x, y)
        val t0 = SystemClock.uptimeMillis()
        s.end(InkPoint(pt.x, pt.y, pressure, 0f, 0f, tMs))
        lastUpToModelMs = SystemClock.uptimeMillis() - t0
        lastUpAt = t0
        val viaDriver = sessionViaDriver
        if (!viaDriver) deps.epd.releaseFast(INK_OWNER)
        restoreEraser()
        val page = strokePage
        val rect = strokeBounds
        if (page != null && rect != null) afterCommit(page, rect, viaDriver)
    }

    private fun cancelPen() {
        val s = session ?: return
        session = null
        main.removeCallbacks(pollRunnable)
        s.cancel()
        live.discardCurrent()
        if (!sessionViaDriver) deps.epd.releaseFast(INK_OWNER)
        restoreEraser()
    }

    private fun restoreEraser() {
        if (tempEraser) {
            tempEraser = false
            deps.toolController.temporaryEraser(false)
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
        val pageRect = Rect(
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
        val level = Viewport.levelFor(viewport.scale)
        val bounds = info.height?.let { Rect(0f, 0f, info.width, it) }
        return TileGeometry.cover(page, level, overlayRect, bounds).all { cache.isFresh(it) }
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
        rect.intersect(0, 0, canvasView.width, canvasView.height)
        if (rect.isEmpty) return
        host.arbiter.block(this, BlockReason.REFRESH, true)
        deps.epd.afterDraw(canvasView) {
            deps.epd.refresh(canvasView, EpdMode.GU, intArrayOf(rect.left, rect.top, rect.right, rect.bottom), EpdTiming.NOW)
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

    override fun onToolChanged(tool: ToolPreset) {
        updateDriverInk()
    }

    override fun onFocusChanged(focused: Boolean) = Unit

    private fun updateDriverInk() {
        val preset = deps.toolController.state.value.preset()
        val ink = if (!deps.settings.driverInk.value || viewport.widthPx == 0) {
            null
        } else if (preset.kind == ToolKind.PEN || preset.kind == ToolKind.HIGHLIGHTER) {
            val pen = if (preset.kind == ToolKind.HIGHLIGHTER) PenType.HIGHLIGHTER else preset.pen ?: PenType.BALLPOINT
            DriverInkPolicy.forPen(InkStyle(pen, preset.color ?: 0xFF000000.toInt(), preset.widthPt ?: DEFAULT_WIDTH_PT), viewport.scale)
        } else {
            null    // erasers and other tools: the driver draws nothing
        }
        driver.setInk(ink)
    }

    // ---------------------------------------------------------------------------------------------------------
    // View state, lifecycle
    // ---------------------------------------------------------------------------------------------------------

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
    fun debugLines(): List<String> {
        val (cov, inFlight) = tileStats()
        val a = host.arbiter
        val reasons = BlockReason.entries.filter { a.isBlockedBy(it) || a.isBlockedBy(this, it) }
        return listOf(
            "owner=${a.isOwner(this)} active=${a.isDriverActive(this)} suspended=${a.isSuspended(this)}",
            "blocked=${reasons.joinToString().ifEmpty { "-" }}",
            "level=${Viewport.levelFor(viewport.scale)} zoom=${"%.2f".format(viewport.zoom)}",
            "tiles fresh=${(cov * 100).toInt()}% inFlight=$inFlight",
            "up->model=${lastUpToModelMs}ms up->disk=${lastUpToDiskMs}ms",
        )
    }

    /** Called by the HUD when the save queue went idle after a stroke. */
    fun onSaveIdle(nowUptime: Long) {
        if (lastUpAt != 0L) {
            lastUpToDiskMs = nowUptime - lastUpAt
            lastUpAt = 0L
        }
    }

    override fun release() {
        if (released) return
        cancelPen()
        released = true
        main.removeCallbacksAndMessages(null)
        deps.epd.releaseFast(FAST_OWNER)
        deps.epd.releaseFast(INK_OWNER)
        scheduler.cancelAll()
        host.arbiter.detach(this)
        cache.clear()
    }

    private companion object {
        const val TAG = "NotebookPane"
        const val FIT_MARGIN_DP = 12f
        const val ZOOM_RENDER_DELAY_MS = 150L
        const val FINGER_SETTLE_MS = 700L
        const val POLL_MS = 50L
        const val HOVER_QUIET_MS = 400L
        const val REFRESH_MAX_MS = 1500L
        const val OVERLAY_MAX_MS = 700L
        const val BOUNDS_PAD_DP = 6f
        const val EVICT_DISTANCE = 5
        const val DEFAULT_WIDTH_PT = 1.134f
        const val FAST_OWNER = "pane-finger"
        const val INK_OWNER = "pane-ink"
    }
}
