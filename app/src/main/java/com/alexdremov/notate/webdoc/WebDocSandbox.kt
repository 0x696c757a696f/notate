package com.alexdremov.notate.webdoc

import android.net.Uri
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import java.io.ByteArrayInputStream

/** WebView sandbox shared by the full reader and the split-view book pane. */
object WebDocSandbox {
    fun configure(settings: WebSettings) {
        with(settings) {
            domStorageEnabled = true // localStorage / sessionStorage for exercise answers & progress
            @Suppress("DEPRECATION")
            databaseEnabled = true
            // Sandbox: the page can only see its own files through the private origin.
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            // Layout: honour <meta viewport> (fixed-layout EPUBs), fit wide pages, pinch zoom without +/- buttons.
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100 // keep layout stable so ink stays on the right words
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
    }

    fun applyPolicy(
        settings: WebSettings,
        policy: WebDocPolicy,
    ) {
        settings.javaScriptEnabled = policy.javaScriptEnabled
        settings.blockNetworkLoads = !policy.networkAllowed
        settings.safeBrowsingEnabled = policy.networkAllowed
    }

    /** Serves a document's own files on its private origin. */
    fun serveLocal(
        root: java.io.File,
        url: Uri,
        method: String,
    ): WebResourceResponse {
        if (method != "GET" && method != "HEAD") {
            return WebResourceResponse("text/plain", "utf-8", 405, "Method Not Allowed", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        val file = WebDocResources.resolve(root, url.encodedPath ?: "/")
        if (file == null) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        val mime = WebDocResources.mimeType(file)
        // HTML/XHTML: let the parser pick the charset from <meta>/XML declaration.
        val encoding = if (WebDocResources.isTextual(mime) && mime != "text/html" && mime != "application/xhtml+xml") "utf-8" else null
        val headers = mapOf("Cache-Control" to "no-cache", "X-Content-Type-Options" to "nosniff")
        return WebResourceResponse(mime, encoding, 200, "OK", headers, file.inputStream().buffered())
    }

    fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked offline", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
