package app.notate.editor.pane

import android.view.View
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.input.InkArbiter
import app.notate.core.input.InkSurface
import app.notate.core.input.PaneTarget
import app.notate.core.model.Capability
import app.notate.core.model.PageId
import app.notate.core.model.PageLayoutMode
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

    /** Continuous scroll or single page. */
    val layoutMode: PageLayoutMode get() = PageLayoutMode.CONTINUOUS

    /** The page in view (nearest to the middle of the pane), or null before the first layout. */
    val currentPage: PageId? get() = null

    /** Scrolls (continuous) or flips (single page) to [page]. */
    fun goToPage(page: PageId) {}

    /** Like [goToPage], then scrolls so that [yPt] (page pt from the page top) is near the top of the pane; [yPt] null = page top. */
    fun goToPage(page: PageId, yPt: Float?) = goToPage(page)

    /** Switches between continuous scroll and single-page mode, staying on the current page, and remembers it in the view state. */
    fun setLayoutMode(mode: PageLayoutMode) {}

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

    /** The page in view changed: [index] (0-based) of [count]. Drives the page indicator. */
    fun onPageChanged(index: Int, count: Int) {}

    /** A pane gesture (three-finger tap with that setting) asks to toggle focus mode. */
    fun onFocusModeRequested() {}

    /** The page in the middle of the pane changed; [hasGrid] tells whether its paper has a grid to snap to. */
    fun onPageGrid(hasGrid: Boolean) {}
}
