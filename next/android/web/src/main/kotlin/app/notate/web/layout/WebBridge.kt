package app.notate.web.layout

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlin.math.roundToInt

/**
 * Glue between a document's WebView and `assets/notate-web/bridge.js`: fixes the layout width and reports how far the page's
 * real scroller has moved and how big the content is. What the page can call takes numbers and short strings only, because
 * the page controls the caller.
 */
class WebBridge(private val webView: WebView, private val listener: Listener) {
    interface Listener {
        /** Main thread. Scroll of an inner scroller and content size, CSS px. */
        fun onScrolled()
        fun onLayoutReported(widthCss: Float, fixed: Boolean)
        fun onHashChanged(hash: String)
    }

    @Volatile var innerX = 0f
        private set
    @Volatile var innerY = 0f
        private set
    @Volatile var contentWidthCss = 0f
        private set
    @Volatile var contentHeightCss = 0f
        private set

    @Volatile var features = 0
        private set

    inner class JsApi {
        @JavascriptInterface
        fun onScroll(x: Float, y: Float, contentWidth: Float, contentHeight: Float) {
            if (!x.isFinite() || !y.isFinite() || !contentWidth.isFinite() || !contentHeight.isFinite()) return
            innerX = x
            innerY = y
            contentWidthCss = contentWidth.coerceIn(0f, MAX_CSS)
            contentHeightCss = contentHeight.coerceIn(0f, MAX_CSS)
            webView.post { listener.onScrolled() }
        }

        @JavascriptInterface
        fun onLayout(innerWidth: Float, fixed: Boolean) {
            if (!innerWidth.isFinite() || innerWidth <= 0f) return
            webView.post { listener.onLayoutReported(innerWidth, fixed) }
        }

        /** Bit set of features the page used: 1 service worker, 2 WebGL, 4 WebAssembly, 8 ES modules. */
        @JavascriptInterface
        fun onFeatures(bits: Int) {
            features = features or (bits and ALL_FEATURES)
        }

        @JavascriptInterface
        fun onNavigate(hash: String?) {
            val h = (hash ?: "").take(512)
            webView.post { listener.onHashChanged(h) }
        }
    }

    init {
        webView.addJavascriptInterface(JsApi(), NAME)
    }

    /** Injects the script, then locks the layout to [widthCss] and applies reduce motion. Safe to call twice per page. */
    fun inject(widthCss: Float, reduceMotion: Boolean) {
        if (!webView.settings.javaScriptEnabled) return
        val js = script(webView.context) +
            "\nwindow.__notate&&(__notate.setLayout(${widthCss.roundToInt()}),__notate.setReduceMotion($reduceMotion));"
        webView.evaluateJavascript(js, null)
    }

    fun setReduceMotion(on: Boolean) {
        if (webView.settings.javaScriptEnabled) webView.evaluateJavascript("window.__notate&&__notate.setReduceMotion($on)", null)
    }

    /** Page content scroll in device px: the WebView's own plus an inner scroller's. */
    fun scrollXPx(scale: Float): Float = webView.scrollX + innerX * scale

    fun scrollYPx(scale: Float): Float = webView.scrollY + innerY * scale

    /** Scrolls to [yCss]: the inner scroller if there is one, else the window. */
    fun scrollToCss(yCss: Float, scale: Float) {
        webView.scrollTo(webView.scrollX, (yCss * scale).roundToInt())
        if (webView.settings.javaScriptEnabled) webView.evaluateJavascript("window.__notate&&__notate.scrollTo(${yCss.roundToInt()})", null)
    }

    /** A new page is loading: forget the old page's scroller and size. */
    fun reset() {
        innerX = 0f
        innerY = 0f
        contentWidthCss = 0f
        contentHeightCss = 0f
        features = 0
    }

    fun release() {
        webView.removeJavascriptInterface(NAME)
    }

    companion object {
        const val NAME = "NotateBridge"
        const val FEATURE_SERVICE_WORKER = 1
        const val FEATURE_WEBGL = 2
        const val FEATURE_WASM = 4
        const val FEATURE_MODULES = 8
        private const val ALL_FEATURES = 15
        private const val ASSET = "notate-web/bridge.js"
        private const val MAX_CSS = 1_000_000f

        @Volatile private var cached: String? = null

        private fun script(context: Context): String =
            cached ?: context.assets.open(ASSET).bufferedReader().use { it.readText() }.also { cached = it }
    }
}
