package com.alexdremov.notate.webdoc

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.alexdremov.notate.util.Logger
import kotlin.math.roundToInt

/**
 * Glue between a document's WebView and `assets/webdoc/notate-bridge.js`: fixes the layout width and
 * tells us how far the page's real scroller has moved, so ink follows documents that scroll an inner
 * element instead of the window. One per WebView; the same code serves the reader and the book pane.
 */
class WebLayoutBridge(
    private val webView: WebView,
    private val store: WebAnnotationStore,
    /** Called on the main thread when the inner scroll position changed. */
    private val onInnerScroll: () -> Unit,
) {
    companion object {
        private const val TAG = "WebLayoutBridge"
        private const val ASSET = "webdoc/notate-bridge.js"

        @Volatile private var script: String? = null

        private fun script(context: Context): String =
            script ?: context.assets
                .open(ASSET)
                .bufferedReader()
                .use { it.readText() }
                .also { script = it }
    }

    @Volatile private var innerX = 0f

    @Volatile private var innerY = 0f

    @Volatile private var inner = false

    /** What the page can call. Numbers only: the page controls the caller, so it must not be able to do anything else. */
    inner class JsApi {
        @JavascriptInterface
        fun onScroll(
            x: Float,
            y: Float,
            isInner: Boolean,
        ) {
            if (!x.isFinite() || !y.isFinite()) return
            innerX = x
            innerY = y
            inner = isInner
            webView.post { onInnerScroll() }
        }
    }

    init {
        webView.addJavascriptInterface(JsApi(), "NotateBridge")
    }

    /** Width the page is laid out at (CSS px); the first call for a document fixes it. */
    fun lockedWidthCss(): Float = store.lockedWidthCss(webView.width / webView.resources.displayMetrics.density)

    /** Call from onPageStarted and onPageFinished (idempotent). Needs JavaScript. */
    fun inject(view: WebView) {
        if (!view.settings.javaScriptEnabled) return
        val width = lockedWidthCss()
        try {
            view.evaluateJavascript(script(view.context), null)
            if (width > 0f) view.evaluateJavascript("window.__notate&&__notate.setLayout(${width.roundToInt()})", null)
        } catch (e: Exception) {
            Logger.w(TAG, "Could not inject the layout bridge", e)
        }
    }

    /** Horizontal scroll of the page in device px, including the inner scroller. */
    fun scrollXPx(scale: Float): Int = webView.scrollX + if (inner) (innerX * scale).roundToInt() else 0

    /** Vertical scroll of the page in device px, including the inner scroller. */
    fun scrollYPx(scale: Float): Int = webView.scrollY + if (inner) (innerY * scale).roundToInt() else 0

    /** Vertical scroll in CSS px (what is saved). */
    fun scrollYCss(scale: Float): Float = scrollYPx(scale) / scale

    /** Restores a saved vertical position (CSS px): scrolls the inner element if there is one, else the window. */
    fun scrollToCss(
        yCss: Float,
        scale: Float,
    ) {
        if (inner) {
            webView.evaluateJavascript("window.__notate&&__notate.scrollTo(${yCss.roundToInt()})", null)
        } else {
            webView.scrollTo(0, (yCss * scale).roundToInt())
            // The page may only now turn out to scroll an inner element: try that too, harmlessly.
            if (webView.settings.javaScriptEnabled) webView.evaluateJavascript("window.__notate&&__notate.scrollTo(${yCss.roundToInt()})", null)
        }
    }

    /** A new page is loading: forget the old page's inner scroller. */
    fun reset() {
        inner = false
        innerX = 0f
        innerY = 0f
    }
}
