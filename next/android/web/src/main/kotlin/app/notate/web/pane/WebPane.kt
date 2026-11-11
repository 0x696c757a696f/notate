package app.notate.web.pane

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.FileProvider
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
import app.notate.core.web.ChapterNotes
import app.notate.core.web.ChapterTextSearch
import app.notate.core.web.NotesToc
import app.notate.core.web.WebNotesJson
import app.notate.core.web.WebExportPlan
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
import app.notate.web.export.WebExporter
import app.notate.web.find.WebFindBar
import app.notate.web.layout.WebBridge
import app.notate.web.sandbox.SandboxClient
import app.notate.web.sandbox.WebSandbox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
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
    /** Replaced when its renderer process dies ([onRendererGone]). */
    private var webView = WebView(context)
    private val body = FrameLayout(context)
    private val overlay = InkOverlayView(context)
    /**
     * Transparent surface over the page that only the Onyx pen driver uses, as the note canvas does (TouchHelper in
     * SFTouchRender + single-region mode on a SurfaceView). It draws nothing and takes no touches; null = the driver runs
     * on the ink overlay (a plain View), the earlier form, kept for comparison (Settings → Pen surface on web pages).
     */
    private val penSurface: android.view.SurfaceView? =
        if (deps.settings.webPenSurface.value) android.view.SurfaceView(context).apply {
            setZOrderOnTop(true)
            holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)
            isFocusable = false
        } else null
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
    private val findBar = WebFindBar(
        context, onQuery = ::onFindQuery, onStep = { webView.findNext(it) },
        onAllChapters = ::onFindAllChapters, onClose = ::closeFind,
    ).apply { visibility = View.GONE }
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    override val view: View get() = root

    override val capabilities: Set<Capability> = Capabilities.forKind(handle.editor.meta.value.kind)

    override val driver: ConfigurableDriverPort =
        if (deps.device.hasRawDrawing && deps.settings.driverInk.value) OnyxDriverPort(penSurface ?: overlay, this, deps.device) else NoDriverPort
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

    // --- find, export (R4-3) ---
    private var findAllChapters = false
    private var pendingFind: String? = null
    private var findJob: Job? = null
    private val findRunnable = Runnable { runChapterSearch() }
    @Volatile private var exporting = false
    private var exportJob: Job? = null
    private var pageLoaded: CompletableDeferred<Unit>? = null
    private var lastSaveAt = 0L
    private var nextVisualState = 1L

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

    private var bridge: WebBridge
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
    /** The overlay's last frame lacks committed driver strokes (the driver's ink shows them); redrawn with the refresh. */
    private var overlayStale = false
    private var inkedByDriver = 0
    private var inkedByTouch = 0
    private var refreshes = 0
    private var deferOwnCommitUntil = 0L
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
        setUpWebView(webView)
        appliedJs = docSettings.javascript
        appliedZoom = docSettings.textZoom
        appliedReduce = docSettings.reduceMotion
        appliedPenMode = docSettings.penMode

        body.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        penSurface?.let { surface ->
            body.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            surface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                // As NotebookPane: the driver session lives on the surface, so it is reopened once per new surface or real
                // size change. A surface that never received a buffer may not be recognised by the driver: post a clear one.
                private var surfaceW = -1
                private var surfaceH = -1
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    clearFrame(holder)
                    val f = holder.surfaceFrame
                    surfaceW = f.width(); surfaceH = f.height()
                    if (attached && !released) host.arbiter.reopen(this@WebPane)
                }
                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {
                    clearFrame(holder)
                    if (w == surfaceW && h == surfaceH) return
                    surfaceW = w; surfaceH = h
                    if (attached && !released) host.arbiter.reopen(this@WebPane)
                }
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                    if (attached && !released) host.arbiter.closeSession(this@WebPane)
                }
            })
        }
        body.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        banner.setOnClickListener {
            current?.let { dismissedBanners.add(it.id) }
            updateBanner()
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(PaneBar.HEIGHT_DP)))
        root.addView(findBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(WebFindBar.HEIGHT_DP)))
        webView.setFindListener { active, total, done -> if (done) findBar.setCount(active + 1, total) }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        overlay.sizeListener = ::onBodySized

        scope.launch {
            editor.changes.collect {
                // The driver already shows a stroke it inked: redrawing the overlay now would put a window frame (overlay +
                // WebView) on the panel between two strokes. The overlay is redrawn with the post-writing refresh instead.
                if (SystemClock.uptimeMillis() < deferOwnCommitUntil) overlayStale = true else overlay.invalidate()
                updateBanner()
            }
        }
        scope.launch { editor.pages.collect { onPagesChanged() } }
        scope.launch { handle.save.state.collect { if (it is app.notate.core.doc.save.SaveState.Clean) lastSaveAt = deps.clock.now() } }
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

    private fun setUpWebView(v: WebView) {
        v.apply {
            webViewClient = client
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.WHITE)
            setOnScrollChangeListener { _, _, _, _, _ -> onContentMoved() }
            // A finger on the page (tap an answer, scroll, play an animation) wakes it up; see [updateFreeze].
            setOnTouchListener { _, ev ->
                if (ev.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER) thaw(FINGER_THAW_MS)
                false
            }
        }
        WebTimers.register(this, v)
        WebSandbox.configure(v.settings, docSettings)
    }

    private val rendererDeaths = ArrayDeque<Long>()

    /**
     * The page's renderer process died (crash, or killed for memory): a WebView in that state can only be destroyed. Like
     * the legacy web reader, keep the ink (it is in the document already; a stroke under way ends here), drop the dead
     * WebView and load the chapter again at the same place in a new one. A page that kills its renderer again and again
     * (twice within a minute) is not reloaded a third time: the pane stays blank with a message instead of looping.
     */
    private fun recoverRenderer() {
        if (released) return
        if (session != null) endPen(lastLocalX, lastLocalY, lastPoint?.tMs ?: 0L, 0f)
        val page = current
        val css = runCatching { scrollYCss() }.getOrDefault(0f)
        val zoom = zoomFactor()
        persistViewState()
        WebTimers.unregister(this)
        bridge.release()
        body.removeView(webView)
        runCatching { webView.destroy() }
        val now = SystemClock.uptimeMillis()
        while (rendererDeaths.isNotEmpty() && now - rendererDeaths.first() > RENDERER_DEATH_WINDOW_MS) rendererDeaths.removeFirst()
        rendererDeaths.addLast(now)
        webView = WebView(context)
        paneFrozen = false
        setUpWebView(webView)
        bridge = WebBridge(webView, this)
        body.addView(webView, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateFreeze()
        if (rendererDeaths.size > MAX_RENDERER_DEATHS) {
            Toast.makeText(context, R.string.web_page_crashed_again, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(context, R.string.web_page_crashed, Toast.LENGTH_SHORT).show()
        if (page != null) showChapter(page, null, css, zoom)
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
        pageBusy()      // a frozen WebView would not even parse the new page
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
        pageBusy()
        bridge.reset()
        bridge.inject(lockedWidthCss, docSettings.reduceMotion)
    }

    override fun onPageFinished(url: String) {
        pageLoaded?.complete(Unit)
        pendingFind?.let { q ->
            pendingFind = null
            main.postDelayed({ if (!released) webView.findAllAsync(q) }, FIND_AFTER_LOAD_MS)
        }
        pageBusy()      // freeze once it stays quiet: scripts (math, images) usually finish right after load
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
            recoverRenderer()
        }
    }

    // --- WebBridge.Listener ---

    override fun onScrolled() {
        if (!pageSettled) pageBusy()    // still loading/laying out: push the freeze back
        onContentMoved()
    }

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

    // ---------------------------------------------------------------------------------------------------------
    // Freezing the page while writing
    // ---------------------------------------------------------------------------------------------------------
    // A live page redraws itself (CSS/JS animations, widgets, script-driven DOM changes). Each redraw is a panel update that
    // competes with the pen driver, so the driver's ink trails the nib. Like e-ink readers that write on a static page,
    // the page is frozen ([WebTimers]) in Write mode once it has loaded and gone quiet; a finger on it, Interact mode, or a
    // new chapter wakes it.

    private var pageSettled = false
    private var paneFrozen = false
    private var thawUntil = 0L
    private val settleRunnable = Runnable { pageSettled = true; updateFreeze() }
    private val freezeCheck = Runnable { updateFreeze() }

    /** The page may change (loading, layout reports): wait for it to stay quiet before freezing again. */
    private fun pageBusy() {
        pageSettled = false
        main.removeCallbacks(settleRunnable)
        main.postDelayed(settleRunnable, SETTLE_MS)
        updateFreeze()
    }

    private fun thaw(forMs: Long) {
        thawUntil = max(thawUntil, SystemClock.uptimeMillis() + forMs)
        updateFreeze()
    }

    private fun updateFreeze() {
        if (released) return
        val now = SystemClock.uptimeMillis()
        val frozen = !interactive && !exporting && pageSettled && current != null && now >= thawUntil
        // Per pane: the page's own animations paused through the bridge, so a page freezes even while another web pane
        // (Interact mode, being touched) keeps the process-wide timers running. All panes frozen: WebTimers also pauses the
        // timers of every WebView (layout, parsing, JavaScript).
        // Not WebView.onPause(): Chromium then treats the page as hidden and drops its rendered tiles, so the finger that
        // thaws it (onResume) had to wait for the whole page to be drawn again before the scroll moved (scrolling lag).
        if (frozen != paneFrozen) {
            paneFrozen = frozen
            bridge.setFrozen(frozen)
        }
        WebTimers.want(this, frozen)
        main.removeCallbacks(freezeCheck)
        if (!frozen && now < thawUntil) main.postDelayed(freezeCheck, thawUntil - now)
    }

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
        var key: Any = vp
        if (cur != null) {
            // One page that always covers the content and everything visible, so any visible point belongs to it.
            val vis = vp.visibleDocRect()
            val pageW = max(max(cur.width, WebViewTransform.cssToPt(bridge.contentWidthCss)), vis.r)
            val pageH = max(WebViewTransform.cssToPt(max(bridge.contentHeightCss, webView.contentHeight.toFloat())), vis.b)
            key = listOf(vp, cur.id, pageW, pageH)
            if (key != syncedKey) layout = DocumentLayout(listOf(cur.copy(width = pageW, height = pageH)), PageLayoutMode.CONTINUOUS)
        }
        overlay.viewport = vp
        overlay.layout = layout
        // Pen-down calls this too: redrawing the overlay (and with it the WebView) for an unchanged view would put a window
        // frame on the panel just as the driver starts inking.
        if (key != syncedKey) {
            syncedKey = key
            overlay.invalidate()
        }
    }

    private var syncedKey: Any? = null

    private fun onContentMoved() {
        if (released) return
        val before = viewport
        syncViewport()
        // Only a real scroll or zoom pauses the pen driver. The bridge also reports when the content only grew (images,
        // fonts, script-driven DOM changes); pausing for those switched the driver off for 700 ms and refreshed the whole
        // pane while the user was writing, so the strokes in that window fell back to software ink.
        val vp = viewport
        if (vp.scrollX == before.scrollX && vp.scrollY == before.scrollY && vp.scale == before.scale) return
        beginFinger()
        scheduleEndFinger()
        main.removeCallbacks(saveViewRunnable)
        main.postDelayed(saveViewRunnable, VIEW_STATE_DELAY_MS)
    }

    private fun restoreScroll(final: Boolean) {
        if (exporting) return
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

    /** Strokes the driver has begun (debug HUD); which source inks a stroke is [penGate]'s call. */
    private var rawBegins = 0

    override fun onPen(ev: PenEvent) {
        if (released) return
        if (ev is PenEvent.Hover) {
            onHover(ev)
            return
        }
        // See NotebookPane.onPen: a touch-driven stroke goes on that way, and so does the first stroke after a focus switch.
        val touchStroke = session != null && !sessionViaDriver
        val driverStroke = session != null && sessionViaDriver
        val now = SystemClock.uptimeMillis()
        for (e in penGate.onTouch(ev, driverActive, touchStroke, driverStroke, now)) inkFromTouch(e)
        armPenGate()
    }

    /** Held touch events for a stroke the driver should have taken (see [PenSourceGate]); fires at the gate's deadline. */
    private val penGate = app.notate.core.input.PenSourceGate()
    private val penGateRunnable = Runnable {
        val evs = penGate.onTimeout(SystemClock.uptimeMillis())
        if (evs.isNotEmpty()) deps.logger.i(TAG, "driver missed a stroke: inked from touch (fallbacks=${penGate.fallbacks})")
        for (e in evs) inkFromTouch(e)
        armPenGate()
    }

    private fun armPenGate() {
        main.removeCallbacks(penGateRunnable)
        penGate.deadline?.let { main.postAtTime(penGateRunnable, it) }
    }

    private fun inkFromTouch(ev: PenEvent) {
        if (released) return
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
        val accepted = penGate.onRawBegin(SystemClock.uptimeMillis(), session != null && !sessionViaDriver)
        armPenGate()
        if (!accepted) return     // the touch events are already inking this stroke
        host.onDriverStroke(true, SystemClock.uptimeMillis())
        beginPen(p.x, p.y, p.pressure, p.tiltX, p.tiltY, p.tMs, eraser, viaDriver = true)
    }

    override fun onRawMove(batch: List<RawPoint>) {
        if (penGate.ignoringDriver || session == null || !sessionViaDriver || batch.isEmpty()) return
        val last = batch.last()
        movePen(batch.map { InkPoint(0f, 0f, it.pressure, it.tiltX, it.tiltY, it.tMs) }, last.x, last.y, batch.map { it.x to it.y })
    }

    override fun onRawEnd(p: RawPoint, eraser: Boolean) {
        if (!penGate.onRawEnd()) return
        if (session != null && !sessionViaDriver) return
        endPen(p.x, p.y, p.tMs, p.pressure)
        host.onDriverStroke(false, SystemClock.uptimeMillis())
    }

    override fun onInkSuspended(suspended: Boolean) {
        if (suspended && session != null && !sessionViaDriver) cancelPen()
    }

    override fun onDriverDeactivated() {
        // A stroke held for the driver is the touch events' now.
        main.removeCallbacks(penGateRunnable)
        for (e in penGate.flush()) inkFromTouch(e)
        // The driver was switched off mid-stroke and will never report its end: keep what was written.
        if (session != null && sessionViaDriver) endPen(lastLocalX, lastLocalY, lastPoint?.tMs ?: 0L, 0f)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pen: common. A pen event this pane accepted always reaches a tool session; nothing is dropped for being early.
    // ---------------------------------------------------------------------------------------------------------

    private fun beginPen(x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float, tMs: Long, eraser: Boolean, viaDriver: Boolean, lasso: Boolean = false) {
        if (session != null) cancelPen()
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
        if (viaDriver) inkedByDriver++ else inkedByTouch++
        val preset = deps.toolController.state.value.preset()
        val holdMs = deps.settings.holdShapeMs.value.takeIf { it > 0 }?.toLong()
        val ctx = ToolContext(
            editor = editor,
            page = page.id,
            pageBounds = null,
            pxPerPt = vp.scale,
            preview = live,
            settings = ToolSettings(holdMs, (preset.eraserRadiusDp ?: 0f) * density, scribbleErase = deps.settings.scribbleErase.value),
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
        // The result is in the page content already and the overlay draws it: the live picture can go. A stroke the driver
        // inked is on the panel already; the overlay catches up with the post-writing refresh (no frame between strokes).
        if (live.driverDraws) {
            live.releaseOverlaySilently()
            overlayStale = true
            deferOwnCommitUntil = SystemClock.uptimeMillis() + OWN_COMMIT_WINDOW_MS
        } else {
            live.releaseOverlay()
            overlay.invalidate()
        }
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
        refreshes++
        host.arbiter.block(this, BlockReason.REFRESH, true)
        if (overlayStale) {
            overlayStale = false
            overlay.invalidate()
        }
        deps.epd.afterDraw(overlay) {
            deps.epd.refresh(overlay, EpdMode.GU, intArrayOf(rect.left, rect.top, rect.right, rect.bottom), EpdTiming.NOW)
            host.arbiter.block(this, BlockReason.REFRESH, false)
        }
    }

    private fun onHover(ev: PenEvent.Hover) {
        // The pen is over the page right after a scroll: end the scroll's driver pause now instead of after the settle
        // delay, so the driver is back before the nib lands. Otherwise a stroke started inside that window is written
        // through touch events from start to end (the lag after scrolling).
        if (fingerBusy && !ev.exit) {
            main.removeCallbacks(endFingerRunnable)
            endFinger()
        }
        if (refreshRect == null || !refreshAfterDriver) return
        // While the pen hovers the user is still writing: the refresh pauses the driver, so a stroke started during it
        // would go through touch events and the ink would stall. Wait until the pen has been away for a while (as the legacy
        // canvas: 2 s after hover exit, cancelled by any hover). Lifting the pen between two words is a short hover exit, so
        // refreshing at once on exit paused the driver right under the next word's first stroke.
        if (ev.exit) armRefresh(HOVER_EXIT_REFRESH_MS) else armRefresh(HOVER_QUIET_MS)
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
        updateFreeze()
        val on = interactive
        host.arbiter.block(this, BlockReason.INTERACT_MODE, on)
        host.arbiter.block(this, BlockReason.READ_ONLY, docSettings.penMode == PenMode.READ_ONLY)
        bar.setMode(docSettings.penMode == PenMode.INTERACT)
        if (on && session != null) cancelPen()
        updateDriverInk()
    }

    private fun onMetaChanged() {
        thaw(SETTLE_MS)
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
        MenuEntry(context.getString(R.string.web_find_in_document)) { showFind() },
        MenuEntry(context.getString(R.string.web_export_annotations)) { showExportOptions() },
        MenuEntry(context.getString(R.string.web_compat_check)) { showCompatibility() },
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
    // Find in document (R4-3)
    // ---------------------------------------------------------------------------------------------------------

    private fun showFind() {
        findBar.visibility = View.VISIBLE
        findBar.focusField()
        if (findBar.query.isNotBlank()) webView.findAllAsync(findBar.query)
    }

    private fun closeFind() {
        findBar.visibility = View.GONE
        main.removeCallbacks(findRunnable)
        findJob?.cancel()
        pendingFind = null
        webView.clearMatches()
        host.dismissPopup()
    }

    private fun onFindQuery(q: String) {
        main.removeCallbacks(findRunnable)
        if (q.isBlank()) {
            webView.clearMatches()
            findBar.setCount(0, 0)
            return
        }
        webView.findAllAsync(q)
        if (findAllChapters) main.postDelayed(findRunnable, FIND_DEBOUNCE_MS)
    }

    private fun onFindAllChapters(on: Boolean) {
        findAllChapters = on
        if (on) runChapterSearch() else host.dismissPopup()
    }

    /** Searches the text of every chapter file (off the main thread) and lists the chapters that match. */
    private fun runChapterSearch() {
        val q = findBar.query.trim()
        if (q.isEmpty() || !findAllChapters) return
        findJob?.cancel()
        val list = chapters()
        val root = File(handle.dir, "assets/web")
        findJob = scope.launch {
            val hits = withContext(deps.dispatchers.io) {
                list.mapNotNull { ch ->
                    val file = WebResourcePaths.resolve(root, hrefOf(ch)) ?: return@mapNotNull null
                    if (file.length() > MAX_SEARCH_BYTES) return@mapNotNull null
                    val text = try { file.readText(Charsets.UTF_8) } catch (e: java.io.IOException) { return@mapNotNull null }
                    ChapterTextSearch.search(hrefOf(ch), text, q)?.let { ch to it }
                }
            }
            if (!released && findBar.visibility == View.VISIBLE) showFindResults(hits, q)
        }
    }

    private fun showFindResults(hits: List<Pair<PageInfo, app.notate.core.web.ChapterHits>>, q: String) {
        val col = PopupUi.column(context)
        if (hits.isEmpty()) col.addView(popupText(context.getString(R.string.web_find_results_none)))
        val all = chapters()
        for ((ch, h) in hits) {
            val index = all.indexOfFirst { it.id == ch.id }
            val name = outline.firstOrNull { it.page == ch.id }?.title?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.web_untitled_chapter, index + 1)
            val open = {
                host.dismissPopup()
                if (ch.id == current?.id) {
                    webView.findAllAsync(q)
                } else {
                    pendingFind = q
                    showChapter(ch, null, null)
                }
            }
            col.addView(
                PopupUi.button(context, context.getString(R.string.web_find_result_fmt, name, h.count), open).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    minWidth = context.dp(280)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(48)),
            )
            for (snippet in h.snippets) {
                col.addView(
                    PopupUi.text(context, snippet, 13f).apply {
                        setPadding(context.dp(16), context.dp(2), context.dp(8), context.dp(6))
                        setOnClickListener { open() }
                    },
                    LinearLayout.LayoutParams(context.dp(300), ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
        }
        host.showPopup(null, android.widget.ScrollView(context).apply { addView(col) }, Placement.CENTER)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Export (R4-3)
    // ---------------------------------------------------------------------------------------------------------

    private enum class ExportKind { PDF_CHAPTER, PDF_ALL, JSON }

    private fun showExportOptions() {
        val col = PopupUi.column(context)
        fun option(label: Int, kind: ExportKind) {
            col.addView(
                PopupUi.button(context, context.getString(label)) {
                    host.dismissPopup()
                    startExport(kind)
                }.apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    minWidth = context.dp(260)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(48)),
            )
        }
        option(R.string.web_export_pdf_chapter, ExportKind.PDF_CHAPTER)
        option(R.string.web_export_pdf_all, ExportKind.PDF_ALL)
        option(R.string.web_export_json, ExportKind.JSON)
        host.showPopup(null, col, Placement.CENTER)
    }

    private fun startExport(kind: ExportKind) {
        if (exportJob?.isActive == true || current == null) return
        val status = popupText(context.getString(R.string.web_export_preparing))
        val col = PopupUi.column(context)
        col.addView(status, LinearLayout.LayoutParams(context.dp(280), ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(
            PopupUi.button(context, context.getString(R.string.web_cancel)) {
                exportJob?.cancel()
                host.dismissPopup()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END },
        )
        host.showPopup(null, col, Placement.CENTER)
        exportJob = scope.launch {
            try {
                val file = try {
                    handle.save.flush()
                    when (kind) {
                        ExportKind.PDF_CHAPTER -> exportPdf(listOfNotNull(current), status, all = false)
                        ExportKind.PDF_ALL -> exportPdf(chaptersWithNotes(), status, all = true)
                        ExportKind.JSON -> exportJson()
                    }
                } finally {
                    host.dismissPopup()
                }
                if (file != null) share(file, if (kind == ExportKind.JSON) "application/json" else "application/pdf")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.logger.e(TAG, "Export failed", e)
                if (!released) Toast.makeText(context, R.string.web_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun chaptersWithNotes(): List<PageInfo> =
        chapters().filter { editor.ensureLoaded(it.id).size > 0 }

    private fun exportsDir(): File {
        val dir = File(context.filesDir, "notate/exports").also { it.mkdirs() }
        val cutoff = System.currentTimeMillis() - EXPORT_KEEP_MS
        dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        return dir
    }

    private fun exportName(suffix: String, ext: String): File =
        File(exportsDir(), WebExporter.safeFileName(editor.meta.value.title) + suffix + "." + ext)

    /** Renders [list] to a PDF as seen on screen. The pane is busy meanwhile and returns to where it was. */
    private suspend fun exportPdf(list: List<PageInfo>, status: TextView, all: Boolean): File? {
        if (list.isEmpty()) {
            Toast.makeText(context, R.string.web_export_nothing, Toast.LENGTH_SHORT).show()
            return null
        }
        if (overlay.width <= 0 || overlay.height <= 0) return null
        val origin = current
        val originCss = scrollYCss()
        val originZoom = zoomFactor()
        val out = exportName(if (all) "-notes" else "-" + (chapters().indexOfFirst { it.id == list[0].id } + 1), "pdf")
        persistViewState()
        exporting = true
        host.arbiter.block(this, BlockReason.EXPORT, true)
        try {
            WebExporter(exportHost, deps.dispatchers.io).exportPdf(list, editor.meta.value.title, out) { done, total ->
                status.text = context.getString(R.string.web_export_progress, (done + 1).coerceAtMost(total), total)
            }
            return out
        } finally {
            host.arbiter.block(this, BlockReason.EXPORT, false)
            viewStateLocked = true
            exporting = false
            pageLoaded = null
            if (origin != null && !released) showChapter(origin, null, originCss, originZoom)
        }
    }

    private val exportHost = object : WebExporter.Host {
        override suspend fun load(page: PageInfo): WebExporter.Geometry? {
            val loaded = CompletableDeferred<Unit>()
            pageLoaded = loaded
            showChapter(page, null, null)
            if (withTimeoutOrNull(LOAD_TIMEOUT_MS) { loaded.await() } == null) return null
            pageLoaded = null
            try {
                editor.ensureLoaded(page.id)
            } catch (e: Exception) {
                deps.logger.e(TAG, "Loading chapter ink failed", e)
            }
            // The page counts as laid out once two reports 500 ms apart agree (scripts keep growing some pages), at most 5 s.
            var previous = -1
            var waited = 0L
            while (waited < STABLE_MAX_MS) {
                delay(STABLE_INTERVAL_MS)
                waited += STABLE_INTERVAL_MS
                val now = contentHeightPx()
                if (WebExportPlan.heightStable(previous, now)) break
                previous = now
            }
            val pxPerPt = transform().pxPerPt
            if (webView.width <= 0 || webView.height <= 0 || pxPerPt <= 0f) return null
            return WebExporter.Geometry(contentHeightPx(), webView.width, webView.height, pxPerPt)
        }

        override suspend fun capture(page: PageInfo, scrollYPx: Int): WebExporter.Capture? {
            val scale = webScale()
            bridge.scrollToCss(scrollYPx / scale, scale)
            awaitVisualState()
            delay(SCROLL_SETTLE_MS)
            syncViewport()
            val w = webView.width
            val h = webView.height
            if (w <= 0 || h <= 0) return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            webView.draw(canvas)
            overlay.drawContent(canvas, viewport, page.id)
            return WebExporter.Capture(bmp, transform().scrollYPx.roundToInt())
        }
    }

    private fun contentHeightPx(): Int = (max(bridge.contentHeightCss, webView.contentHeight.toFloat()) * webScale()).roundToInt()

    private suspend fun awaitVisualState() {
        withTimeoutOrNull(VISUAL_STATE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                webView.postVisualStateCallback(nextVisualState++, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            }
        }
    }

    /** Notes data: every chapter that has handwriting, with the contents list, as JSON. */
    private suspend fun exportJson(): File? {
        val notes = ArrayList<ChapterNotes>()
        for (ch in chapters()) {
            val content = editor.ensureLoaded(ch.id)
            if (content.size > 0) notes += ChapterNotes(hrefOf(ch), (ch.background as Background.Web).layoutWidthCss, content.all().toList())
        }
        if (notes.isEmpty()) {
            Toast.makeText(context, R.string.web_export_nothing, Toast.LENGTH_SHORT).show()
            return null
        }
        val depth = HashMap<Long, Int>()
        val toc = outline.map { e ->
            val d = (e.parentId?.let { depth[it] } ?: -1) + 1
            depth[e.id] = d
            val href = chapters().firstOrNull { it.id == e.page }?.let { hrefOf(it) + (e.anchor?.let { a -> "#$a" } ?: "") }
            NotesToc(e.title, href, d)
        }
        val json = WebNotesJson.build(editor.meta.value.title, toc, notes)
        val out = exportName("-notes", "json")
        withContext(deps.dispatchers.io) { out.writeText(json) }
        return out
    }

    private fun share(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.web_export_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    // ---------------------------------------------------------------------------------------------------------
    // Compatibility check and diagnostics (R4-3)
    // ---------------------------------------------------------------------------------------------------------

    private fun showCompatibility() {
        scope.launch {
            val web = docSettings
            val pkg = WebView.getCurrentWebViewPackage()
            fun used(bit: Int) = when {
                !web.javascript -> "n/a (JavaScript off)"
                (bridge.features and bit) != 0 -> "used"
                else -> "not used"
            }
            val lines = ArrayList<String>()
            lines += "WebView: " + (pkg?.let { it.packageName + " " + it.versionName } ?: "unknown")
            lines += "JavaScript: " + if (web.javascript) "on" else "off"
            lines += "Network: " + if (web.network) "on" else "off"
            lines += "Service Worker: " + used(WebBridge.FEATURE_SERVICE_WORKER)
            lines += "WebGL: " + used(WebBridge.FEATURE_WEBGL)
            lines += "WebAssembly: " + used(WebBridge.FEATURE_WASM)
            lines += "ES modules: " + used(WebBridge.FEATURE_MODULES)
            lines += "Blocked external requests: ${client.blockedRequests}"
            val failed = client.failedResources
            lines += "Missing resources: ${failed.size}"
            failed.forEach { lines += "  $it" }
            if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) lines += inkDiagnostics()
            val col = PopupUi.column(context)
            for (l in lines) {
                col.addView(PopupUi.text(context, l, 14f), LinearLayout.LayoutParams(context.dp(320), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            col.addView(
                PopupUi.button(context, context.getString(R.string.web_compat_close)) { host.dismissPopup() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END },
            )
            host.showPopup(null, android.widget.ScrollView(context).apply { addView(col) }, Placement.CENTER)
        }
    }

    /** Debug builds only: what the ink layer holds for this chapter and how it is wired. */
    private suspend fun inkDiagnostics(): List<String> {
        val page = current ?: return listOf("Ink diagnostics: no chapter")
        val inMemory = editor.content(page.id)?.size ?: 0
        val onDisk = try {
            withContext(deps.dispatchers.io) { handle.countStoredItems(page.id) }
        } catch (e: Exception) {
            -1
        }
        val a = host.arbiter
        val reasons = BlockReason.entries.filter { a.isBlockedBy(it) || a.isBlockedBy(this, it) }
        val bg = page.background as Background.Web
        val saved = if (lastSaveAt == 0L) "never" else "${(deps.clock.now() - lastSaveAt) / 1000} s ago"
        return listOf(
            "— Ink diagnostics —",
            "Objects in memory: $inMemory, on disk: $onDisk",
            "Save queue: ${handle.save.state.value}, last clean $saved",
            "Driver: active=${a.isDriverActive(this)} suspended=${a.isSuspended(this)} blocked=${reasons.joinToString().ifEmpty { "-" }}",
            "webScale=${"%.2f".format(webScale())} layoutWidthCss=${bg.layoutWidthCss.roundToInt()} pane=${(overlay.width / density).roundToInt()} css px",
            "layoutGen=${bg.layoutGen}",
        )
    }

    // ---------------------------------------------------------------------------------------------------------
    // View state, lifecycle
    // ---------------------------------------------------------------------------------------------------------

    override fun saveViewState(): ViewState? {
        val page = current ?: return null
        if (viewStateLocked || exporting || overlay.width == 0) return null
        val t = transform()
        return ViewState(
            page.id, zoomFactor(), WebViewTransform.cssToPt(t.scrollXPx / t.scale), WebViewTransform.cssToPt(t.scrollYPx / t.scale),
            PageLayoutMode.CONTINUOUS,
        )
    }

    /** Writes the reading position into the metadata (not undoable; the save queue merges these writes). */
    private fun persistViewState() {
        main.removeCallbacks(saveViewRunnable)
        if (exporting) return
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

    private fun clearFrame(holder: android.view.SurfaceHolder) {
        val c = runCatching { holder.lockCanvas() }.getOrNull() ?: return
        try {
            c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        } finally {
            runCatching { holder.unlockCanvasAndPost(c) }
        }
    }

    override fun debugLines(): List<String> {
        val a = host.arbiter
        val reasons = BlockReason.entries.filter { a.isBlockedBy(it) || a.isBlockedBy(this, it) }
        val t = transform()
        return listOf(
            "owner=${a.isOwner(this)} active=${a.isDriverActive(this)} suspended=${a.isSuspended(this)}",
            "blocked=${reasons.joinToString().ifEmpty { "-" }}",
            "driver: ${driver.debugStatus()}",
            "web scale=${"%.2f".format(t.scale)} scroll=${t.scrollXPx.roundToInt()},${t.scrollYPx.roundToInt()} layout=${lockedWidthCss.roundToInt()}css",
            "pen view=${if (penSurface != null) "surface" else "overlay"} mode=${docSettings.penMode} js=${docSettings.javascript} net=${docSettings.network}",
            "inked: driver=$inkedByDriver touch=$inkedByTouch refreshes=$refreshes frozen=pane:$paneFrozen all:${WebTimers.isPaused} settled=$pageSettled",
            "pen source: driver strokes=$rawBegins trusted=${penGate.trusted} fallbacks=${penGate.fallbacks}",
        )
    }

    override fun release() {
        if (released) return
        persistViewState()
        cancelPen()
        released = true
        main.removeCallbacksAndMessages(null)
        exportJob?.cancel()
        findJob?.cancel()
        deps.epd.releaseFast(FAST_OWNER)
        deps.epd.releaseFast(INK_OWNER)
        host.arbiter.detach(this)
        WebTimers.unregister(this)
        bridge.release()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }

    private companion object {
        const val TAG = "WebPane"
        const val MAX_RENDERER_DEATHS = 2
        const val RENDERER_DEATH_WINDOW_MS = 60_000L
        const val POLL_MS = 50L
        /** After a driver stroke with the pen still hovering (or no hover reported): wait this long before the refresh. */
        const val HOVER_QUIET_MS = 3000L
        /** After the pen left the screen: the legacy canvas's delay before the post-writing refresh. */
        const val HOVER_EXIT_REFRESH_MS = 2000L
        /** A content change this soon after a driver stroke's pen-up is that stroke's own commit. */
        const val OWN_COMMIT_WINDOW_MS = 300L
        const val FINGER_SETTLE_MS = 700L
        const val VIEW_STATE_DELAY_MS = 1000L
        const val RESTORE_FIRST_MS = 150L
        const val RESTORE_FINAL_MS = 800L
        /** The page must stay quiet this long after loading before it is frozen for writing. */
        const val SETTLE_MS = 1500L
        /** A finger on the page keeps it live this long after the last touch. */
        const val FINGER_THAW_MS = 5000L
        const val RELOAD_WIDTH_FRACTION = 0.05f
        const val FIND_DEBOUNCE_MS = 400L
        const val FIND_AFTER_LOAD_MS = 400L
        const val MAX_SEARCH_BYTES = 8L * 1024 * 1024
        const val EXPORT_KEEP_MS = 7L * 24 * 3600 * 1000
        const val LOAD_TIMEOUT_MS = 15_000L
        const val STABLE_INTERVAL_MS = 500L
        const val STABLE_MAX_MS = 5_000L
        const val SCROLL_SETTLE_MS = 80L
        const val VISUAL_STATE_TIMEOUT_MS = 1_000L
        const val BOUNDS_PAD_DP = 6f
        const val DEFAULT_WIDTH_PT = 1.134f
        const val FAST_OWNER = "web-pane-finger"
        const val INK_OWNER = "web-pane-ink"
        val WRITING_TOOLS = setOf(ToolKind.PEN, ToolKind.HIGHLIGHTER, ToolKind.ERASER, ToolKind.SHAPE)
    }
}
