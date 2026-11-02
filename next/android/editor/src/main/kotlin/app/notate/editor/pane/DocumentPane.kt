package app.notate.editor.pane

import android.view.View
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.input.InkArbiter
import app.notate.core.input.InkSurface
import app.notate.core.input.PaneTarget
import app.notate.core.model.ToolPreset
import app.notate.core.model.ViewState

/** One document view inside the editor window (02-architecture §7). */
interface DocumentPane : PaneTarget, InkSurface {
    val handle: DocumentHandle
    val view: View
    fun onFocusChanged(focused: Boolean)
    fun onToolChanged(tool: ToolPreset)
    fun saveViewState(): ViewState?
    fun restoreViewState(s: ViewState)
    fun release()
}

/** What a pane needs from the window that hosts it. */
interface PaneHost {
    val arbiter: InkArbiter

    /** Areas of the pane's view (view-local px) where the pen must be ignored, e.g. floating toolbars. */
    fun excludeRects(): List<android.graphics.Rect>

    /** The e-ink driver reported a stroke start/end; feeds palm rejection. [timeMs] is `SystemClock.uptimeMillis()`. */
    fun onDriverStroke(begin: Boolean, timeMs: Long)
}
