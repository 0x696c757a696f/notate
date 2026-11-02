package app.notate.web.sandbox

import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.notate.core.web.ByteRange
import app.notate.core.web.WebHtml
import app.notate.core.web.WebOrigin
import app.notate.core.web.WebResourcePaths
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Serves a web document's files on its private origin `https://<id>.webdoc.notate/` and keeps the page inside it:
 * same-origin requests come from [root] (with byte ranges for audio and video), requests to other origins are refused
 * with 403 unless [network] allows them, and main-frame navigation away from the origin never loads in the WebView.
 */
class SandboxClient(
    private val docId: String,
    private val root: File,
    private val network: () -> Boolean,
    private val listener: Listener,
    /** Layout width (CSS px) every HTML page of the main frame is locked to; 0 leaves pages alone. */
    private val layoutWidthCss: () -> Float = { 0f },
) : WebViewClient() {
    interface Listener {
        /** A link inside the document was followed; return true when handled (the WebView must not navigate itself). */
        fun onDocumentLink(path: String, fragment: String?): Boolean
        fun onExternalLink(url: String)
        fun onPageStarted(url: String)
        fun onPageFinished(url: String)
        fun onScaleChanged(scale: Float)
        fun onRendererGone()
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        respond(request.url.toString(), request.method, request.requestHeaders, request.isForMainFrame)

    /** The response to a request; null lets the WebView load it from the network. */
    fun respond(url: String, method: String, headers: Map<String, String>?, mainFrame: Boolean = false): WebResourceResponse? {
        if (WebOrigin.isSameOrigin(docId, url)) {
            return serve(url, method, headers?.entries?.firstOrNull { it.key.equals("Range", true) }?.value, mainFrame)
        }
        return if (network()) null else empty(403, "Blocked offline")
    }

    private fun serve(url: String, method: String, rangeHeader: String?, mainFrame: Boolean): WebResourceResponse {
        if (method != "GET" && method != "HEAD") return empty(405, "Method Not Allowed")
        val path = url.substring(WebOrigin.base(docId).length).substringBefore('#').substringBefore('?')
        val file = WebResourcePaths.resolve(root, path) ?: return empty(404, "Not Found")
        val mime = WebResourcePaths.mimeType(file)
        // HTML and XHTML: the parser picks the charset from <meta> or the XML declaration.
        val encoding = if (WebResourcePaths.isTextual(mime) && mime != "text/html" && mime != "application/xhtml+xml") "utf-8" else null
        val length = file.length()
        val headers = HashMap<String, String>()
        headers["Cache-Control"] = "no-cache"
        headers["X-Content-Type-Options"] = "nosniff"
        headers["Accept-Ranges"] = "bytes"
        val range = ByteRange.parse(rangeHeader, length)
        if (rangeHeader != null && range == null && length > 0 && rangeHeader.trim().startsWith("bytes=")) {
            headers["Content-Range"] = "bytes */$length"
            return WebResourceResponse(mime, encoding, 416, "Range Not Satisfiable", headers, ByteArrayInputStream(ByteArray(0)))
        }
        if (method == "HEAD") {
            headers["Content-Length"] = length.toString()
            return WebResourceResponse(mime, encoding, 200, "OK", headers, ByteArrayInputStream(ByteArray(0)))
        }
        val width = layoutWidthCss()
        if (range == null && mainFrame && width > 0f && (mime == "text/html" || mime == "application/xhtml+xml")) {
            val locked = WebHtml.injectViewport(file.readBytes(), Math.round(width))
            headers["Content-Length"] = locked.size.toString()
            return WebResourceResponse(mime, encoding, 200, "OK", headers, ByteArrayInputStream(locked))
        }
        if (range == null) {
            headers["Content-Length"] = length.toString()
            return WebResourceResponse(mime, encoding, 200, "OK", headers, file.inputStream().buffered())
        }
        val count = range.last - range.first + 1
        headers["Content-Range"] = "bytes ${range.first}-${range.last}/$length"
        headers["Content-Length"] = count.toString()
        val input = file.inputStream()
        var skipped = 0L
        while (skipped < range.first) {
            val n = input.skip(range.first - skipped)
            if (n <= 0) break
            skipped += n
        }
        return WebResourceResponse(mime, encoding, 206, "Partial Content", headers, BoundedInputStream(input.buffered(), count))
    }

    private fun empty(code: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url.toString()
        val local = WebOrigin.parse(docId, url)
        if (local != null) return listener.onDocumentLink(local.first, local.second)
        // Never let the main frame leave the sandbox: our script bridge must not be reachable from other sites.
        if (url.startsWith("http://") || url.startsWith("https://")) listener.onExternalLink(url)
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = listener.onPageStarted(url)

    override fun onPageFinished(view: WebView, url: String) = listener.onPageFinished(url)

    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) = listener.onScaleChanged(newScale)

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        listener.onRendererGone()
        return true
    }

    private class BoundedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = super.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }
}
