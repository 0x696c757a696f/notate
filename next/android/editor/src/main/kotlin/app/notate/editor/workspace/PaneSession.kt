package app.notate.editor.workspace

import android.graphics.Rect
import android.view.View
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.model.DocId
import app.notate.core.model.edit.Edit
import app.notate.editor.pages.PageActions
import app.notate.editor.pages.PageIndicator
import app.notate.editor.pages.ThumbnailStore
import app.notate.editor.pane.DocumentPane
import app.notate.editor.pane.PaneHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * Everything the window keeps for one open document: its pane, the page indicator, page actions and thumbnails. The pane
 * lives in [PaneCache] and may be released while the tab stays open; the [handle] (undo history) outlives it. Main thread only.
 */
class PaneSession internal constructor(
    val handle: DocumentHandle,
    val pane: DocumentPane,
    val scope: CoroutineScope,
    internal val host: GatedHost,
    val pageActions: PageActions,
    val indicator: PageIndicator?,
) {
    val doc: DocId get() = handle.id

    /** What the pane was last told about its focus (null: nothing yet). */
    internal var focusAnnounced: Boolean? = null
    var thumbs: ThumbnailStore? = null

    /** The window's frame around the pane (bar, focus strip); built by the window, kept as long as the session lives. */
    var frame: app.notate.editor.split.PaneFrame? = null

    /** False for the second pane of a document that is open in both panes: only one of them may write the reader's position. */
    var persistsMeta = true
    private var closed = false

    /** Writes the reader's position into the document (not undoable; the save queue merges these writes). */
    fun persistView() {
        if (!persistsMeta) return
        val state = pane.saveViewState() ?: return
        val meta = handle.editor.meta.value
        if (meta.view != state) handle.editor.apply(Edit.SetMeta(meta, meta.copy(view = state)), undoable = false)
    }

    /** Saves the position, releases the pane and stops its coroutines. The document handle stays with the controller. */
    fun close() {
        if (closed) return
        closed = true
        persistView()
        thumbs = null
        pane.release()
        scope.cancel()
    }
}

/**
 * The [PaneHost] a pane sees: forwards to the window, but only while its pane is the visible one, so a pane that sits in the
 * cache cannot open popups or move the page indicator of another document.
 */
internal class GatedHost(private val outer: PaneHost, private val rectsFor: (View) -> List<Rect>) : PaneHost {
    var view: View? = null
    var indicator: PageIndicator? = null
    private var active = false
    private var hasGrid = false

    /** Whether this pane has the pen and the toolbar. Set by the controller. */
    var focused = false

    /** Told about page changes of the pane (the split bar shows them). */
    var onPage: ((Int, Int) -> Unit)? = null

    override val arbiter get() = outer.arbiter
    override fun excludeRects(): List<Rect> = view?.let(rectsFor) ?: emptyList()
    override fun onDriverStroke(begin: Boolean, timeMs: Long) = outer.onDriverStroke(begin, timeMs)

    override fun showPopup(anchor: View?, content: View, placement: app.notate.editor.popup.Placement) =
        if (active) outer.showPopup(anchor, content, placement) else null

    override fun dismissPopup() { if (active) outer.dismissPopup() }
    override fun onPageChanged(index: Int, count: Int) {
        indicator?.setPage(index, count)
        onPage?.invoke(index, count)
        if (active && focused) outer.onPageChanged(index, count)
    }

    override fun onZoomWindowChanged(open: Boolean) { if (active && focused) outer.onZoomWindowChanged(open) }
    override fun onFocusModeRequested() { if (active) outer.onFocusModeRequested() }

    override fun onPageGrid(hasGrid: Boolean) {
        this.hasGrid = hasGrid
        if (active && focused) outer.onPageGrid(hasGrid)
    }

    /** A pane that gets its size takes the pen only when it has the focus; the controller hands it over otherwise. */
    override fun claimPen(pane: app.notate.editor.pane.DocumentPane) {
        if (focused) outer.claimPen(pane)
    }

    /** The pane is shown in the window (or not, e.g. in the cache). */
    fun activate(on: Boolean) {
        active = on
        if (on && focused) outer.onPageGrid(hasGrid)
    }

    /** The grid state belongs to the toolbar, which follows the focus. */
    fun pushGrid() { if (active && focused) outer.onPageGrid(hasGrid) }
}
