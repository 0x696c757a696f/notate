package com.alexdremov.notate.webdoc

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.alexdremov.notate.BuildConfig
import com.alexdremov.notate.R
import com.alexdremov.notate.model.ActionType
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.model.ToolbarItem
import com.alexdremov.notate.ui.dialog.PenSettingsPopup
import com.alexdremov.notate.ui.input.PalmGuard
import com.alexdremov.notate.ui.toolbar.MainToolbar
import com.alexdremov.notate.util.EpdFastModeController
import com.alexdremov.notate.vm.DrawingViewModel
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.OnyxSystemHelper
import com.alexdremov.notate.util.UriUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.alexdremov.notate.NotePickerActivity
import com.alexdremov.notate.ui.split.SplitDrag
import com.alexdremov.notate.ui.split.SplitPaneLayout
import com.alexdremov.notate.ui.split.SplitPartnerPicker
import com.alexdremov.notate.ui.tabs.DocKind
import com.alexdremov.notate.ui.tabs.DocTab
import com.alexdremov.notate.ui.tabs.TabBarView
import com.alexdremov.notate.ui.tabs.SplitOrientation
import com.alexdremov.notate.ui.tabs.TabNavigator
import com.alexdremov.notate.ui.tabs.WorkspaceStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Reader for interactive EPUB 3 books and offline HTML5 documents, with handwritten notes on top.
 *
 * The page runs in a sandboxed WebView (scripts, ES modules, Canvas, SVG, MathML, iframes,
 * localStorage/IndexedDB) served from a private per-document origin; ink lives in
 * [WebInkOverlayView] and is anchored to document coordinates.
 */
class WebDocActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "WebDocActivity"
        const val EXTRA_DOC_ID = "WEBDOC_ID"
        const val EXTRA_IS_FOLDER = "WEBDOC_IS_FOLDER"
        private const val PREFS = "webdoc"
        private const val PREF_NATIVE_INK = "native_ink"

        /** Chromium versions below this miss parts of ES2020+/MathML Core that interactive books use. */
        const val MIN_RECOMMENDED_WEBVIEW = 109

        fun library(context: Context) = WebDocLibrary(java.io.File(context.filesDir, "webdocs"))

        fun openIntent(
            context: Context,
            docId: String,
        ) = Intent(context, WebDocActivity::class.java).putExtra(EXTRA_DOC_ID, docId)

        fun importIntent(
            context: Context,
            uri: Uri,
            isFolder: Boolean,
        ) = Intent(context, WebDocActivity::class.java)
            .setData(uri)
            .putExtra(EXTRA_IS_FOLDER, isFolder)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        const val REDUCE_MOTION_JS =
            "(function(){if(document.getElementById('__notate_reduce_motion'))return;" +
                "var s=document.createElement('style');s.id='__notate_reduce_motion';" +
                // Near-zero instead of 'none' so transitionend/animationend still fire for page scripts.
                "s.textContent='*,*::before,*::after{animation-duration:0.001s!important;animation-delay:0s!important;" +
                "animation-iteration-count:1!important;transition-duration:0.001s!important;transition-delay:0s!important;" +
                "scroll-behavior:auto!important;caret-color:transparent}';" +
                "(document.head||document.documentElement).appendChild(s);})();"

        private const val FEATURE_PROBE_JS =
            "(function(){var r={};" +
                "r.esModules='noModule' in document.createElement('script');" +
                "try{new Function('a?.b??c');r.es2020=true}catch(e){r.es2020=false}" +
                "r.mathml=typeof MathMLElement!=='undefined';" +
                "r.canvas2d=!!document.createElement('canvas').getContext('2d');" +
                "r.webgl=!!document.createElement('canvas').getContext('webgl');" +
                "r.svg=!!document.createElementNS&&!!document.createElementNS('http://www.w3.org/2000/svg','svg').createSVGRect;" +
                "try{localStorage.setItem('__n','1');localStorage.removeItem('__n');r.localStorage=true}catch(e){r.localStorage=false}" +
                "r.indexedDB=!!window.indexedDB;r.wasm=typeof WebAssembly==='object';" +
                "r.pointerEvents=!!window.PointerEvent;r.ua=navigator.userAgent;return JSON.stringify(r)})()"
    }

    private lateinit var library: WebDocLibrary
    private var meta: WebDocMeta? = null
    private var annotations = WebDocAnnotations()
    private val inkPages = HashMap<String, WebInkPage>()
    private val scrollY = HashMap<String, Float>()
    private var currentHref: String? = null
    private var pendingScrollRestore: String? = null
    private var saveJob: Job? = null
    private var dirty = false

    private lateinit var webView: WebView
    private lateinit var content: FrameLayout
    private var rawInput: WebInkRawInput? = null
    private var fingerPaused = false
    private var fingerDown = false
    private var rejectingPalm = false
    private var gestureOnPage = false
    private val palmGuard by lazy { PalmGuard(PalmGuard.PALM_MIN_SIZE_MM / 25.4f * resources.displayMetrics.xdpi) }
    private var focusPaused = false
    private val fingerResume = Runnable { setFingerPaused(false) }
    private var pageLoaded: CompletableDeferred<String>? = null
    private var exporting = false
    private var pendingExport: (suspend (Uri) -> Unit)? = null
    private val createPdf =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> runPendingExport(uri) }
    private val createJson =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> runPendingExport(uri) }
    private lateinit var overlay: WebInkOverlayView
    private lateinit var epd: WebDocEpd
    private lateinit var toolbar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var modeButton: TextView
    private var moreButton: View? = null

    // Same toolbar, pens and settings as note pages (shared through PreferencesManager).
    private val toolbarVm: DrawingViewModel by viewModels()
    private var activePenPopup: PenSettingsPopup? = null
    private var prevButton: TextView? = null
    private var tabBar: TabBarView? = null

    // Split view with a second book (book|note splits are hosted by the note editor).
    private lateinit var splitLayout: SplitPaneLayout
    private var partnerPane: BookPane? = null
    private var partnerId: String? = null
    private var partnerActive = false
    private var mainWriteMode = true
    private var pendingSplitOrientation = SplitOrientation.SIDE_BY_SIDE
    private val splitNotePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val path = result.data?.getStringExtra("NOTE_PATH")
            if (result.resultCode == RESULT_OK && path != null) {
                startSplit(DocTab(DocKind.NOTE, path, WorkspaceStore.titleFor(path)), pendingSplitOrientation)
            }
        }
    private var isSwitchingTabs = false
    private var nextButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = library(this)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val docId = intent.getStringExtra(EXTRA_DOC_ID)
        val uri = intent.data
        when {
            docId != null -> {
                val m = library.loadMeta(docId)
                if (m == null) {
                    WorkspaceStore.update(this) { it.close(docId) }
                    toast("Document not found or deleted")
                    finish()
                    return
                }
                // A book that is split with a note is shown by the note editor, whatever the
                // entry point (library tap, tab, deep link), so the split is always restored.
                val ws = WorkspaceStore.load(this)
                val host = TabNavigator.hostFor(ws, DocTab(DocKind.WEB, docId, m.title))
                if (host.kind == DocKind.NOTE) {
                    startActivity(TabNavigator.intentFor(this, host, ws))
                    finish()
                    overridePendingTransition(0, 0)
                    return
                }
                open(m)
            }
            uri != null -> importThenOpen(uri, intent.getBooleanExtra(EXTRA_IS_FOLDER, false))
            else -> finish()
        }

        onBackPressedDispatcher.addCallback(this) {
            if (::webView.isInitialized && meta?.kind == WebDocKind.HTML && webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
        }
    }

    // ---------------------------------------------------------------- import

    private fun importThenOpen(
        uri: Uri,
        isFolder: Boolean,
    ) {
        val progress =
            AlertDialog
                .Builder(this)
                .setMessage("Importing…")
                .setCancelable(false)
                .show()
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (isFolder) importFolder(uri) else importFile(uri)
                    }
                }
            progress.dismiss()
            result
                .onSuccess { open(it) }
                .onFailure {
                    Logger.e(TAG, "Import failed for $uri", it)
                    toast("Import failed: ${it.message}")
                    finish()
                }
        }
    }

    private fun importFile(uri: Uri): WebDocMeta {
        val name = UriUtils.getFileName(this, uri) ?: "document.html"
        return library.importFile(name, { contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot read $uri") })
    }

    private fun importFolder(treeUri: Uri): WebDocMeta {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: throw java.io.IOException("Cannot read folder")
        val files = ArrayList<Pair<String, () -> InputStream>>()

        fun walk(
            dir: DocumentFile,
            prefix: String,
        ) {
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                if (name.startsWith(".")) continue
                if (child.isDirectory) {
                    walk(child, "$prefix$name/")
                } else if (child.isFile) {
                    val childUri = child.uri
                    files.add("$prefix$name" to { contentResolver.openInputStream(childUri) ?: ByteArrayInputStream(ByteArray(0)) })
                }
            }
        }
        walk(root, "")
        return library.importFolder(root.name ?: "HTML", files.asSequence())
    }

    // ---------------------------------------------------------------- UI

    private fun open(m: WebDocMeta) {
        meta = m
        annotations = library.loadAnnotations(m.id)
        scrollY.putAll(annotations.scrollY)
        buildUi(m)
        applyPolicy(m.policy)
        checkWebViewVersion()
        restoreBookSplit(m.id)

        val start = annotations.lastPage?.takeIf { href -> m.kind == WebDocKind.HTML || m.pages.any { it.href == href } } ?: m.pages.first().href
        loadPage(start)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun buildUi(m: WebDocMeta) {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
            }

        toolbar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
        val toolbarScroll =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(toolbar)
            }
        root.addView(toolbarScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        // Tabs shared with the note editor; Home replaces the old Back button.
        val tabs =
            TabBarView(this).apply {
                splitButtonsVisible = true
                onHome = { finish() }
                onTabClick = { tab -> onTabSelected(tab) }
                onTabClose = { tab -> closeTab(tab) }
                onSplit = { orientation -> onSplitButton(orientation) }
                onSwapPanes = { swapPanes() }
                onExitSplit = { exitSplit() }
            }
        tabBar = tabs
        root.addView(tabs, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.tab_bar_height)))
        WorkspaceStore.update(this) { it.open(DocTab(DocKind.WEB, m.id, m.title)) }
        renderTabs()
        titleView =
            TextView(this).apply {
                text = m.title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.BLACK)
                maxLines = 1
                maxWidth = dp(260)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(12), 0)
            }
        toolbar.addView(titleView)

        if (m.kind == WebDocKind.EPUB) {
            toolbar.addView(button("Contents") { showToc() })
            prevButton = button("◀ Prev") { stepPage(-1) }.also { toolbar.addView(it) }
            nextButton = button("Next ▶") { stepPage(1) }.also { toolbar.addView(it) }
        }
        toolbar.addView(separator())

        modeButton = button("") { setWriteMode(!overlay.writeEnabled) }
        toolbar.addView(modeButton)
        toolbar.addView(button("Refresh screen") { epd.fullRefresh() })
        moreButton = button("⋯") { showMoreMenu(it) }.also { toolbar.addView(it) }

        // Second row: the note toolbar (pens, highlighter, eraser, Lines, Shapes, ruler/protractor, undo/redo).
        val inkToolbar =
            androidx.compose.ui.platform.ComposeView(this).apply {
                setViewCompositionStrategy(
                    androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                )
                setContent {
                    MainToolbar(
                        viewModel = toolbarVm,
                        isHorizontal = true,
                        canvasController = null,
                        canvasModel = null,
                        onToolClick = { item, rect ->
                            handleToolClick(
                                item,
                                android.graphics.Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()),
                            )
                        },
                        onActionClick = { handleAction(it) },
                        onOpenSidebar = { moreButton?.let { showMoreMenu(it) } },
                    )
                }
            }
        val inkToolbarScroll =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
                addView(
                    FrameLayout(this@WebDocActivity).apply {
                        setPadding(dp(4), 0, dp(4), dp(4))
                        addView(inkToolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
                    },
                )
            }
        // Below the tab bar and the reader toolbar.
        root.addView(inkToolbarScroll, 2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content = FrameLayout(this)
        webView =
            WebView(this).apply {
                setBackgroundColor(Color.WHITE)
                isVerticalScrollBarEnabled = true
            }
        overlay =
            WebInkOverlayView(
                this,
                object : WebInkOverlayView.Viewport {
                    override val scrollXPx get() = webView.scrollX
                    override val scrollYPx get() = webView.scrollY

                    @Suppress("DEPRECATION")
                    override val scale get() = webView.scale.takeIf { it > 0f } ?: resources.displayMetrics.density
                },
            )
        content.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        splitLayout = SplitPaneLayout(this)
        splitLayout.setPanes(content, null)
        splitLayout.fingerTapActivates = !com.alexdremov.notate.data.PreferencesManager.isPalmRejectionEnabled(this)
        splitLayout.onPaneTouched = { index -> setActivePane(partner = index != primarySlot()) }
        splitLayout.onDividerDragStateChanged = { dragging -> onDividerDrag(dragging) }
        splitLayout.onDragReleased = { outcome -> onDividerReleased(outcome) }
        splitLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> partnerPane?.setExclusionRects(emptyList()) }
        root.addView(splitLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        epd = WebDocEpd(webView)
        OnyxSystemHelper.ignoreSystemSideButton(overlay)
        // Native ink is drawn by the pen driver: switching the whole screen to fast mode (and redrawing
        // everything when leaving it) would only make the next stroke stutter. Software strokes need it.
        overlay.onStrokeStart = { native -> if (!native) epd.onInteractionStart() }
        overlay.onInkChanged = {
            epd.onInteractionEnd()
            inkChanged()
        }
        overlay.onShapeSnapped = { rawInput?.showOverlayNow() }
        overlay.onPenHover = {
            if (it.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
                palmGuard.onHoverExit(SystemClock.uptimeMillis())
            } else {
                palmGuard.onHover(SystemClock.uptimeMillis())
            }
            rawInput?.onHover(it)
            if (it.actionMasked != MotionEvent.ACTION_HOVER_EXIT) resumeForPen()
        }
        overlay.onGuidesMoved = { ended -> if (ended) epd.onInteractionEnd() else epd.onInteractionStart() }
        webView.setOnScrollChangeListener { _, _, _, _, _ ->
            overlay.invalidate()
            if (!overlay.isPenActive) epd.onContentMoved()
        }

        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> rawInput?.updateLimitRect() }

        configureWebView(m)
        setWriteMode(true)
        observeToolbar()
        overlay.post { openNativeInk() }
    }

    // ---------------------------------------------------------------- Onyx native pen channel

    private fun nativeInkPreferred() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_NATIVE_INK, true)

    private fun openNativeInk() {
        if (rawInput != null || !::overlay.isInitialized || !nativeInkPreferred() || isFinishing || partnerActive) return
        rawInput = WebInkRawInput.createOrNull(overlay)
        overlay.nativeInput = rawInput != null
        rawInput?.let { raw ->
            raw.setEnabled(overlay.writeEnabled)
            if (fingerPaused) raw.pause()
            if (focusPaused || exporting) raw.pause()
        }
    }

    private fun closeNativeInk() {
        rawInput?.close()
        rawInput = null
        if (::overlay.isInitialized) overlay.nativeInput = false
    }

    /**
     * The pen came into hover range after a finger scroll: hand the screen back to the pen now
     * instead of [WebDocEpd.IDLE_DELAY_MS] after the finger lifted, which could be mid-stroke.
     */
    private fun resumeForPen() {
        if (!fingerPaused || fingerDown) return
        overlay.removeCallbacks(fingerResume)
        epd.settle()
        setFingerPaused(false)
    }

    private fun setFingerPaused(paused: Boolean) {
        if (paused == fingerPaused) return
        fingerPaused = paused
        if (paused) rawInput?.pause() else rawInput?.resume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and menus take focus; without this the pen would draw over them instead of tapping them.
        val pause = !hasFocus
        if (pause == focusPaused) return
        focusPaused = pause
        if (pause) rawInput?.pause() else rawInput?.resume()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::overlay.isInitialized) {
            // Palm rejection: while the pen is writing, a resting hand must not scroll the page under the ink.
            val finger = ev.getToolType(ev.actionIndex) == MotionEvent.TOOL_TYPE_FINGER
            if (finger && rejectPalm(ev)) return true
            if (exporting) return true
            // Pen strokes drawn by the driver must not toggle the screen's refresh mode.
            val nativePen = !finger && rawInput != null && overlay.writeEnabled && !overlay.needsSoftwareInput
            if (!nativePen) when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    epd.onInteractionStart()
                    if (finger) {
                        fingerDown = true
                        // The page must be able to redraw while the finger scrolls or drags.
                        overlay.removeCallbacks(fingerResume)
                        setFingerPaused(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (finger) fingerDown = false
                    onGestureEnd()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun onGestureEnd() {
        epd.onInteractionEnd()
        if (fingerPaused) {
            overlay.removeCallbacks(fingerResume)
            overlay.postDelayed(fingerResume, WebDocEpd.IDLE_DELAY_MS)
        }
    }

    /**
     * Palm rejection on the page: a finger gesture made while the pen hovers or writes, or one as large
     * as a palm, is the writing hand and must not scroll the page. Fingers elsewhere (toolbar) still work.
     * A scroll already under way when the pen arrives is cancelled.
     */
    private fun rejectPalm(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            rejectingPalm = false
            val r = android.graphics.Rect()
            content.getGlobalVisibleRect(r)
            gestureOnPage = r.contains(ev.rawX.toInt(), ev.rawY.toInt())
        }
        if (!rejectingPalm && gestureOnPage) {
            val sizes = FloatArray(ev.pointerCount) { ev.getTouchMajor(it) }
            if (overlay.isPenActive || palmGuard.isPenNear(SystemClock.uptimeMillis()) || palmGuard.isPalmSized(sizes)) {
                rejectingPalm = true
                if (action != MotionEvent.ACTION_DOWN) {
                    val cancel = MotionEvent.obtain(ev).apply { setAction(MotionEvent.ACTION_CANCEL) }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    fingerDown = false
                    onGestureEnd()
                    resumeForPen()
                }
            }
        }
        if (!rejectingPalm) return false
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) rejectingPalm = false
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(m: WebDocMeta) {
        val host = WebDocResources.hostFor(m.id)
        val contentRoot = library.contentDir(m.id)

        WebDocSandbox.configure(webView.settings)

        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url
                    if (url.host == host) return WebDocSandbox.serveLocal(contentRoot, url, request.method)
                    val scheme = url.scheme ?: ""
                    if (scheme == "data" || scheme == "blob") return null
                    return if (meta?.policy?.networkAllowed == true) null else WebDocSandbox.blocked()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url
                    if (url.host == host) return false
                    if (!request.isForMainFrame) return meta?.policy?.networkAllowed != true
                    if (meta?.policy?.networkAllowed == true && (url.scheme == "http" || url.scheme == "https")) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, url))
                        } catch (e: ActivityNotFoundException) {
                            toast("No app can open this link")
                        }
                    } else {
                        toast("External link blocked (allow network in ⋯)")
                    }
                    return true
                }

                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?,
                ) {
                    val m = meta ?: return
                    val href = WebDocResources.hrefForUrl(m.id, url) ?: return
                    if (href != currentHref) switchInkPage(href)
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    val m = meta ?: return
                    if (m.policy.reduceMotion && m.policy.javaScriptEnabled) view.evaluateJavascript(REDUCE_MOTION_JS, null)
                    val href = WebDocResources.hrefForUrl(m.id, url)
                    if (href != null && href == pendingScrollRestore) {
                        pendingScrollRestore = null
                        val y = scrollY[href] ?: 0f
                        if (y > 0f) {
                            // Give scripts/fonts a moment to finish layout before restoring the position.
                            view.postDelayed({ view.scrollTo(0, (y * currentScale()).roundToInt()) }, 300)
                        }
                    }
                    updateNavButtons()
                    epd.onContentMoved()
                    if (href != null) pageLoaded?.complete(href)
                }

                override fun onScaleChanged(
                    view: WebView,
                    oldScale: Float,
                    newScale: Float,
                ) {
                    overlay.invalidate()
                    rawInput?.updateTool()
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    // A heavy or hostile document must not take the whole app down.
                    Logger.e(TAG, "WebView renderer gone (crash=${detail.didCrash()})")
                    saveNow()
                    toast("Document renderer exited; notes were saved")
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    finish()
                    return true
                }
            }

        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback,
                ) = callback.invoke(origin, false, false)

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (BuildConfig.DEBUG) Logger.d(TAG, "JS ${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    return true
                }
            }
    }

    private fun applyPolicy(policy: WebDocPolicy) = WebDocSandbox.applyPolicy(webView.settings, policy)

    private fun updatePolicy(transform: (WebDocPolicy) -> WebDocPolicy) {
        val m = meta ?: return
        val updated = m.copy(policy = transform(m.policy))
        meta = updated
        lifecycleScope.launch(Dispatchers.IO) { library.saveMeta(updated) }
        applyPolicy(updated.policy)
        rememberScroll()
        currentHref?.let { pendingScrollRestore = it }
        webView.reload()
    }

    private fun showMoreMenu(anchor: View) {
        val m = meta ?: return
        val popup = PopupMenu(this, anchor)
        popup.menu
            .add(0, 1, 0, "Enable JavaScript")
            .setCheckable(true)
            .setChecked(m.policy.javaScriptEnabled)
        popup.menu
            .add(0, 2, 1, "Allow network")
            .setCheckable(true)
            .setChecked(m.policy.networkAllowed)
        popup.menu
            .add(0, 3, 2, "Reduce motion (E Ink)")
            .setCheckable(true)
            .setChecked(m.policy.reduceMotion)
        popup.menu
            .add(0, 7, 3, "Boox native ink (low latency)")
            .setCheckable(true)
            .setChecked(rawInput != null)
        popup.menu.add(0, 8, 4, "Export annotations…")
        popup.menu.add(0, 4, 5, "Clear notes on this page")
        popup.menu.add(0, 5, 6, "Compatibility check")
        popup.menu.add(0, 6, 7, "Clear data saved by the page")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> updatePolicy { it.copy(javaScriptEnabled = !it.javaScriptEnabled) }
                2 -> confirmNetworkToggle()
                3 -> updatePolicy { it.copy(reduceMotion = !it.reduceMotion) }
                4 ->
                    AlertDialog
                        .Builder(this)
                        .setMessage("Clear all handwritten notes on this page? (Can be undone)")
                        .setPositiveButton("Clear") { _, _ ->
                            currentInk()?.clear()
                            inkChanged()
                        }.setNegativeButton("Cancel", null)
                        .show()
                5 -> showCompatibility()
                7 -> toggleNativeInk()
                8 -> showExportDialog()
                6 ->
                    AlertDialog
                        .Builder(this)
                        .setMessage("Clear exercise answers and progress this document saved in localStorage / IndexedDB? Handwritten notes are not affected.")
                        .setPositiveButton("Clear") { _, _ ->
                            WebStorage.getInstance().deleteOrigin(WebDocResources.originFor(m.id))
                            webView.reload()
                        }.setNegativeButton("Cancel", null)
                        .show()
            }
            true
        }
        popup.show()
    }

    private fun toggleNativeInk() {
        val enable = rawInput == null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_NATIVE_INK, enable).apply()
        if (enable) {
            openNativeInk()
            if (rawInput == null) toast("Native Boox ink is not supported on this device; using regular drawing")
        } else {
            closeNativeInk()
        }
    }

    // ---------------------------------------------------------------- export

    private fun showExportDialog() {
        val m = meta ?: return
        val annotated = annotatedHrefs()
        val sectionWord = if (m.kind == WebDocKind.EPUB) "section" else "page"
        val options =
            arrayOf<CharSequence>(
                "PDF: current $sectionWord (page + annotations)",
                "PDF: all annotated ${sectionWord}s (${annotated.size})",
                "Notes data (JSON)",
            )
        AlertDialog
            .Builder(this)
            .setTitle("Export annotations")
            .setSingleChoiceItems(options, -1) { dialog, which ->
                dialog.dismiss()
                val base = safeName(m.title)
                when (which) {
                    0 -> {
                        val href = currentHref ?: return@setSingleChoiceItems
                        pendingExport = { uri -> exportPdf(listOf(href), uri) }
                        createPdf.launch("$base-annotations.pdf")
                    }
                    1 -> {
                        if (annotated.isEmpty()) {
                            toast("No annotations yet")
                            return@setSingleChoiceItems
                        }
                        pendingExport = { uri -> exportPdf(annotated, uri) }
                        createPdf.launch("$base-all-annotations.pdf")
                    }
                    2 -> {
                        pendingExport = { uri -> exportJson(uri) }
                        createJson.launch("$base-notes.json")
                    }
                }
            }.setNegativeButton("Cancel", null)
            .show()
    }

    /** Pages with ink, in reading order (spine order for EPUB, then any other HTML pages). */
    private fun annotatedHrefs(): List<String> {
        val m = meta ?: return emptyList()
        val withInk = snapshotAnnotations().strokes.filterValues { it.isNotEmpty() }.keys
        val ordered = m.pages.map { it.href }.filter { it in withInk }
        return ordered + (withInk - ordered.toSet()).sorted()
    }

    private fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "notes" }

    private fun runPendingExport(uri: Uri?) {
        val job = pendingExport
        pendingExport = null
        if (uri == null || job == null) return
        lifecycleScope.launch { job(uri) }
    }

    private suspend fun exportJson(uri: Uri) {
        val m = meta ?: return
        val bundle =
            WebDocNotesExport(
                title = m.title,
                kind = m.kind,
                sourceName = m.sourceName,
                pages = m.pages,
                annotations = snapshotAnnotations(),
            )
        val text = WebDocLibrary.json.encodeToString(WebDocNotesExport.serializer(), bundle)
        val ok =
            withContext(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
            }
        toast(if (ok.isSuccess) "Notes data exported" else "Export failed: ${ok.exceptionOrNull()?.message}")
    }

    /**
     * Renders each page as it looks on screen (scripts, canvas, MathML included) with its ink,
     * one PDF page per screen-height slice. Capturing what the WebView actually painted keeps
     * the ink aligned with the content it was written on.
     */
    private suspend fun exportPdf(
        hrefs: List<String>,
        uri: Uri,
    ) {
        val startHref = currentHref
        rememberScroll()
        exporting = true
        rawInput?.pause()
        val progress =
            AlertDialog
                .Builder(this)
                .setMessage("Exporting…")
                .setCancelable(false)
                .show()
        val pdf = PdfDocument()
        var pageNo = 0
        var error: Throwable? = null
        try {
            val w = content.width
            val h = content.height
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            for ((i, href) in hrefs.withIndex()) {
                progress.setMessage("Exporting ${i + 1}/${hrefs.size}…")
                if (href != currentHref || webView.progress < 100) loadPageAndWait(href)
                val contentPx = (webView.contentHeight * currentScale()).roundToInt()
                for (slice in WebDocExportPlan.slices(contentPx, h)) {
                    webView.scrollTo(0, slice.scrollY)
                    delay(300) // let Chromium raster the newly exposed area
                    bitmap.eraseColor(Color.WHITE)
                    content.draw(canvas)
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, slice.height, ++pageNo).create())
                    page.canvas.drawBitmap(
                        bitmap,
                        android.graphics.Rect(0, slice.srcTop, w, slice.srcTop + slice.height),
                        android.graphics.Rect(0, 0, w, slice.height),
                        null,
                    )
                    pdf.finishPage(page)
                }
            }
            bitmap.recycle()
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri, "wt")!!.use { pdf.writeTo(it) }
            }
        } catch (t: Throwable) {
            error = t
            Logger.e(TAG, "PDF export failed", t)
        } finally {
            pdf.close()
            progress.dismiss()
            if (startHref != null) {
                if (startHref != currentHref) {
                    loadPage(startHref)
                } else {
                    webView.scrollTo(0, ((scrollY[startHref] ?: 0f) * currentScale()).roundToInt())
                }
            }
            exporting = false
            rawInput?.resume()
        }
        toast(if (error == null) "Exported $pageNo-page PDF" else "Export failed: ${error.message}")
    }

    private suspend fun loadPageAndWait(href: String) {
        val wait = CompletableDeferred<String>()
        pageLoaded = wait
        loadPage(href)
        // A redirect inside the document finishes on another href; export whatever was loaded.
        withTimeoutOrNull(15_000) { wait.await() }
        pageLoaded = null
        delay(800) // scripts, fonts and MathJax/KaTeX typesetting
        webView.scrollTo(0, 0)
    }

    private fun confirmNetworkToggle() {
        val m = meta ?: return
        if (m.policy.networkAllowed) {
            updatePolicy { it.copy(networkAllowed = false) }
            return
        }
        AlertDialog
            .Builder(this)
            .setTitle("Allow this document network access?")
            .setMessage("Scripts in the document will be able to reach the internet (e.g. load web fonts, a MathJax CDN, or send data). Only enable this for trusted documents.")
            .setPositiveButton("Allow") { _, _ -> updatePolicy { it.copy(networkAllowed = true) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun webViewMajorVersion(): Pair<String, Int?> {
        val pkg = WebView.getCurrentWebViewPackage()
        val version = pkg?.versionName ?: "unknown"
        return "${pkg?.packageName ?: "WebView"} $version" to version.substringBefore('.').toIntOrNull()
    }

    private fun checkWebViewVersion() {
        val (_, major) = webViewMajorVersion()
        if (major != null && major < MIN_RECOMMENDED_WEBVIEW) {
            toast("System WebView is outdated ($major); some interactive content or formulas may not display correctly")
        }
    }

    private fun showCompatibility() {
        val (label, major) = webViewMajorVersion()
        val m = meta ?: return
        val base =
            buildString {
                appendLine("WebView: $label")
                if (major != null && major < MIN_RECOMMENDED_WEBVIEW) appendLine("⚠ Version $MIN_RECOMMENDED_WEBVIEW or newer recommended")
                appendLine("Document type: ${if (m.kind == WebDocKind.EPUB) "EPUB" + if (m.fixedLayout) " (fixed layout)" else " (reflowable)" else "HTML"}")
                appendLine("JavaScript: ${if (m.policy.javaScriptEnabled) "on" else "off"}  Network: ${if (m.policy.networkAllowed) "allowed" else "blocked"}")
            }
        if (!m.policy.javaScriptEnabled) {
            showText("Compatibility check", base + "\nEnable JavaScript to detect more features.")
            return
        }
        webView.evaluateJavascript(FEATURE_PROBE_JS) { raw ->
            val text =
                try {
                    val obj = JSONObject(JSONObject("{\"v\":$raw}").getString("v"))
                    val names =
                        linkedMapOf(
                            "esModules" to "ES Modules",
                            "es2020" to "ES2020 syntax",
                            "mathml" to "MathML",
                            "canvas2d" to "Canvas 2D",
                            "webgl" to "WebGL",
                            "svg" to "SVG",
                            "localStorage" to "localStorage",
                            "indexedDB" to "IndexedDB",
                            "wasm" to "WebAssembly",
                            "pointerEvents" to "Pointer Events",
                        )
                    base + "\n" + names.entries.joinToString("\n") { (k, v) -> (if (obj.optBoolean(k)) "✓ " else "✗ ") + v }
                } catch (e: Exception) {
                    base + "\nFeature detection failed: ${e.message}"
                }
            showText("Compatibility check", text)
        }
    }

    private fun showText(
        title: String,
        text: String,
    ) {
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------------------------------------------------------------- pages & ink

    private fun currentScale(): Float {
        @Suppress("DEPRECATION")
        return webView.scale.takeIf { it > 0f } ?: resources.displayMetrics.density
    }

    private fun loadPage(href: String) {
        val m = meta ?: return
        rememberScroll()
        switchInkPage(href)
        pendingScrollRestore = href
        webView.loadUrl(WebDocResources.urlFor(m.id, href))
    }

    private fun switchInkPage(href: String) {
        rememberScroll()
        currentHref = href
        overlay.page = inkPages.getOrPut(href) { WebInkPage(annotations.strokes[href].orEmpty()) }
        updateNavButtons()
    }

    private fun currentInk(): WebInkPage? = currentHref?.let { inkPages[it] }

    private fun rememberScroll() {
        val href = currentHref ?: return
        if (!::webView.isInitialized) return
        scrollY[href] = webView.scrollY / currentScale()
    }

    private fun stepPage(delta: Int) {
        val m = meta ?: return
        val index = m.pages.indexOfFirst { it.href == currentHref }
        val target = (if (index < 0) 0 else index + delta)
        if (target !in m.pages.indices) return
        loadPage(m.pages[target].href)
    }

    private fun showToc() {
        val m = meta ?: return
        val current = m.pages.indexOfFirst { it.href == currentHref }
        AlertDialog
            .Builder(this)
            .setTitle("Contents")
            .setSingleChoiceItems(m.pages.map { it.title }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                loadPage(m.pages[which].href)
            }.setNegativeButton("Close", null)
            .show()
    }

    private fun updateNavButtons() {
        val m = meta ?: return
        val index = m.pages.indexOfFirst { it.href == currentHref }
        prevButton?.isEnabled = index > 0
        prevButton?.alpha = if (index > 0) 1f else 0.35f
        nextButton?.isEnabled = index >= 0 && index < m.pages.size - 1
        nextButton?.alpha = if (nextButton?.isEnabled == true) 1f else 0.35f
        if (m.kind == WebDocKind.EPUB && index >= 0) titleView.text = "${m.title} · ${m.pages[index].title}"
    }

    private fun setWriteMode(write: Boolean) {
        mainWriteMode = write
        if (!partnerActive) {
            overlay.writeEnabled = write
            rawInput?.setEnabled(write)
        }
        modeButton.text = if (write) "✎ Pen: write" else "☝ Pen: interact"
        styleButton(modeButton, write)
    }

    // ---------------------------------------------------------------- toolbar (shared with note pages)

    private fun observeToolbar() {
        lifecycleScope.launch {
            toolbarVm.activeTool.collect { tool ->
                overlay.tool = tool
                rawInput?.updateTool()
                partnerPane?.setTool(tool)
            }
        }
        lifecycleScope.launch {
            toolbarVm.currentEraser.collect {
                overlay.sideEraser = it
                partnerPane?.setEraser(it)
            }
        }
    }

    private fun handleToolClick(
        item: ToolbarItem,
        anchor: android.graphics.Rect,
    ) {
        val unsupported =
            when (item) {
                is ToolbarItem.Pen -> if (item.penTool.type == ToolType.TEXT) "Text boxes are only available on note pages" else null
                is ToolbarItem.Select -> "Selection is only available on note pages"
                else -> null
            }
        if (unsupported != null) {
            toast(unsupported)
            return
        }
        if (!partnerActive && !overlay.writeEnabled) setWriteMode(true)

        // Shape and line buttons open their picker on the first tap, like on note pages.
        val opensOnFirstTap = item is ToolbarItem.Pen && item.penTool.isShapeTool
        if (toolbarVm.activeToolId.value != item.id) {
            toolbarVm.selectTool(item.id)
            if (!opensOnFirstTap) return
        }
        val tool =
            when (item) {
                is ToolbarItem.Pen -> item.penTool
                is ToolbarItem.Eraser -> item.penTool
                else -> null
            } ?: return
        showPenSettings(tool, anchor)
    }

    private fun showPenSettings(
        tool: com.alexdremov.notate.model.PenTool,
        anchor: android.graphics.Rect,
    ) {
        activePenPopup?.dismiss()
        rawInput?.pause()
        if (partnerActive) partnerPane?.setInkAllowed(false)
        val popup =
            PenSettingsPopup(
                this,
                tool,
                onUpdate = { updated -> toolbarVm.updateTool(updated) },
                onRemove = { removed -> toolbarVm.removePen(removed.id) },
                onDismiss = {
                    EpdFastModeController.exitFastMode()
                    activePenPopup = null
                    toolbarVm.setPenPopupOpen(false)
                    rawInput?.resume()
                    partnerPane?.setInkAllowed(true)
                },
            )
        activePenPopup = popup
        toolbarVm.setPenPopupOpen(true)
        EpdFastModeController.enterFastMode()
        popup.show(window.decorView, anchor)
    }

    private fun handleAction(action: ActionType) {
        when (action) {
            ActionType.UNDO -> if (partnerActive) partnerPane?.undo() else if (currentInk()?.undo() == true) inkChanged()
            ActionType.REDO -> if (partnerActive) partnerPane?.redo() else if (currentInk()?.redo() == true) inkChanged()
            ActionType.GUIDES -> if (partnerActive) toast("The ruler is only available in the main book") else showGuidesPicker()
            ActionType.INSERT_IMAGE -> toast("Inserting is only available on note pages")
            ActionType.ZOOM_WINDOW -> toast("The zoom window is only available on note pages")
        }
    }

    /** Ruler / protractor picker, same as on note pages: icon toggles, no text. */
    private fun showGuidesPicker() {
        val guides = overlay.guides
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
        val dialog =
            AlertDialog
                .Builder(this)
                .setView(row)
                .setOnDismissListener {
                    // Guides need the software preview: this switches the driver's ink off or back on.
                    rawInput?.updateTool()
                    epd.fullRefresh()
                }.create()

        fun toggle(
            iconRes: Int,
            description: String,
            isOn: () -> Boolean,
            set: (Boolean) -> Unit,
        ) = android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            setBackgroundResource(R.drawable.bg_tool_selected)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isSelected = isOn()
            layoutParams =
                LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
            setOnClickListener {
                set(!isOn())
                this@WebDocActivity.overlay.invalidate()
                dialog.dismiss()
            }
        }

        row.addView(
            toggle(R.drawable.ic_guide_ruler, "Ruler", { guides.isRulerVisible }) {
                guides.setRulerVisible(it, overlay.width, overlay.height)
            },
        )
        row.addView(
            toggle(R.drawable.ic_guide_protractor, "Protractor", { guides.isProtractorVisible }) {
                guides.setProtractorVisible(it, overlay.width, overlay.height)
            },
        )
        dialog.show()
    }

    private fun inkChanged() {
        overlay.notifyPageChanged()
        dirty = true
        saveJob?.cancel()
        saveJob =
            lifecycleScope.launch {
                delay(1000)
                saveNow()
            }
    }

    private fun snapshotAnnotations(): WebDocAnnotations {
        rememberScroll()
        val strokes = HashMap(annotations.strokes)
        for ((href, page) in inkPages) {
            if (page.strokes.isEmpty()) strokes.remove(href) else strokes[href] = page.strokes.toList()
        }
        return WebDocAnnotations(strokes = strokes, lastPage = currentHref, scrollY = HashMap(scrollY))
    }

    private fun saveNow() {
        val m = meta ?: return
        annotations = snapshotAnnotations()
        try {
            library.saveAnnotations(m.id, annotations)
            dirty = false
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save annotations", e)
            toast("Failed to save notes: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        if (meta != null && ::webView.isInitialized) {
            saveJob?.cancel()
            saveNow()
            webView.onPause()
        }
        closeNativeInk()
        partnerPane?.onHostPause()
    }

    // ---------------------------------------------------------------- tabs

    private fun renderTabs() {
        val id = meta?.id ?: return
        val ws = WorkspaceStore.load(this)
        val partner = if (partnerPane != null) partnerId else null
        tabBar?.render(
            ws.tabs,
            if (partnerActive && partner != null) partner else id,
            setOfNotNull(id, partner),
            if (partner != null) ws.splitFor(id) else null,
        )
    }

    private fun onTabSelected(tab: DocTab) {
        when (tab.path) {
            meta?.id -> setActivePane(partner = false)
            partnerId -> setActivePane(partner = true)
            else -> switchToTab(tab)
        }
    }

    /** Opens another tab (note or book); this reader saves in onPause. */
    private fun switchToTab(tab: DocTab) {
        if (tab.path == meta?.id || isSwitchingTabs) return
        isSwitchingTabs = true
        WorkspaceStore.update(this) { it.activate(tab.path) }
        startActivity(TabNavigator.intentFor(this, tab))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun closeTab(tab: DocTab) {
        val id = meta?.id
        if (tab.path == partnerId && partnerPane != null) {
            exitSplit()
            WorkspaceStore.update(this) { it.close(tab.path) }
            renderTabs()
            return
        }
        val workspace = WorkspaceStore.update(this) { it.close(tab.path) }
        if (tab.path != id) {
            renderTabs()
            return
        }
        // Closing the main book: the split partner (if any) takes over, else the active neighbour.
        val next = partnerId?.let { workspace.tab(it) } ?: workspace.activePath?.let { workspace.tab(it) }
        if (next != null) switchToTab(next) else finish()
    }

    // ---------------------------------------------------------------- split view (book | book)

    private fun primarySlot(): Int {
        val id = meta?.id ?: return 0
        val split = WorkspaceStore.load(this).splitFor(id) ?: return 0
        return if (split.first == id) 0 else 1
    }

    private fun layoutPanes() {
        val id = meta?.id ?: return
        val pane = partnerPane
        val split = WorkspaceStore.load(this).splitFor(id)
        if (pane == null || split == null) {
            splitLayout.setPanes(content, null)
        } else {
            if (primarySlot() == 0) splitLayout.setPanes(content, pane) else splitLayout.setPanes(pane, content)
            splitLayout.orientation = split.orientation
            splitLayout.ratio = split.ratio
            splitLayout.activeIndex = if (partnerActive) 1 - primarySlot() else primarySlot()
        }
        splitLayout.post { partnerPane?.setExclusionRects(emptyList()) }
        renderTabs()
    }

    private fun restoreBookSplit(id: String) {
        val ws = WorkspaceStore.load(this)
        val partner = ws.splitFor(id)?.partnerOf(id)?.let { ws.tab(it) } ?: return
        if (partner.kind != DocKind.WEB) return
        splitLayout.post {
            if (showBookPartner(partner)) layoutPanes()
        }
    }

    /** Creates the second book pane; false (after cleaning up the workspace) if the book is gone. */
    private fun showBookPartner(tab: DocTab): Boolean {
        val id = meta?.id ?: return false
        val partnerMeta = library.loadMeta(tab.path)
        if (partnerMeta == null) {
            toast("Book not found or deleted")
            WorkspaceStore.update(this) { it.unsplit(id).close(tab.path) }
            renderTabs()
            return false
        }
        val pane = BookPane(this, partnerMeta, library)
        pane.setTool(toolbarVm.activeTool.value)
        pane.setEraser(toolbarVm.currentEraser.value)
        pane.onStrokeStarted = {
            activePenPopup?.dismiss()
            activePenPopup = null
        }
        partnerPane = pane
        partnerId = tab.path
        return true
    }

    private fun releasePartner() {
        val pane = partnerPane
        partnerPane = null
        partnerId = null
        splitLayout.setPanes(content, null)
        pane?.release()
    }

    /** Moves the pen driver, toolbar and undo to the other book. Only one pane owns the pen. */
    private fun setActivePane(partner: Boolean) {
        val pane = partnerPane
        val wantPartner = partner && pane != null
        if (wantPartner == partnerActive) return
        partnerActive = wantPartner
        if (wantPartner) {
            // Release the main driver first: two raw-drawing sessions fight over the digitizer.
            closeNativeInk()
            overlay.writeEnabled = false
            pane?.setPaneActive(true)
        } else {
            pane?.setPaneActive(false)
            overlay.writeEnabled = mainWriteMode
            openNativeInk()
            rawInput?.setEnabled(mainWriteMode)
        }
        splitLayout.activeIndex = if (partnerActive) 1 - primarySlot() else primarySlot()
        val active = if (partnerActive) partnerId else meta?.id
        if (active != null) WorkspaceStore.update(this) { it.activate(active) }
        renderTabs()
    }

    private fun onSplitButton(orientation: SplitOrientation) {
        val id = meta?.id ?: return
        val ws = WorkspaceStore.load(this)
        val split = ws.splitFor(id)
        if (split != null && partnerPane != null) {
            if (split.orientation != orientation) {
                WorkspaceStore.update(this) { it.updateSplit(id) { s -> s.copy(orientation = orientation) } }
                layoutPanes()
            }
            return
        }
        pendingSplitOrientation = orientation
        SplitPartnerPicker.show(
            this,
            ws,
            id,
            orientation,
            onBrowseNotes = { splitNotePicker.launch(Intent(this, NotePickerActivity::class.java)) },
            onPicked = { startSplit(it, orientation) },
        )
    }

    private fun startSplit(
        other: DocTab,
        orientation: SplitOrientation,
    ) {
        val id = meta?.id ?: return
        if (other.path == id) {
            toast("This book is already open")
            return
        }
        if (partnerPane != null) {
            setActivePane(partner = false)
            releasePartner()
        }
        val title = if (other.kind == DocKind.NOTE) WorkspaceStore.titleFor(other.path) else other.title
        val tab = other.copy(title = title)
        WorkspaceStore.update(this) { it.open(tab).split(id, tab.path, orientation) }
        if (tab.kind == DocKind.NOTE) {
            // A split with a note is hosted by the note editor (book beside the note).
            if (isSwitchingTabs) return
            isSwitchingTabs = true
            saveNow()
            startActivity(TabNavigator.intentFor(this, tab))
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }
        if (!showBookPartner(tab)) return
        layoutPanes()
        // Like Notability: the document just opened beside gets the pen.
        setActivePane(partner = true)
    }

    private fun swapPanes() {
        val id = meta?.id ?: return
        WorkspaceStore.update(this) { it.updateSplit(id) { s -> s.copy(first = s.second, second = s.first, ratio = 1f - s.ratio) } }
        layoutPanes()
    }

    private fun exitSplit() {
        val id = meta?.id ?: return
        setActivePane(partner = false)
        releasePartner()
        WorkspaceStore.update(this) { it.unsplit(id) }
        layoutPanes()
    }

    private fun onDividerDrag(dragging: Boolean) {
        partnerPane?.setInkAllowed(!dragging)
        if (dragging) {
            rawInput?.pause()
            com.onyx.android.sdk.api.device.EpdDeviceManager
                .enterAnimationUpdate(true)
        } else {
            com.onyx.android.sdk.api.device.EpdDeviceManager
                .exitAnimationUpdate(true)
            if (!fingerPaused && !focusPaused && !exporting) rawInput?.resume()
        }
    }

    private fun onDividerReleased(outcome: SplitDrag.Outcome) {
        val id = meta?.id ?: return
        when (outcome) {
            is SplitDrag.Outcome.Resize ->
                WorkspaceStore.update(this) { it.updateSplit(id) { s -> s.copy(ratio = outcome.ratio) } }
            is SplitDrag.Outcome.Collapse -> {
                if (outcome.keepIndex == primarySlot()) {
                    exitSplit()
                } else {
                    // The main book collapsed: the partner stays, alone.
                    val ws = WorkspaceStore.update(this) { it.unsplit(id) }
                    partnerId?.let { p -> ws.tab(p)?.let { switchToTab(it) } }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderTabs()
        if (::webView.isInitialized) {
            webView.onResume()
            overlay.post { openNativeInk() }
        }
        partnerPane?.onHostResume()
    }

    override fun onDestroy() {
        partnerPane?.release()
        partnerPane = null
        closeNativeInk()
        if (::epd.isInitialized) epd.release()
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun button(
        label: String,
        onClick: (View) -> Unit,
    ): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            minHeight = dp(40)
            setOnClickListener { onClick(it) }
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(4)
                }
            styleButton(this, false)
        }

    private fun styleButton(
        b: TextView,
        selected: Boolean,
    ) {
        b.background =
            GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (selected) Color.BLACK else Color.WHITE)
                setStroke(dp(1), Color.BLACK)
            }
        b.setTextColor(if (selected) Color.WHITE else Color.BLACK)
    }

    private fun separator() =
        View(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams =
                LinearLayout.LayoutParams(dp(1), dp(28)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(8)
                }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
