package com.alexdremov.notate.webdoc

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.alexdremov.notate.BuildConfig
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.OnyxSystemHelper
import com.alexdremov.notate.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        private const val REDUCE_MOTION_JS =
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
    private lateinit var overlay: WebInkOverlayView
    private lateinit var epd: WebDocEpd
    private lateinit var toolbar: LinearLayout
    private lateinit var titleView: TextView
    private val toolButtons = HashMap<WebInkOverlayView.Tool, TextView>()
    private lateinit var modeButton: TextView
    private var prevButton: TextView? = null
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
                    toast("文档不存在或已被删除")
                    finish()
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
                .setMessage("正在导入…")
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
                    toast("导入失败：${it.message}")
                    finish()
                }
        }
    }

    private fun importFile(uri: Uri): WebDocMeta {
        val name = UriUtils.getFileName(this, uri) ?: "document.html"
        return library.importFile(name, { contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法读取 $uri") })
    }

    private fun importFolder(treeUri: Uri): WebDocMeta {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: throw java.io.IOException("无法读取文件夹")
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

        toolbar.addView(button("‹ 返回") { finish() })
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
            toolbar.addView(button("目录") { showToc() })
            prevButton = button("◀ 上一节") { stepPage(-1) }.also { toolbar.addView(it) }
            nextButton = button("下一节 ▶") { stepPage(1) }.also { toolbar.addView(it) }
        }
        toolbar.addView(separator())

        modeButton = button("") { setWriteMode(!overlay.writeEnabled) }
        toolbar.addView(modeButton)
        for ((tool, label) in listOf(WebInkOverlayView.Tool.PEN to "钢笔", WebInkOverlayView.Tool.HIGHLIGHTER to "荧光笔", WebInkOverlayView.Tool.ERASER to "橡皮")) {
            val b = button(label) { selectTool(tool) }
            toolButtons[tool] = b
            toolbar.addView(b)
        }
        toolbar.addView(button("撤销") { if (currentInk()?.undo() == true) inkChanged() })
        toolbar.addView(button("重做") { if (currentInk()?.redo() == true) inkChanged() })
        toolbar.addView(separator())
        toolbar.addView(button("刷新屏幕") { epd.fullRefresh() })
        toolbar.addView(button("⋯") { showMoreMenu(it) })

        val content = FrameLayout(this)
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
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        epd = WebDocEpd(webView)
        OnyxSystemHelper.ignoreSystemSideButton(overlay)
        overlay.onStrokeStart = { epd.onInteractionStart() }
        overlay.onInkChanged = {
            epd.onInteractionEnd()
            inkChanged()
        }
        webView.setOnScrollChangeListener { _, _, _, _, _ ->
            overlay.invalidate()
            if (!overlay.isDrawing) epd.onContentMoved()
        }

        configureWebView(m)
        setWriteMode(true)
        selectTool(WebInkOverlayView.Tool.PEN)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::overlay.isInitialized) {
            // Palm rejection: while the pen is writing, a resting hand must not scroll the page under the ink.
            if (overlay.isDrawing && ev.getToolType(ev.actionIndex) == MotionEvent.TOOL_TYPE_FINGER) return true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> epd.onInteractionStart()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> epd.onInteractionEnd()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(m: WebDocMeta) {
        val host = WebDocResources.hostFor(m.id)
        val contentRoot = library.contentDir(m.id)

        with(webView.settings) {
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

        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url
                    if (url.host == host) return serveLocal(contentRoot, url, request.method)
                    val scheme = url.scheme ?: ""
                    if (scheme == "data" || scheme == "blob") return null
                    return if (meta?.policy?.networkAllowed == true) null else blocked()
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
                            toast("没有可以打开链接的应用")
                        }
                    } else {
                        toast("已阻止外部链接（可在 ⋯ 中允许联网）")
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
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    // A heavy or hostile document must not take the whole app down.
                    Logger.e(TAG, "WebView renderer gone (crash=${detail.didCrash()})")
                    saveNow()
                    toast("文档渲染进程已退出，已保存笔记")
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

    private fun serveLocal(
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

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked offline", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun applyPolicy(policy: WebDocPolicy) {
        webView.settings.javaScriptEnabled = policy.javaScriptEnabled
        webView.settings.blockNetworkLoads = !policy.networkAllowed
        webView.settings.safeBrowsingEnabled = policy.networkAllowed
    }

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
            .add(0, 1, 0, "启用 JavaScript")
            .setCheckable(true)
            .setChecked(m.policy.javaScriptEnabled)
        popup.menu
            .add(0, 2, 1, "允许联网")
            .setCheckable(true)
            .setChecked(m.policy.networkAllowed)
        popup.menu
            .add(0, 3, 2, "减少动画（E Ink）")
            .setCheckable(true)
            .setChecked(m.policy.reduceMotion)
        popup.menu.add(0, 4, 3, "清除本页笔记")
        popup.menu.add(0, 5, 4, "兼容性检查")
        popup.menu.add(0, 6, 5, "清除网页保存的数据")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> updatePolicy { it.copy(javaScriptEnabled = !it.javaScriptEnabled) }
                2 -> confirmNetworkToggle()
                3 -> updatePolicy { it.copy(reduceMotion = !it.reduceMotion) }
                4 ->
                    AlertDialog
                        .Builder(this)
                        .setMessage("清除当前页面上的全部手写笔记？（可撤销）")
                        .setPositiveButton("清除") { _, _ ->
                            currentInk()?.clear()
                            inkChanged()
                        }.setNegativeButton("取消", null)
                        .show()
                5 -> showCompatibility()
                6 ->
                    AlertDialog
                        .Builder(this)
                        .setMessage("清除此文档在 localStorage / IndexedDB 中保存的练习答案和进度？手写笔记不受影响。")
                        .setPositiveButton("清除") { _, _ ->
                            WebStorage.getInstance().deleteOrigin(WebDocResources.originFor(m.id))
                            webView.reload()
                        }.setNegativeButton("取消", null)
                        .show()
            }
            true
        }
        popup.show()
    }

    private fun confirmNetworkToggle() {
        val m = meta ?: return
        if (m.policy.networkAllowed) {
            updatePolicy { it.copy(networkAllowed = false) }
            return
        }
        AlertDialog
            .Builder(this)
            .setTitle("允许此文档联网？")
            .setMessage("文档中的脚本将可以访问互联网（例如加载在线字体、MathJax CDN 或发送数据）。只对可信来源的文档开启。")
            .setPositiveButton("允许") { _, _ -> updatePolicy { it.copy(networkAllowed = true) } }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun webViewMajorVersion(): Pair<String, Int?> {
        val pkg = WebView.getCurrentWebViewPackage()
        val version = pkg?.versionName ?: "未知"
        return "${pkg?.packageName ?: "WebView"} $version" to version.substringBefore('.').toIntOrNull()
    }

    private fun checkWebViewVersion() {
        val (_, major) = webViewMajorVersion()
        if (major != null && major < MIN_RECOMMENDED_WEBVIEW) {
            toast("系统 WebView 版本较旧（$major），部分交互内容或公式可能无法正常显示")
        }
    }

    private fun showCompatibility() {
        val (label, major) = webViewMajorVersion()
        val m = meta ?: return
        val base =
            buildString {
                appendLine("WebView：$label")
                if (major != null && major < MIN_RECOMMENDED_WEBVIEW) appendLine("⚠ 建议升级到 $MIN_RECOMMENDED_WEBVIEW 以上")
                appendLine("文档类型：${if (m.kind == WebDocKind.EPUB) "EPUB" + if (m.fixedLayout) "（固定版式）" else "（流式）" else "HTML"}")
                appendLine("JavaScript：${if (m.policy.javaScriptEnabled) "开启" else "关闭"}　联网：${if (m.policy.networkAllowed) "允许" else "禁止"}")
            }
        if (!m.policy.javaScriptEnabled) {
            showText("兼容性检查", base + "\n开启 JavaScript 后可检测更多特性。")
            return
        }
        webView.evaluateJavascript(FEATURE_PROBE_JS) { raw ->
            val text =
                try {
                    val obj = JSONObject(JSONObject("{\"v\":$raw}").getString("v"))
                    val names =
                        linkedMapOf(
                            "esModules" to "ES Modules",
                            "es2020" to "ES2020 语法",
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
                    base + "\n特性检测失败：${e.message}"
                }
            showText("兼容性检查", text)
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
            .setPositiveButton("确定", null)
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
            .setTitle("目录")
            .setSingleChoiceItems(m.pages.map { it.title }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                loadPage(m.pages[which].href)
            }.setNegativeButton("关闭", null)
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
        overlay.writeEnabled = write
        modeButton.text = if (write) "✎ 笔：书写" else "☝ 笔：交互"
        styleButton(modeButton, write)
        toolButtons.values.forEach { it.alpha = if (write) 1f else 0.35f }
    }

    private fun selectTool(tool: WebInkOverlayView.Tool) {
        overlay.tool = tool
        if (!overlay.writeEnabled) setWriteMode(true)
        toolButtons.forEach { (t, b) -> styleButton(b, t == tool) }
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
            toast("笔记保存失败：${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        if (meta != null && ::webView.isInitialized) {
            saveJob?.cancel()
            saveNow()
            webView.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
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
