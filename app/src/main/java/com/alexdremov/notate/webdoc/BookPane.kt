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
import com.alexdremov.notate.input.InputClassifier
import com.alexdremov.notate.input.PalmRejector
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
    /** Shared by every pane of the window, so a hovering pen protects all of them. */
    private val palmRejector: PalmRejector = InputClassifier.newRejector(context),
) : LinearLayout(context) {
    companion object {
        private const val TAG = "BookPane"
        private const val SAVE_DELAY_MS = 1000L
    }

    private val store = WebAnnotationStore.acquire(library, meta.id)
    private var released = false
    private var currentHref: String? = null
    private var pendingScrollRestore: String? = null

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
    private val driftBanner = WebLayoutDriftBanner(context)
    private val driftCheck = Runnable { checkLayoutDrift() }
    private val epd = WebDocEpd(webView)
    private val titleView: TextView
    private val prevButton: TextView
    private val nextButton: TextView
    private val writeTab: TextView
    private val interactTab: TextView

    private var rawInput: WebInkRawInput? = null
    private var isActive = false
    private var inkAllowed = true
    private var fingerPaused = false
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
        // No menu in the pane header: long-press the title for the ink diagnostics.
        titleView.setOnLongClickListener {
            showInkDiagnostics()
            true
        }
        header.addView(titleView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        writeTab = modeTab("✎ Write") { setPenMode(PenMode.WRITE) }
        interactTab = modeTab("☝ Interact") { setPenMode(PenMode.INTERACT) }
        header.addView(writeTab)
        header.addView(interactTab)
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
        driftBanner.attachTo(content)
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
                palmRejector.onHoverExit(SystemClock.uptimeMillis())
            } else {
                palmRejector.onHover(SystemClock.uptimeMillis(), it.rawX, it.rawY)
                if (fingerPaused) {
                    removeCallbacks(fingerResume)
                    webView.flingScroll(0, 0) // stop a fling still running under the pen
                    epd.settle()
                    setFingerPaused(false)
                }
            }
            rawInput?.onHover(it)
        }
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            rawInput?.updateLimitRect()
            scheduleDriftCheck()
        }
        webView.setOnScrollChangeListener { _, _, _, _, _ ->
            overlay.invalidate()
            // A fling that outlives the finger must not put the panel back in fast mode once the pen has
            // taken over: that refresh would land under the next strokes (the lag right after scrolling).
            if (!overlay.isPenActive && (fingerPaused || !overlay.driverLive)) epd.onContentMoved()
        }

        // Not focused yet: the pen only taps until this pane gets focus.
        applyPenMode()
        configureWebView()
        val start =
            store.lastPage?.takeIf { href -> meta.kind == WebDocKind.HTML || meta.pages.any { it.href == href } }
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

    /** Write / Interact of this pane's book (saved with the book). */
    val penMode: PenMode get() = store.penMode

    /** Called after the mode changed, so the host can refresh its own button. */
    var onPenModeChanged: ((PenMode) -> Unit)? = null

    fun setPenMode(mode: PenMode) {
        if (released) return
        val changed = penMode != mode
        store.penMode = mode
        applyPenMode()
        if (changed) {
            removeCallbacks(saveRunnable)
            postDelayed(saveRunnable, SAVE_DELAY_MS)
            onPenModeChanged?.invoke(mode)
        }
    }

    fun togglePenMode() = setPenMode(penMode.toggled())

    /** True when the pen writes here right now: focused and in Write mode. */
    val writeEnabled: Boolean get() = overlay.writeEnabled

    private fun applyPenMode() {
        val write = isActive && penMode == PenMode.WRITE
        overlay.writeEnabled = write
        rawInput?.setEnabled(write)
        styleModeTab(writeTab, penMode == PenMode.WRITE)
        styleModeTab(interactTab, penMode == PenMode.INTERACT)
    }

    /** Split view: only the focused pane holds the pen driver. */
    fun setPaneActive(active: Boolean) {
        if (isActive == active) return
        isActive = active
        applyPenMode()
        if (active) openNativeInk() else closeNativeInk()
    }

    /** The editor's ink gate (popups, sidebar, divider drag...). */
    fun setInkAllowed(allowed: Boolean) {
        if (inkAllowed == allowed) return
        inkAllowed = allowed
        overlay.inkSuspended = !allowed
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
            rawInput = WebInkRawInput.createOrNull(overlay)?.also { it.onPenCut = { palmRejector.reset() } }
            overlay.nativeInput = rawInput != null
            rawInput?.let { raw ->
                raw.exclusionRects = exclusionRects
                raw.setEnabled(overlay.writeEnabled)
                if (!inkAllowed) raw.pause()
                if (fingerPaused) raw.setFingerPaused(true)
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
        rawInput?.setFingerPaused(paused)
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
        val nativePen = !finger && overlay.penUsesDriver
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
        val decision = palmRejector.onTouch(InputClassifier.sample(ev), forceReject = fingerOff() || overlay.isPenActive)
        if (decision == PalmRejector.Decision.ACCEPT) return false
        if (decision == PalmRejector.Decision.CANCEL_ONGOING) {
            val cancel = InputClassifier.cancelCopy(ev)
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
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
                        val y = store.scrollOf(href)
                        if (y > 0f) view.postDelayed({ view.scrollTo(0, (y * currentScale()).roundToInt()) }, 300)
                    }
                    updateNavButtons()
                    epd.onContentMoved()
                    scheduleDriftCheck()
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
                    saveBlocking()
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
        overlay.page = store.page(href)
        driftBanner.update(href, false)
        scheduleDriftCheck()
        updateNavButtons()
    }

    private fun currentInk(): WebInkPage? = currentHref?.let { store.page(it) }

    private fun rememberScroll() {
        val href = currentHref ?: return
        store.setScroll(href, webView.scrollY / currentScale())
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
        currentHref?.let { store.markInkChanged(it, layoutWidthCss()) }
        removeCallbacks(saveRunnable)
        postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    /** Queues a background save of notes, last page and scroll positions. */
    fun saveNow() {
        removeCallbacks(saveRunnable)
        if (released) return
        rememberScroll()
        store.lastPage = currentHref
        store.save()
    }

    /** Same, but returns once the disk is current (lifecycle moments only). */
    private fun saveBlocking() {
        removeCallbacks(saveRunnable)
        if (released) return
        rememberScroll()
        store.lastPage = currentHref
        store.flushBlocking()
    }

    // ---------------------------------------------------------------- layout drift & diagnostics

    private fun layoutWidthCss(): Float = webView.width / resources.displayMetrics.density

    private fun scheduleDriftCheck() {
        removeCallbacks(driftCheck)
        postDelayed(driftCheck, 600)
    }

    private fun checkLayoutDrift() {
        val href = currentHref ?: return
        driftBanner.update(href, WebAnnotationStore.isLayoutDrift(store.layoutWidthOf(href), layoutWidthCss()))
    }

    private fun showInkDiagnostics() {
        val href = currentHref
        val width = (webView.width / resources.displayMetrics.density).toInt()
        val scale = currentScale()
        Thread {
            val disk = href?.let { store.diskStrokeCount(it) }
            post {
                if (released) return@post
                val text =
                    WebInkDiagnostics.format(
                        href,
                        href?.let { store.memoryStrokeCount(it) } ?: 0,
                        disk,
                        store.stats(),
                        rawInput?.diagnostics() ?: "Native ink: off (software ink)",
                        scale,
                        width,
                        href?.let { store.layoutWidthOf(it) },
                    )
                AlertDialog
                    .Builder(context)
                    .setTitle("Ink diagnostics")
                    .setMessage(text)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    fun onHostPause() {
        saveBlocking()
        webView.onPause()
        closeNativeInk()
    }

    fun onHostResume() {
        webView.onResume()
        if (isActive) openNativeInk()
    }

    /** Saves and frees the WebView; the pane cannot be used afterwards. */
    fun release() {
        if (released) return
        removeCallbacks(driftCheck)
        saveBlocking()
        released = true
        WebAnnotationStore.release(store)
        closeNativeInk()
        epd.release()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun modeTab(
        label: String,
        onClick: () -> Unit,
    ) = TextView(context).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply { marginEnd = dp(2) }
        setOnClickListener { onClick() }
    }

    private fun styleModeTab(
        tab: TextView,
        selected: Boolean,
    ) {
        tab.background =
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (selected) Color.BLACK else Color.WHITE)
                setStroke(dp(1), Color.BLACK)
            }
        tab.setTextColor(if (selected) Color.WHITE else Color.BLACK)
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
