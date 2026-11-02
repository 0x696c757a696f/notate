package app.notate.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.doc.save.SaveState
import app.notate.core.doc.settings.FingerMode
import app.notate.core.input.GestureClassifier
import app.notate.core.input.HoverFocusDebouncer
import app.notate.core.input.InkArbiter
import app.notate.core.input.InputDispatcher
import app.notate.core.input.PalmRejector
import app.notate.core.input.PointerAction
import app.notate.core.input.PointerKind
import app.notate.core.input.PointerSample
import app.notate.core.model.DocId
import app.notate.core.model.EraserMode
import app.notate.core.model.ToolKind
import app.notate.editor.debug.DebugHud
import app.notate.editor.input.TouchAdapter
import app.notate.editor.pane.NotebookPane
import app.notate.editor.pane.PaneHost
import app.notate.editor.toolbar.MiniToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Full-screen editor for one notebook: toolbar on top, one [NotebookPane] below. Main thread only. */
class EditorActivity : Activity(), PaneHost, MiniToolbar.Callbacks {
    override val arbiter = InkArbiter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val deps get() = EditorEnv.deps

    private lateinit var toolbar: MiniToolbar
    private lateinit var container: FrameLayout
    private lateinit var hud: DebugHud
    private lateinit var adapter: TouchAdapter
    private lateinit var palm: PalmRejector
    private lateinit var dispatcher: InputDispatcher
    private var handle: DocumentHandle? = null
    private var pane: NotebookPane? = null
    private var docId: DocId? = null
    private val recentFingers = ArrayDeque<String>()
    private var ticking = false
    private val tickRunnable = object : Runnable {
        override fun run() {
            dispatcher.onTimer(SystemClock.uptimeMillis())
            if (ticking) main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_DOC_ID)
        if (id == null) {
            finish()
            return
        }
        docId = DocId(id)
        val density = resources.displayMetrics.density
        val xdpi = deps.device.xdpi
        adapter = TouchAdapter(xdpi)
        palm = PalmRejector(
            palmMinSizePx = PalmRejector.PALM_MIN_SIZE_MM * xdpi / 25.4f,
            pxPerCm = xdpi / 2.54f,
        )
        dispatcher = InputDispatcher(
            palm,
            GestureClassifier(touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat(), multiTapSlopPx = 24f * density),
            HoverFocusDebouncer(),
        ) { }
        buildViews()
        observeSettings()
        open()
    }

