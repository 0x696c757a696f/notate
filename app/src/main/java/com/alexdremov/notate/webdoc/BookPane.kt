package com.alexdremov.notate.webdoc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.ui.input.PalmGuard
import com.alexdremov.notate.util.Logger
import kotlin.math.roundToInt

/**
 * An EPUB/HTML book inside a split-view pane, next to a note: page navigation and handwritten
 * notes on the page, saved to the same annotations as the full reader (which keeps export,
 * policies and the ruler).
 *
 * Like a note pane, it owns the Onyx pen driver only while focused ([setPaneActive]).
 */
@SuppressLint("ViewConstructor")
class BookPane(
    context: Context,
    val meta: WebDocMeta,
    private val library: WebDocLibrary,
) : LinearLayout(context) {
    companion object {
        private const val TAG = "BookPane"
        private const val SAVE_DELAY_MS = 1000L
    }

    private var annotations = library.loadAnnotations(meta.id)
    private val inkPages = HashMap<String, WebInkPage>()
    private val scrollY = HashMap(annotations.scrollY)
    private var currentHref: String? = null
    private var pendingScrollRestore: String? = null
    private var dirty = false

    private val webView = WebView(context).apply { setBackgroundColor(Color.WHITE) }
    private val overlay =
        WebInkOverlayView(
            context,
            object : WebInkOverlayView.Viewport {
                override val scrollXPx get() = webView.scrollX
                override val scrollYPx get() = webView.scrollY
                override val scale get() = currentScale()
            },
        )
    private val content = FrameLayout(context)
    private val epd = WebDocEpd(webView)
    private val titleView: TextView
    private val prevButton: TextView
    private val nextButton: TextView

    private var rawInput: WebInkRawInput? = null
    private var isActive = false
    private var inkAllowed = true
    private var fingerPaused = false
    private var rejectingPalm = false
    private var downX = 0f
    private var downY = 0f
    private val palmGuard =
        PalmGuard(
            PalmGuard.PALM_MIN_SIZE_MM / 25.4f * resources.displayMetrics.xdpi,
            pxPerCm = resources.displayMetrics.xdpi / 2.54f,
        ).also { it.handedness = com.alexdremov.notate.data.PreferencesManager.getHandedness(context) }
    private var exclusionRects: List<Rect> = emptyList()
    private val fingerResume = Runnable { setFingerPaused(false) }
    private val saveRunnable = Runnable { saveNow() }

    /** Called when the pen starts a stroke here (e.g. to close the pen settings popup). */
    var onStrokeStarted: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)

        val header =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(4), 0)
            }
        titleView =
            TextView(context).apply {
                text = meta.title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.BLACK)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        header.addView(titleView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        prevButton = headerButton("◀") { stepPage(-1) }
        nextButton = headerButton("▶") { stepPage(1) }
        if (meta.kind == WebDocKind.EPUB) {
            header.addView(headerButton("Contents") { showToc() })
            header.addView(prevButton)
            header.addView(nextButton)
        }
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        addView(View(context).apply { setBackgroundColor(Color.BLACK) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        content.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        overlay.onStrokeStart = { native ->
            if (!native) epd.onInteractionStart()
            onStrokeStarted?.invoke()
        }
        overlay.onInkChanged = {
            epd.onInteractionEnd()
            inkChanged()
        }
        overlay.onShapeSnapped = { rawInput?.showOverlayNow() }
        overlay.onPenHover = {
            if (it.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
                palmGuard.onHoverExit(SystemClock.uptimeMillis())
            } else {
                palmGuard.onHover(SystemClock.uptimeMillis(), it.rawX, it.rawY)
                if (fingerPaused) {
                    removeCallbacks(fingerResume)
                    epd.settle()
                    setFingerPaused(false)
                }
            }
            rawInput?.onHover(it)
        }
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> rawInput?.updateLimitRect() }
        webView.setOnScrollChangeListener { _, _, _, _, _ ->
            overlay.invalidate()
            if (!overlay.isPenActive) epd.onContentMoved()
        }

        // Not focused yet: the pen only taps until this pane gets focus.
        overlay.writeEnabled = false
        configureWebView()
        val start =
            annotations.lastPage?.takeIf { href -> meta.kind == WebDocKind.HTML || meta.pages.any { it.href == href } }
                ?: meta.pages.first().href
        loadPage(start)
    }

    // ---------------------------------------------------------------- pen

    fun setTool(tool: PenTool) {
        overlay.tool = tool
        rawInput?.updateTool()
    }

    fun setEraser(tool: PenTool?) {
        overlay.sideEraser = tool
    }

    /** Split view: only the focused pane holds the pen driver. */
    fun setPaneActive(active: Boolean) {
        if (isActive == active) return
        isActive = active
        overlay.writeEnabled = active
        if (active) openNativeInk() else closeNativeInk()
    }

    /** The editor's ink gate (popups, sidebar, divider drag...). */
    fun setInkAllowed(allowed: Boolean) {
        if (inkAllowed == allowed) return
        inkAllowed = allowed
        if (allowed) rawInput?.resume() else rawInput?.pause()
    }

    /** Screen rectangles (e.g. the floating toolbar) where the pen must not ink. */
    fun setExclusionRects(rects: List<Rect>) {
        val origin = IntArray(2)
        overlay.getLocationOnScreen(origin)
        exclusionRects = rects.filter { !it.isEmpty }.map { Rect(it).apply { offset(-origin[0], -origin[1]) } }
        rawInput?.exclusionRects = exclusionRects
    }

    private fun openNativeInk() {
        if (rawInput != null) return
        overlay.post {
            if (!isActive || rawInput != null || !isAttachedToWindow) return@post
            rawInput = WebInkRawInput.createOrNull(overlay)?.also { it.onPenCut = { palmGuard.reset() } }
            overlay.nativeInput = rawInput != null
            rawInput?.let { raw ->
                raw.exclusionRects = exclusionRects
                if (!inkAllowed) raw.pause()
                if (fingerPaused) raw.pause()
            }
        }
    }

    private fun closeNativeInk() {
        rawInput?.close()
        rawInput = null
        overlay.nativeInput = false
    }

    private fun setFingerPaused(paused: Boolean) {
        if (paused == fingerPaused) return
        fingerPaused = paused
        if (paused) rawInput?.pause() else rawInput?.resume()
    }

    fun undo() {
        if (currentInk()?.undo() == true) inkChanged()
    }

    fun redo() {
        if (currentInk()?.redo() == true) inkChanged()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val finger = ev.getToolType(ev.actionIndex) == MotionEvent.TOOL_TYPE_FINGER
        if (finger && rejectPalm(ev)) return true
        val nativePen = !finger && rawInput != null && overlay.writeEnabled && !overlay.needsSoftwareInput
        if (!nativePen) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    epd.onInteractionStart()
                    if (finger) {
                        // The page must be able to redraw while the finger scrolls.
                        removeCallbacks(fingerResume)
                        setFingerPaused(true)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    epd.onInteractionEnd()
                    if (fingerPaused) {
                        removeCallbacks(fingerResume)
                        postDelayed(fingerResume, WebDocEpd.IDLE_DELAY_MS)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** A finger gesture while the pen hovers or writes, or one palm-sized, is the writing hand: ignore it. */
    private fun fingerOff() = com.alexdremov.notate.data.PreferencesManager.isFingerOff(context)

    private fun rejectPalm(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            rejectingPalm = false
            downX = ev.rawX
            downY = ev.rawY
            palmGuard.observeFingerDown(ev.getTouchMajor(0))
        }
        if (!rejectingPalm) {
            val sizes = FloatArray(ev.pointerCount) { ev.getTouchMajor(it) }
            if (fingerOff() || overlay.isPenActive || palmGuard.shouldReject(SystemClock.uptimeMillis(), downX, downY) || palmGuard.isPalmSized(sizes)) {
                rejectingPalm = true
                if (action != MotionEvent.ACTION_DOWN) {
                    val cancel = MotionEvent.obtain(ev).apply { setAction(MotionEvent.ACTION_CANCEL) }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
        }
        if (!rejectingPalm) return false
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) rejectingPalm = false
        return true
    }

    // ---------------------------------------------------------------- pages

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val host = WebDocResources.hostFor(meta.id)
        val contentRoot = library.contentDir(meta.id)
        WebDocSandbox.configure(webView.settings)
        WebDocSandbox.applyPolicy(webView.settings, meta.policy)
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
                    return if (meta.policy.networkAllowed) null else WebDocSandbox.blocked()
                }

                // Leaving the book (external links) is only possible from the full reader.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = request.url.host != host

                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?,
                ) {
                    val href = WebDocResources.hrefForUrl(meta.id, url) ?: return
                    if (href != currentHref) switchInkPage(href)
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    if (meta.policy.reduceMotion && meta.policy.javaScriptEnabled) {
                        view.evaluateJavascript(WebDocActivity.REDUCE_MOTION_JS, null)
                    }
                    val href = WebDocResources.hrefForUrl(meta.id, url)
                    if (href != null && href == pendingScrollRestore) {
                        pendingScrollRestore = null
                        val y = scrollY[href] ?: 0f
                        if (y > 0f) view.postDelayed({ view.scrollTo(0, (y * currentScale()).roundToInt()) }, 300)
                    }
                    updateNavButtons()
                    epd.onContentMoved()
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
                    Logger.e(TAG, "WebView renderer gone (crash=${detail.didCrash()})", showToUser = true)
                    saveNow()
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    return true
                }
            }
    }

    private fun currentScale(): Float {
        @Suppress("DEPRECATION")
        return webView.scale.takeIf { it > 0f } ?: resources.displayMetrics.density
    }

    private fun loadPage(href: String) {
        rememberScroll()
        switchInkPage(href)
        pendingScrollRestore = href
        webView.loadUrl(WebDocResources.urlFor(meta.id, href))
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
        scrollY[href] = webView.scrollY / currentScale()
    }

    private fun stepPage(delta: Int) {
        val index = meta.pages.indexOfFirst { it.href == currentHref }
        val target = if (index < 0) 0 else index + delta
        if (target in meta.pages.indices) loadPage(meta.pages[target].href)
    }

    private fun showToc() {
        val current = meta.pages.indexOfFirst { it.href == currentHref }
        AlertDialog
            .Builder(context)
            .setTitle("Contents")
            .setSingleChoiceItems(meta.pages.map { it.title }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                loadPage(meta.pages[which].href)
            }.setNegativeButton("Close", null)
            .show()
    }

    private fun updateNavButtons() {
        val index = meta.pages.indexOfFirst { it.href == currentHref }
        prevButton.isEnabled = index > 0
        prevButton.alpha = if (prevButton.isEnabled) 1f else 0.35f
        nextButton.isEnabled = index >= 0 && index < meta.pages.size - 1
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.35f
        if (meta.kind == WebDocKind.EPUB && index >= 0) titleView.text = "${meta.title} · ${meta.pages[index].title}"
    }

    // ---------------------------------------------------------------- saving

    private fun inkChanged() {
        overlay.notifyPageChanged()
        dirty = true
        removeCallbacks(saveRunnable)
        postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    /** Writes notes, last page and scroll positions; main thread. */
    fun saveNow() {
        removeCallbacks(saveRunnable)
        rememberScroll()
        val strokes = HashMap(annotations.strokes)
        for ((href, page) in inkPages) {
            if (page.strokes.isEmpty()) strokes.remove(href) else strokes[href] = page.strokes.toList()
        }
        annotations = WebDocAnnotations(strokes = strokes, lastPage = currentHref, scrollY = HashMap(scrollY))
        try {
            library.saveAnnotations(meta.id, annotations)
            dirty = false
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save book notes", e, showToUser = true)
        }
    }

    fun onHostPause() {
        saveNow()
        webView.onPause()
        closeNativeInk()
    }

    fun onHostResume() {
        webView.onResume()
        if (isActive) openNativeInk()
    }

    /** Saves and frees the WebView; the pane cannot be used afterwards. */
    fun release() {
        saveNow()
        closeNativeInk()
        epd.release()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun headerButton(
        label: String,
        onClick: () -> Unit,
    ) = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        minWidth = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
