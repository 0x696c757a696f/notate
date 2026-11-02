package app.notate.web.sandbox

import android.webkit.WebSettings
import app.notate.core.model.WebDocSettings

/** WebView sandbox settings for a web document (R4-2): only the document's own files, through its private origin. */
object WebSandbox {
    fun configure(settings: WebSettings, doc: WebDocSettings) {
        with(settings) {
            javaScriptEnabled = doc.javascript
            domStorageEnabled = true      // localStorage / sessionStorage: exercise answers, reading progress
            @Suppress("DEPRECATION")
            databaseEnabled = true
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
            // Honour <meta viewport> (fixed-layout books), fit wide pages, pinch zoom without +/- buttons.
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = doc.textZoom
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
    }

    /** Settings that can change while the page is shown. */
    fun apply(settings: WebSettings, doc: WebDocSettings) {
        settings.javaScriptEnabled = doc.javascript
        settings.textZoom = doc.textZoom
    }
}
