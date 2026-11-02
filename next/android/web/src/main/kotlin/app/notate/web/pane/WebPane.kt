package app.notate.web.pane

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.doc.registry.DocumentHandle
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
import app.notate.core.model.Background
import app.notate.core.model.Capabilities
import app.notate.core.model.Capability
import app.notate.core.model.DocumentMeta
import app.notate.core.model.InkPoint
import app.notate.core.model.InkStyle
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PageLayoutMode
import app.notate.core.model.PenMode
import app.notate.core.model.PenType
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import app.notate.core.model.ViewState
import app.notate.core.model.WebDocSettings
import app.notate.core.model.edit.Edit
import app.notate.core.store.OutlineEntry
import app.notate.core.web.WebLayoutLock
import app.notate.core.web.WebLayoutMath
import app.notate.core.web.WebOrigin
import app.notate.core.web.WebResourcePaths
import app.notate.core.web.WebViewTransform
import app.notate.editor.EditorDeps
import app.notate.editor.pane.DocumentPane
import app.notate.editor.pane.PaneCoords
import app.notate.editor.pane.PaneHost
import app.notate.editor.popup.MenuEntry
import app.notate.editor.popup.Placement
import app.notate.editor.popup.PopupUi
import app.notate.editor.popup.SliderView
import app.notate.editor.ui.Ink
import app.notate.editor.ui.dp
import app.notate.editor.view.LiveInkLayer
import app.notate.platform.ink.ConfigurableDriverPort
import app.notate.platform.ink.NoDriverPort
import app.notate.platform.ink.OnyxDriverPort
import app.notate.platform.ink.RawPenSink
import app.notate.platform.ink.RawPoint
import app.notate.web.R
import app.notate.web.layout.WebBridge
import app.notate.web.sandbox.SandboxClient
import app.notate.web.sandbox.WebSandbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One chapter of a web document (EPUB, HTML) at a time: a sandboxed WebView with the handwriting layer on top (R4-2).
 *
 * In Write mode the pen goes to the tool sessions and fingers belong to the WebView (tap, scroll, pinch); in Interact mode the
 * pen is a finger too. Page points are `(viewPx + contentScrollPx) / webScale × 0.75`, see [WebViewTransform]; the WebView's
 * scale and scroll are read again whenever it reports a change, so ink stays on the words it was written on. Main thread only.
 */
