package app.notate.editor.pane

import android.view.View
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.input.InkArbiter
import app.notate.core.input.InkSurface
import app.notate.core.input.PaneTarget
import app.notate.core.model.Capability
import app.notate.core.model.ToolPreset
import app.notate.core.model.ViewState

/** One document view inside the editor window (02-architecture §7). */
interface DocumentPane : PaneTarget, InkSurface {
    val handle: DocumentHandle
    val view: View

    /** What this pane can do; the toolbar greys out the rest (04 §5). */
    val capabilities: Set<Capability>
    fun onFocusChanged(focused: Boolean)
    fun onToolChanged(tool: ToolPreset)
    fun saveViewState(): ViewState?
    fun restoreViewState(s: ViewState)
    fun release()

    /** Pages button: shows the page grid (R2-3; nothing until then). */
    fun showPageGrid() {}

    /** Called by the window when the save queue went idle after a change (debug timings). */
    fun onSaveIdle(nowUptime: Long) {}

    /** Lines for the debug HUD. */
    fun debugLines(): List<String> = emptyList()

    /** Extra rows of the More menu that only this kind of pane has. */
    fun moreEntries(): List<app.notate.editor.popup.MenuEntry> = emptyList()

    /** The host's exclusion rectangles changed (floating button, popup): hand them to the pen driver again. */
    fun refreshExcludes() {}
}

/** What a pane needs from the window that hosts it. */
interface PaneHost {
    val arbiter: InkArbiter

    /** Areas of the pane's view (view-local px) where the pen must be ignored, e.g. floating toolbars. */
    fun excludeRects(): List<android.graphics.Rect>

    /** The e-ink driver reported a stroke start/end; feeds palm rejection. [timeMs] is `SystemClock.uptimeMillis()`. */
    fun onDriverStroke(begin: Boolean, timeMs: Long)

    /** Shows [content] as the window's popup near [anchor] (the pen is kept off the page while it is up); null if unsupported. */
    fun showPopup(anchor: View?, content: View, placement: app.notate.editor.popup.Placement = app.notate.editor.popup.Placement.BELOW_ANCHOR): app.notate.editor.popup.PopupHandle? = null

    /** Closes the window's popup, if any. */
    fun dismissPopup() {}

    /** A pane gesture (three-finger tap with that setting) asks to toggle focus mode. */
    fun onFocusModeRequested() {}
}