    private fun buildViews() {
        toolbar = MiniToolbar(this, this)
        container = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        hud = DebugHud(this) { hudLines() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun observeSettings() {
        val d = deps
        scope.launch {
            d.settings.fingerMode.collect { dispatcher.fingersEnabled = it == FingerMode.SCROLL }
        }
        scope.launch {
            d.settings.handedness.collect {
                palm.handedness = if (it == app.notate.core.doc.settings.Handedness.LEFT) app.notate.core.input.Handedness.LEFT else app.notate.core.input.Handedness.RIGHT
            }
        }
        scope.launch { d.settings.debugHud.collect { hud.setShown(it) } }
        scope.launch {
            d.toolController.state.collect { toolbar.setTool(it.active, it.eraserMode) }
        }
    }

    private fun open() {
        val id = docId ?: return
        scope.launch {
            val h = try {
                deps.registry.acquire(id)
            } catch (e: Exception) {
                deps.logger.e(TAG, "Opening $id failed", e)
                Toast.makeText(this@EditorActivity, R.string.open_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            if (isDestroyed || isFinishing) {
                deps.registry.release(h)
                return@launch
            }
            handle = h
            val p = NotebookPane(this@EditorActivity, h, deps, this@EditorActivity, scope)
            pane = p
            container.addView(p.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            container.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            arbiter.block(app.notate.core.input.BlockReason.WINDOW_UNFOCUSED, !hasWindowFocus())
            dispatcher.focusedPane = p.id
            bind(h)
        }
    }

    private fun bind(h: DocumentHandle) {
        scope.launch { h.editor.meta.collect { toolbar.setTitle(it.title) } }
        scope.launch { h.editor.history.canUndo.collect { toolbar.setUndoRedo(it, h.editor.history.canRedo.value) } }
        scope.launch { h.editor.history.canRedo.collect { toolbar.setUndoRedo(h.editor.history.canUndo.value, it) } }
        scope.launch {
            var busy = false
            h.save.state.collect { st ->
                toolbar.setSaveState(st)
                if (st == SaveState.Clean && busy) pane?.onSaveIdle(SystemClock.uptimeMillis())
                busy = st != SaveState.Clean
            }
        }
    }

    // --- PaneHost -------------------------------------------------------------------------------------------

    override fun excludeRects(): List<android.graphics.Rect> = emptyList()

    override fun onDriverStroke(begin: Boolean, timeMs: Long) = dispatcher.onDriverStroke(begin, timeMs)

    // --- Toolbar --------------------------------------------------------------------------------------------

    override fun onPen() = deps.toolController.select(ToolKind.PEN)

    override fun onEraser() {
        val tc = deps.toolController
        val s = tc.state.value
        if (s.active == ToolKind.ERASER) {
            tc.update { it.copy(eraserMode = if (it.eraserMode == EraserMode.PARTIAL) EraserMode.STROKE else EraserMode.PARTIAL) }
        } else {
            tc.select(ToolKind.ERASER)
        }
    }

    override fun onUndo() { handle?.editor?.undo() }

    override fun onRedo() { handle?.editor?.redo() }

    override fun onBack() = finish()

    // --- Input ----------------------------------------------------------------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val p = pane ?: return super.dispatchTouchEvent(ev)
        val panes = listOf<app.notate.core.input.PaneTarget>(p)
        var consumed = false
        for (s in adapter.convert(ev)) {
            val c = dispatcher.onSample(s, panes)
            consumed = consumed || c
            if (s.kind == PointerKind.FINGER && s.action == PointerAction.DOWN) rememberFinger(s, c)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> startTicking()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ticking = false
        }
        return if (consumed) true else super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val p = pane ?: return super.dispatchGenericMotionEvent(ev)
        val panes = listOf<app.notate.core.input.PaneTarget>(p)
        var consumed = false
        for (s in adapter.convert(ev)) consumed = dispatcher.onSample(s, panes) || consumed
        return if (consumed) true else super.dispatchGenericMotionEvent(ev)
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        main.postDelayed(tickRunnable, TICK_MS)
    }

    private fun rememberFinger(s: PointerSample, consumed: Boolean) {
        val palmish = palm.shouldReject(s.timeMs, s.x, s.y)
        recentFingers.addLast("${if (palmish) "palm" else "ok"} ${"%.1f".format(s.majorMm)}mm ${if (consumed) "used" else "passed"}")
        while (recentFingers.size > RECENT_FINGERS) recentFingers.removeFirst()
    }

    private fun hudLines(): List<String> {
        val p = pane ?: return listOf("(no pane)")
        val save = handle?.save?.state?.value
        return p.debugLines() + "save=$save" + "touch: ${recentFingers.joinToString(" | ").ifEmpty { "-" }}"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        arbiter.block(app.notate.core.input.BlockReason.WINDOW_UNFOCUSED, !hasFocus)
    }

    override fun onStop() {
        super.onStop()
        saveViewState()
        deps.appScope.launch { deps.registry.flushAll() }
    }

    private fun saveViewState() {
        val h = handle ?: return
        val state = pane?.saveViewState() ?: return
        val meta = h.editor.meta.value
        if (meta.view != state) h.editor.apply(app.notate.core.model.edit.Edit.SetMeta(meta, meta.copy(view = state)), undoable = false)
    }

    override fun onDestroy() {
        ticking = false
        main.removeCallbacksAndMessages(null)
        saveViewState()
        pane?.release()
        pane = null
        scope.cancel()
        handle?.let { deps.registry.release(it) }
        handle = null
        docId?.let { deps.onDocumentClosed(it) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EditorActivity"
        private const val EXTRA_DOC_ID = "doc_id"
        private const val TICK_MS = 50L
        private const val RECENT_FINGERS = 5

        fun intent(context: Context, id: DocId): Intent =
            Intent(context, EditorActivity::class.java).putExtra(EXTRA_DOC_ID, id.value)
    }
}
