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

    /** Set when the pane has been in the window (and therefore attached to the ink arbiter) at least once. */
    internal var shown = false
    var thumbs: ThumbnailStore? = null
    private var closed = false

    /** Writes the reader's position into the document (not undoable; the save queue merges these writes). */
    fun persistView() {
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

    override val arbiter get() = outer.arbiter
    override fun excludeRects(): List<Rect> = view?.let(rectsFor) ?: emptyList()
    override fun onDriverStroke(begin: Boolean, timeMs: Long) = outer.onDriverStroke(begin, timeMs)

    override fun showPopup(anchor: View?, content: View, placement: app.notate.editor.popup.Placement) =
        if (active) outer.showPopup(anchor, content, placement) else null

    override fun dismissPopup() { if (active) outer.dismissPopup() }
    override fun onPageChanged(index: Int, count: Int) { indicator?.setPage(index, count) }
    override fun onFocusModeRequested() { if (active) outer.onFocusModeRequested() }

    override fun onPageGrid(hasGrid: Boolean) {
        this.hasGrid = hasGrid
        if (active) outer.onPageGrid(hasGrid)
    }

    fun activate(on: Boolean) {
        active = on
        if (on) outer.onPageGrid(hasGrid)
    }
}