class WebPane(
    private val context: Context,
    override val handle: DocumentHandle,
    private val deps: EditorDeps,
    private val host: PaneHost,
    private val scope: CoroutineScope,
    override val id: PaneId = PaneId("main"),
) : DocumentPane, RawPenSink, SandboxClient.Listener, WebBridge.Listener {
    private val editor = handle.editor
    private val docId = handle.id.value
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val factory = ToolSessionFactory()

    // --- views ---
    private val webView = WebView(context)
    private val overlay = InkOverlayView(context)
    private val live = LiveInkLayer(overlay, density) { editor.content(it) }.also { it.paintHiddenWhite = false }
    private val bar = PaneBar(
        context, onPrevious = { stepChapter(-1) }, onNext = { stepChapter(1) }, onContents = { showContents() },
        onMode = { interact -> setPenMode(if (interact) PenMode.INTERACT else PenMode.WRITE) },
    )
    private val banner = TextView(context).apply {
        text = context.getString(R.string.web_layout_changed)
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.WHITE)
        textSize = 14f
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        visibility = View.GONE
    }
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    override val view: View get() = root

    override val capabilities: Set<Capability> = Capabilities.forKind(handle.editor.meta.value.kind)

    override val driver: ConfigurableDriverPort =
        if (deps.device.hasRawDrawing) OnyxDriverPort(overlay, this, deps.device) else NoDriverPort
    override val closesWhenNotOwner: Boolean = true
    override val consumesFingers: Boolean = false

    override val penMode: InputPenMode
        get() = when {
            docSettings.penMode == PenMode.READ_ONLY -> InputPenMode.READ_ONLY
            interactive -> InputPenMode.INTERACT
            else -> InputPenMode.WRITE
        }

    private val docSettings: WebDocSettings get() = editor.meta.value.web ?: WebDocSettings()

    /** The pen operates the page: Interact mode, or the Hand tool. */
    private val interactive: Boolean
        get() = docSettings.penMode == PenMode.INTERACT || deps.toolController.baseState().active == ToolKind.HAND

    // --- chapter state ---
    private var current: PageInfo? = null
    private var started = false
    private var released = false
    private var attached = false
    private var outline: List<OutlineEntry> = emptyList()
    private val dismissedBanners = HashSet<PageId>()
    private var appliedJs: Boolean? = null
    private var appliedZoom = -1
    private var appliedReduce: Boolean? = null
    private var lastKind: ToolKind? = null
    private var appliedPenMode: PenMode = PenMode.WRITE
    private var loadedPaneWidthPx = 0

    @Volatile private var lockedWidthCss = 0f
    private var fitScale = 0f
    private var pendingRestoreY: Float? = null
    private var pendingZoom = 1f
    private var viewStateLocked = true

    // --- geometry ---
    var viewport: Viewport = Viewport(0f, 0f, 1f, 1f, 0, 0)
        private set
    var layout: DocumentLayout = DocumentLayout(emptyList(), PageLayoutMode.CONTINUOUS)
        private set
    private val loc = IntArray(2)

    private val bridge: WebBridge
    private val client: SandboxClient

    // --- pen state ---
    private var session: ToolSession? = null
    private var sessionViaDriver = false
    private var strokeViewport: Viewport = viewport
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
    private val saveViewRunnable = Runnable { persistViewState() }
    private val restoreRunnable = Runnable { restoreScroll(final = false) }
    private val finalRestoreRunnable = Runnable { restoreScroll(final = true) }

    init {
        overlay.live = live
        overlay.content = { editor.content(it) }
        live.viewportProvider = { viewport }
        live.layoutProvider = { layout }
        bridge = WebBridge(webView, this)
        client = SandboxClient(
            docId = docId,
            root = File(handle.dir, "assets/web"),
            network = { docSettings.network },
            listener = this,
            layoutWidthCss = { lockedWidthCss },
        )
        webView.apply {
            webViewClient = client
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.WHITE)
            setOnScrollChangeListener { _, _, _, _, _ -> onContentMoved() }
        }
        WebSandbox.configure(webView.settings, docSettings)
        appliedJs = docSettings.javascript
        appliedZoom = docSettings.textZoom
        appliedReduce = docSettings.reduceMotion
        appliedPenMode = docSettings.penMode

        val body = FrameLayout(context)
        body.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        banner.setOnClickListener {
            current?.let { dismissedBanners.add(it.id) }
            updateBanner()
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(PaneBar.HEIGHT_DP)))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        overlay.sizeListener = ::onBodySized

        scope.launch {
            editor.changes.collect {
                overlay.invalidate()
                updateBanner()
            }
        }
        scope.launch { editor.pages.collect { onPagesChanged() } }
        scope.launch { editor.meta.collect { onMetaChanged() } }
        scope.launch { deps.toolController.state.collect { onToolChanged(it.preset()) } }
        scope.launch { deps.toolController.selections.collect { onToolPicked(it) } }
        scope.launch { deps.settings.driverInk.collect { updateDriverInk() } }
        scope.launch {
            try {
                outline = handle.readOutline()
                updateBar()
            } catch (e: Exception) {
                deps.logger.e(TAG, "Reading the contents failed", e)
            }
        }
        updateMode()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Chapters
    // ---------------------------------------------------------------------------------------------------------

    private fun chapters(): List<PageInfo> = editor.pages.value.filter { it.background is Background.Web }

    private fun hrefOf(p: PageInfo): String = (p.background as Background.Web).href

    private fun samePath(a: String, b: String): Boolean = WebResourcePaths.normalize(a) == WebResourcePaths.normalize(b)

    private fun onBodySized(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || released) return
        if (!started) {
            started = true
            val saved = initialView ?: editor.meta.value.view
            val first = chapters().let { list -> list.firstOrNull { it.id == saved?.page } ?: list.firstOrNull() }
            if (first == null) {
                bar.setTitle("")
            } else {
                val restoreCss = saved?.takeIf { it.page == first.id }?.let { WebViewTransform.ptToCss(it.scrollYPt) }
                showChapter(first, null, restoreCss, saved?.takeIf { it.page == first.id }?.zoom ?: 1f)
            }
        } else if (loadedPaneWidthPx > 0 && Math.abs(w - loadedPaneWidthPx) > w * RELOAD_WIDTH_FRACTION) {
            // The pane got narrower or wider (rotation, split): scale the locked layout to it again, staying where we are.
            reloadHere()
        }
        syncViewport()
        refreshExcludes()
        updateDriverInk()
        if (!attached) {
            attached = true
            host.arbiter.attach(this)
            host.claimPen(this)
            updateMode()
        }
    }

    private fun showChapter(page: PageInfo, fragment: String?, restoreCssY: Float?, zoom: Float = 1f) {
        persistViewState()
        current = page
        val bg = page.background as Background.Web
        lockedWidthCss = bg.layoutWidthCss
        pendingRestoreY = restoreCssY
        pendingZoom = zoom
        viewStateLocked = true
        bridge.reset()
        overlay.page = page.id
        scope.launch {
            try {
                editor.ensureLoaded(page.id)
            } catch (e: Exception) {
                deps.logger.e(TAG, "Loading chapter ink failed", e)
            }
            overlay.invalidate()
            updateBanner()
        }
        loadedPaneWidthPx = overlay.width
        val percent = WebLayoutLock.initialScalePercent(overlay.width, bg.layoutWidthCss, density)
        fitScale = if (percent > 0) percent / 100f * density else 0f
        if (percent > 0) webView.setInitialScale(max(1, (percent * zoom).roundToInt()))
        val url = WebOrigin.urlFor(docId, Uri.encode(bg.href, "/")) + (fragment?.let { "#" + Uri.encode(it) } ?: "")
        webView.loadUrl(url)
        updateBar()
        updateBanner()
        syncViewport()
    }

    /** Loads the current chapter again (layout width or pane width changed), keeping the reading position. */
    private fun reloadHere() {
        val page = current ?: return
        val css = scrollYCss()
        showChapter(page, null, css, zoomFactor())
    }

    private fun stepChapter(delta: Int) {
        val list = chapters()
        val i = list.indexOfFirst { it.id == current?.id }
        val target = list.getOrNull(i + delta) ?: return
        showChapter(target, null, null)
    }

    /** Opens the chapter [path] (inserting a page row for files not seen before) and scrolls to [fragment]. */
    private fun openPath(path: String, fragment: String?) {
        val list = chapters()
        val existing = list.firstOrNull { samePath(hrefOf(it), path) }
        val target = existing ?: insertChapter(path, list) ?: return
        showChapter(target, fragment, null)
    }

    private fun insertChapter(path: String, list: List<PageInfo>): PageInfo? {
        val last = list.lastOrNull() ?: return null
        val width = (last.background as Background.Web).layoutWidthCss
        val info = PageInfo(
            id = editor.newPageId(), ord = last.ord + 1.0, width = WebViewTransform.cssToPt(width), height = null,
            background = Background.Web(WebResourcePaths.normalize(path), width, WebLayoutLock.INITIAL_GEN),
            inkRev = 0, modifiedAt = deps.clock.now(),
        )
        editor.apply(Edit.InsertPage(info, emptyList()), undoable = false)
        return info
    }

    private fun onPagesChanged() {
        val cur = current ?: return
        val fresh = chapters().firstOrNull { it.id == cur.id } ?: return
        val old = (cur.background as Background.Web).layoutWidthCss
        val now = (fresh.background as Background.Web).layoutWidthCss
        current = fresh
        if (old != now) reloadHere()
        updateBar()
        updateBanner()
    }

    private fun updateBar() {
        val list = chapters()
        val i = list.indexOfFirst { it.id == current?.id }
        if (i < 0) return
        val page = list[i]
        val name = outline.firstOrNull { it.page == page.id }?.title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.web_untitled_chapter, i + 1)
        bar.setTitle(context.getString(R.string.web_chapter_fmt, i + 1, name))
        bar.setChapterNav(hasPrevious = i > 0, hasNext = i < list.size - 1)
    }

    private fun updateBanner() {
        val page = current
        val show = page != null && WebLayoutLock.showLayoutChangedBanner(
            layoutGen = (page.background as Background.Web).layoutGen,
            hasInk = editor.content(page.id)?.all()?.any() == true,
            dismissed = page.id in dismissedBanners,
        )
        banner.visibility = if (show) View.VISIBLE else View.GONE
    }

    // --- SandboxClient.Listener ---

    override fun onDocumentLink(path: String, fragment: String?): Boolean {
        val cur = current ?: return false
        if (path.isEmpty() || samePath(path, hrefOf(cur))) return false      // an anchor on this page: the WebView scrolls itself
        openPath(path, fragment)
        return true
    }

    override fun onExternalLink(url: String) {
        main.post {
            if (released) return@post
            confirm(null, context.getString(R.string.web_open_link), context.getString(R.string.web_open)) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    deps.logger.w(TAG, "No app can open the link", e)
                }
            }
        }
    }

    override fun onPageStarted(url: String) {
        bridge.reset()
        bridge.inject(lockedWidthCss, docSettings.reduceMotion)
    }

    override fun onPageFinished(url: String) {
        bridge.inject(lockedWidthCss, docSettings.reduceMotion)
        main.removeCallbacks(restoreRunnable)
        main.removeCallbacks(finalRestoreRunnable)
        main.postDelayed(restoreRunnable, RESTORE_FIRST_MS)
        main.postDelayed(finalRestoreRunnable, RESTORE_FINAL_MS)
        syncViewport()
    }

    override fun onScaleChanged(scale: Float) {
        onContentMoved()
    }

    override fun onRendererGone() {
        main.post {
            if (!released) Toast.makeText(context, R.string.web_page_crashed, Toast.LENGTH_LONG).show()
        }
    }

    // --- WebBridge.Listener ---

    override fun onScrolled() = onContentMoved()

    override fun onLayoutReported(widthCss: Float, fixed: Boolean) {
        val page = current ?: return
        val bg = page.background as Background.Web
        // A fixed-layout page keeps its own viewport: adopt that width as the chapter's layout, once.
        if (fixed && WebLayoutMath.differs(bg.layoutWidthCss, widthCss)) {
            editor.apply(
                Edit.SetPage(page, page.copy(width = WebViewTransform.cssToPt(widthCss), background = bg.copy(layoutWidthCss = widthCss))),
                undoable = false,
            )
        }
    }

    override fun onHashChanged(hash: String) {
        onContentMoved()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Scroll, zoom, viewport
    // ---------------------------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun webScale(): Float = webView.scale.takeIf { it > 0f } ?: density

    private fun transform(): WebViewTransform {
        val s = webScale()
        return WebViewTransform(s, bridge.scrollXPx(s), bridge.scrollYPx(s))
    }

    private fun scrollYCss(): Float = transform().let { it.scrollYPx / it.scale }

    private fun zoomFactor(): Float = if (fitScale > 0f) (webScale() / fitScale).coerceIn(0.25f, 6f) else 1f

    /** Recomputes how page points map to the overlay and redraws the ink. */
    private fun syncViewport() {
        val w = overlay.width
        val h = overlay.height
        val t = transform()
        val pxPerPt = t.pxPerPt
        if (w <= 0 || h <= 0 || pxPerPt <= 0f) return
        val vp = Viewport(t.scrollXPx / pxPerPt, t.scrollYPx / pxPerPt, 1f, pxPerPt, w, h)
        viewport = vp
        val cur = current
        if (cur != null) {
            // One page that always covers the content and everything visible, so any visible point belongs to it.
            val vis = vp.visibleDocRect()
            val pageW = max(max(cur.width, WebViewTransform.cssToPt(bridge.contentWidthCss)), vis.r)
            val pageH = max(WebViewTransform.cssToPt(max(bridge.contentHeightCss, webView.contentHeight.toFloat())), vis.b)
            layout = DocumentLayout(listOf(cur.copy(width = pageW, height = pageH)), PageLayoutMode.CONTINUOUS)
        }
        overlay.viewport = vp
        overlay.layout = layout
        overlay.invalidate()
    }

    private fun onContentMoved() {
        if (released) return
        syncViewport()
        beginFinger()
        scheduleEndFinger()
        main.removeCallbacks(saveViewRunnable)
        main.postDelayed(saveViewRunnable, VIEW_STATE_DELAY_MS)
    }

    private fun restoreScroll(final: Boolean) {
        val y = pendingRestoreY
        if (y != null) {
            val s = webScale()
            if (!final || Math.abs(scrollYCss() - y) > 2f) bridge.scrollToCss(y, s)
        }
        if (final) {
            pendingRestoreY = null
            viewStateLocked = false
            persistViewState()
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // PaneTarget
    // ---------------------------------------------------------------------------------------------------------

    override fun hitTest(x: Float, y: Float): Boolean {
        overlay.getLocationInWindow(loc)
        return x >= loc[0] && y >= loc[1] && x < loc[0] + overlay.width && y < loc[1] + overlay.height
    }

    private fun localX(x: Float): Float { overlay.getLocationInWindow(loc); return x - loc[0] }
    private fun localY(y: Float): Float { overlay.getLocationInWindow(loc); return y - loc[1] }

    /** Fingers are the WebView's business ([consumesFingers] is false); nothing arrives here except a cancel. */
    override fun onFingerGesture(g: FingerGesture) = Unit

    /** A scroll or zoom of the page is under way: the pen driver steps aside and the panel uses its fast waveform. */
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
    }

    private fun endFinger() {
        if (!fingerBusy) return
        fingerBusy = false
        deps.epd.releaseFast(FAST_OWNER)
        host.arbiter.block(this, BlockReason.FINGER_GESTURE, false)
        updateDriverInk()
        deps.epd.refresh(overlay, EpdMode.GU, null, EpdTiming.AFTER_DRAW)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pen: touch-event path
    // ---------------------------------------------------------------------------------------------------------

    private val driverActive: Boolean get() = driver !== NoDriverPort && host.arbiter.isDriverActive(this)

    override fun onPen(ev: PenEvent) {
        if (released) return
        if (ev is PenEvent.Hover) {
            onHover(ev)
            return
        }
        // See NotebookPane.onPen: a touch-driven stroke goes on that way, and so does the first stroke after a focus switch.
        val touchStroke = session != null && !sessionViaDriver
        if (!app.notate.core.input.PenRouting.inkFromTouch(driverActive, touchStroke, ev)) return
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
        if (suspended && session != null && !sessionViaDriver) cancelPen()
    }

    override fun onDriverDeactivated() {
        // The driver was switched off mid-stroke and will never report its end: keep what was written.
        if (session != null && sessionViaDriver) endPen(lastLocalX, lastLocalY, lastPoint?.tMs ?: 0L, 0f)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pen: common. A pen event this pane accepted always reaches a tool session; nothing is dropped for being early.
    // ---------------------------------------------------------------------------------------------------------

    private fun beginPen(x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float, tMs: Long, eraser: Boolean, viaDriver: Boolean) {
        if (session != null) cancelPen()
        if (fingerBusy && !viaDriver) {
            main.removeCallbacks(endFingerRunnable)
            endFinger()
        }
        if (eraser) {
            tempEraser = true
            deps.toolController.temporaryEraser(true)
        }
        syncViewport()
        val vp = viewport
        val page = current
        val hit = page?.let { PaneCoords.toPage(vp, layout, x, y) }
        val content = page?.let { editor.content(it.id) }
        if (page == null || hit == null || content == null) {
            restoreEraser()
            return
        }
        strokeViewport = vp
        strokePage = page.id
        sessionViaDriver = viaDriver
        val preset = deps.toolController.state.value.preset()
        val holdMs = deps.settings.holdShapeMs.value.takeIf { it > 0 }?.toLong()
        val ctx = ToolContext(
            editor = editor,
            page = page.id,
            pageBounds = null,
            pxPerPt = vp.scale,
            preview = live,
            settings = ToolSettings(holdMs, (preset.eraserRadiusDp ?: 0f) * density),
            clock = deps.clock,
        )
        live.page = page.id
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
        val frame = Rect(0f, 0f, 0f, 0f)
        for ((i, p) in points.withIndex()) {
            val sx = xs?.get(i)?.first ?: lastX
            val sy = xs?.get(i)?.second ?: lastY
            val pt = PaneCoords.toPageIn(strokeViewport, frame, sx, sy)
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
        val pt = PaneCoords.toPageIn(strokeViewport, Rect(0f, 0f, 0f, 0f), x, y)
        s.end(InkPoint(pt.x, pt.y, pressure, 0f, 0f, tMs))
        val viaDriver = sessionViaDriver
        if (!viaDriver) deps.epd.releaseFast(INK_OWNER)
        restoreEraser()
        // The result is in the page content already and the overlay draws it: the live picture can go.
        live.releaseOverlay()
        overlay.invalidate()
        updateBanner()
        strokeBounds?.let { afterCommit(it, viaDriver) }
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
    // After a stroke: one panel refresh of the written area once the pen has left
    // ---------------------------------------------------------------------------------------------------------

    private fun afterCommit(screenRect: android.graphics.Rect, viaDriver: Boolean) {
        val pending = refreshRect
        if (pending == null) refreshRect = android.graphics.Rect(screenRect) else pending.union(screenRect)
        if (refreshFirstAt == 0L) refreshFirstAt = SystemClock.uptimeMillis()
        refreshAfterDriver = refreshAfterDriver || viaDriver
        armRefresh(if (refreshAfterDriver) HOVER_QUIET_MS else 0L)
    }

    private fun armRefresh(delayMs: Long) {
        main.removeCallbacks(refreshRunnable)
        main.postDelayed(refreshRunnable, delayMs)
    }

    private fun tryRefresh() {
        val rect = refreshRect ?: return
        if (session != null) { armRefresh(HOVER_QUIET_MS); return }
        refreshRect = null
        refreshFirstAt = 0L
        refreshAfterDriver = false
        rect.intersect(0, 0, overlay.width, overlay.height)
        if (rect.isEmpty) return
        host.arbiter.block(this, BlockReason.REFRESH, true)
        deps.epd.afterDraw(overlay) {
            deps.epd.refresh(overlay, EpdMode.GU, intArrayOf(rect.left, rect.top, rect.right, rect.bottom), EpdTiming.NOW)
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
    // Pen mode, tools, settings
    // ---------------------------------------------------------------------------------------------------------

    private fun setPenMode(mode: PenMode) {
        val meta = editor.meta.value
        val web = meta.web ?: WebDocSettings()
        if (web.penMode == mode) return
        editor.apply(Edit.SetMeta(meta, meta.copy(web = web.copy(penMode = mode))), undoable = false)
    }

    private fun updateMode() {
        val on = interactive
        host.arbiter.block(this, BlockReason.INTERACT_MODE, on)
        host.arbiter.block(this, BlockReason.READ_ONLY, docSettings.penMode == PenMode.READ_ONLY)
        bar.setMode(docSettings.penMode == PenMode.INTERACT)
        if (on && session != null) cancelPen()
        updateDriverInk()
    }

    private fun onMetaChanged() {
        val web = docSettings
        if (web.penMode != appliedPenMode) {
            appliedPenMode = web.penMode
            updateMode()
        }
        WebSandbox.apply(webView.settings, web)
        val jsChanged = appliedJs != null && appliedJs != web.javascript
        val zoomChanged = appliedZoom != web.textZoom
        appliedJs = web.javascript
        appliedZoom = web.textZoom
        if (appliedReduce != web.reduceMotion) {
            appliedReduce = web.reduceMotion
            bridge.setReduceMotion(web.reduceMotion)
        }
        if ((jsChanged || zoomChanged) && started && current != null) reloadHere()
    }

    override fun onToolChanged(tool: ToolPreset) {
        lastKind = tool.kind
        updateMode()
    }

    /** Picking a pen-like tool while the pen operates the page switches back to writing (04 §4). */
    private fun onToolPicked(kind: ToolKind) {
        if (docSettings.penMode == PenMode.INTERACT && kind in WRITING_TOOLS) setPenMode(PenMode.WRITE)
    }

    override fun refreshExcludes() {
        if (overlay.width == 0) return
        driver.setLimits(android.graphics.Rect(0, 0, overlay.width, overlay.height), host.excludeRects())
    }

    override fun onFocusChanged(focused: Boolean) = Unit

    private fun updateDriverInk() {
        val preset = deps.toolController.state.value.preset()
        val ink = if (!deps.settings.driverInk.value || overlay.width == 0 || interactive) {
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
    // Menus and popups
    // ---------------------------------------------------------------------------------------------------------

    override fun moreEntries(): List<MenuEntry> = listOf(
        MenuEntry(context.getString(R.string.web_contents)) { showContents() },
        MenuEntry(context.getString(R.string.web_reading_options)) { showReadingOptions() },
        MenuEntry(context.getString(R.string.web_clear_page)) { clearPage() },
    )

    private fun popupText(text: CharSequence) = PopupUi.text(context, text)

    private fun confirm(anchor: View?, message: String, positive: String, onYes: () -> Unit) {
        val col = PopupUi.column(context)
        col.addView(popupText(message), LinearLayout.LayoutParams(context.dp(280), ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(PopupUi.button(context, context.getString(R.string.web_cancel)) { host.dismissPopup() })
        row.addView(PopupUi.button(context, positive) {
            host.dismissPopup()
            onYes()
        })
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        host.showPopup(anchor, col, Placement.CENTER)
    }

    private fun showContents() {
        val col = PopupUi.column(context)
        if (outline.isEmpty()) {
            col.addView(popupText(context.getString(R.string.web_no_contents)))
        } else {
            val depth = HashMap<Long, Int>()
            for (e in outline) depth[e.id] = (e.parentId?.let { depth[it] } ?: -1) + 1
            for (e in outline) {
                val page = e.page
                val label = PopupUi.button(context, e.title.ifBlank { context.getString(R.string.web_untitled_chapter, e.ord + 1) }) {
                    host.dismissPopup()
                    if (page != null) chapters().firstOrNull { it.id == page }?.let { showChapter(it, e.anchor, null) }
                }.apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    minWidth = context.dp(260)
                    setPadding(context.dp(12 + 16 * (depth[e.id] ?: 0)), 0, context.dp(12), 0)
                    alpha = if (page == null) 0.5f else 1f
                }
                col.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(48)))
            }
        }
        val scroll = android.widget.ScrollView(context).apply { addView(col) }
        host.showPopup(null, scroll, Placement.CENTER)
    }

    private fun showReadingOptions() {
        val web = docSettings
        val col = PopupUi.column(context)
        fun toggle(label: String, on: Boolean, change: () -> Unit) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(popupText(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(popupText(if (on) "On" else "Off"))
            row.setOnClickListener { change() }
            col.addView(row, LinearLayout.LayoutParams(context.dp(280), context.dp(48)))
        }
        toggle(context.getString(R.string.web_opt_javascript), web.javascript) { updateWeb { it.copy(javascript = !web.javascript) } }
        toggle(context.getString(R.string.web_opt_network), web.network) {
            if (web.network) {
                updateWeb { it.copy(network = false) }
            } else {
                confirm(null, context.getString(R.string.web_network_confirm), context.getString(R.string.web_continue)) {
                    updateWeb { it.copy(network = true) }
                    reloadHere()
                }
            }
        }
        toggle(context.getString(R.string.web_opt_reduce_motion), web.reduceMotion) { updateWeb { it.copy(reduceMotion = !web.reduceMotion) } }
        val sizeLabel = popupText(context.getString(R.string.web_opt_text_size, web.textZoom))
        col.addView(sizeLabel)
        col.addView(
            SliderView(context, 80f, 200f, web.textZoom.toFloat(), onLive = {
                sizeLabel.text = context.getString(R.string.web_opt_text_size, snapZoom(it))
            }, onCommit = { v -> updateWeb { it.copy(textZoom = snapZoom(v)) } }),
            LinearLayout.LayoutParams(context.dp(280), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        col.addView(
            PopupUi.button(context, context.getString(R.string.web_opt_reflow)) {
                host.dismissPopup()
                reflowToFit()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(48)),
        )
        host.showPopup(null, col, Placement.CENTER)
    }

    private fun snapZoom(v: Float): Int = ((v / 10f).roundToInt() * 10).coerceIn(80, 200)

    private fun updateWeb(change: (WebDocSettings) -> WebDocSettings) {
        val meta = editor.meta.value
        val web = meta.web ?: WebDocSettings()
        val next = change(web)
        if (next == web) return
        editor.apply(Edit.SetMeta(meta, meta.copy(web = next)), undoable = false)
        host.dismissPopup()
        showReadingOptions()
    }

    /** Lays the current chapter out at the pane's width (04 §4). Existing ink stays where it is and a notice warns about it. */
    private fun reflowToFit() {
        val page = current ?: return
        val hasInk = editor.content(page.id)?.all()?.any() == true
        val go = { applyReflow(page) }
        if (hasInk) confirm(null, context.getString(R.string.web_reflow_confirm), context.getString(R.string.web_continue)) { go() } else go()
    }

    private fun applyReflow(page: PageInfo) {
        val bg = page.background as Background.Web
        val width = WebLayoutMath.reflowWidth(overlay.width / density)
        val meta = editor.meta.value
        val web = meta.web ?: WebDocSettings()
        val after = page.copy(width = WebViewTransform.cssToPt(width), background = bg.copy(layoutWidthCss = width, layoutGen = bg.layoutGen + 1))
        dismissedBanners.remove(page.id)
        editor.apply(
            Edit.Batch(listOf(Edit.SetPage(page, after), Edit.SetMeta(meta, meta.copy(web = web.copy(reflow = true))))),
            undoable = false,
        )
    }

    private fun clearPage() {
        val page = current ?: return
        val items = editor.content(page.id)?.all()?.toList().orEmpty()
        if (items.isEmpty()) return
        confirm(null, context.getString(R.string.web_clear_confirm), context.getString(R.string.web_clear)) {
            editor.apply(Edit.Items(page.id, removed = items, added = emptyList()))
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // View state, lifecycle
    // ---------------------------------------------------------------------------------------------------------

    override fun saveViewState(): ViewState? {
        val page = current ?: return null
        if (viewStateLocked || overlay.width == 0) return null
        val t = transform()
        return ViewState(
            page.id, zoomFactor(), WebViewTransform.cssToPt(t.scrollXPx / t.scale), WebViewTransform.cssToPt(t.scrollYPx / t.scale),
            PageLayoutMode.CONTINUOUS,
        )
    }

    /** Writes the reading position into the metadata (not undoable; the save queue merges these writes). */
    private fun persistViewState() {
        main.removeCallbacks(saveViewRunnable)
        val state = saveViewState() ?: return
        val meta: DocumentMeta = editor.meta.value
        if (meta.view != state) editor.apply(Edit.SetMeta(meta, meta.copy(view = state)), undoable = false)
    }

    private var initialView: ViewState? = null

    override fun setInitialView(s: ViewState?) { initialView = s }

    override val ownsPaneBar: Boolean get() = true

    override fun setSplitControls(maximize: (() -> Unit)?, close: (() -> Unit)?) = bar.setSplitControls(maximize, close)

    override fun restoreViewState(s: ViewState) {
        val page = chapters().firstOrNull { it.id == s.page } ?: return
        showChapter(page, null, WebViewTransform.ptToCss(s.scrollYPt), s.zoom)
    }

    override fun debugLines(): List<String> {
        val a = host.arbiter
        val reasons = BlockReason.entries.filter { a.isBlockedBy(it) || a.isBlockedBy(this, it) }
        val t = transform()
        return listOf(
            "owner=${a.isOwner(this)} active=${a.isDriverActive(this)} suspended=${a.isSuspended(this)}",
            "blocked=${reasons.joinToString().ifEmpty { "-" }}",
            "web scale=${"%.2f".format(t.scale)} scroll=${t.scrollXPx.roundToInt()},${t.scrollYPx.roundToInt()} layout=${lockedWidthCss.roundToInt()}css",
            "mode=${docSettings.penMode} js=${docSettings.javascript} net=${docSettings.network}",
        )
    }

    override fun release() {
        if (released) return
        persistViewState()
        cancelPen()
        released = true
        main.removeCallbacksAndMessages(null)
        deps.epd.releaseFast(FAST_OWNER)
        deps.epd.releaseFast(INK_OWNER)
        host.arbiter.detach(this)
        bridge.release()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }

    private companion object {
        const val TAG = "WebPane"
        const val POLL_MS = 50L
        const val HOVER_QUIET_MS = 400L
        const val REFRESH_MAX_MS = 1500L
        const val FINGER_SETTLE_MS = 700L
        const val VIEW_STATE_DELAY_MS = 1000L
        const val RESTORE_FIRST_MS = 150L
        const val RESTORE_FINAL_MS = 800L
        const val RELOAD_WIDTH_FRACTION = 0.05f
        const val BOUNDS_PAD_DP = 6f
        const val DEFAULT_WIDTH_PT = 1.134f
        const val FAST_OWNER = "web-pane-finger"
        const val INK_OWNER = "web-pane-ink"
        val WRITING_TOOLS = setOf(ToolKind.PEN, ToolKind.HIGHLIGHTER, ToolKind.ERASER, ToolKind.SHAPE)
    }
}
