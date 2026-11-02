package app.notate.editor.select

import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.view.View
import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.doc.select.InkClipboard
import app.notate.core.doc.select.Selection
import app.notate.core.doc.select.SelectionController
import app.notate.core.geometry.Vec2
import app.notate.core.model.PageId
import app.notate.core.model.Shape
import app.notate.core.model.Stroke
import app.notate.core.model.TextBox
import app.notate.editor.R
import app.notate.editor.popup.ColorPalettePopup
import app.notate.editor.popup.MenuEntry
import app.notate.editor.popup.MoreMenu
import app.notate.editor.popup.PopupHost
import app.notate.editor.ui.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Everything the pane needs around a selection: the box drawn above the page, the floating action bar, the colour and
 * thickness popups, and the long-press Paste menu. Main thread only.
 */
class SelectionUi(
    private val view: View,
    private val editor: DocumentEditor,
    val controller: SelectionController,
    private val clipboard: InkClipboard,
    private val popupHost: () -> PopupHost?,
    private val scope: CoroutineScope,
    private val viewport: () -> Viewport,
    private val layout: () -> DocumentLayout,
    /** "Link…": the pane opens its link dialog for a link over [bounds] of [page]. */
    private val onLink: (PageId, app.notate.core.geometry.Rect) -> Unit,
) : SelectionActions {
    private val density = view.resources.displayMetrics.density
    val layer = SelectionLayer(density)
    private val bar = SelectionActionBar(view.context, this)
    private var lastDirty: Rect? = null
    private val popups: PopupHost? get() = popupHost()

    val selection: Selection? get() = controller.selection.value

    fun draw(canvas: Canvas) {
        val sel = selection ?: return
        layer.draw(canvas, sel, viewport(), layout())
    }

    /** True when the point (layer-local px) is on the box or a handle of the selection on [page]. */
    fun hits(page: PageId, sx: Float, sy: Float): Boolean {
        val sel = selection ?: return false
        return sel.page == page && layer.hit(sel, viewport(), layout(), sx, sy) != null
    }

    /**
     * Brings the bar and the dirty rectangle up to date after the selection (or the view) changed. Returns the union of the
     * previous and new dirty rectangles (layer-local px) for the caller's refresh, or null when nothing was or is shown.
     */
    fun update(): Rect? {
        val sel = selection
        val now = sel?.let { layer.dirtyRect(it, viewport(), layout()) }
        val prev = lastDirty
        lastDirty = now
        if (sel == null || controller.isTransforming) {
            popups?.hideBar()
        } else {
            val r = layer.screenRect(sel, viewport(), layout())
            val local = Rect(r.left.toInt(), (r.top - SelectionLayer.ROTATE_DP * density - SelectionLayer.HANDLE_DP * density).toInt(), r.right.toInt(), r.bottom.toInt())
            bar.showLock(controller.items().any { it is app.notate.core.model.ImageItem && !it.locked })
            popups?.showBar(bar, view, local)
        }
        view.invalidate()
        return when {
            prev == null -> now
            now == null -> prev
            else -> Rect(prev).also { it.union(now) }
        }
    }

    fun release() {
        controller.clear()
        popups?.hideBar()
    }

    // --- SelectionActions ---

    override fun cut() = controller.cut(clipboard)
    override fun copy() = controller.copy(clipboard)
    override fun duplicate() = controller.duplicate()
    override fun delete() = controller.delete()
    override fun front() = controller.bringToFront()
    override fun back() = controller.sendToBack()
    override fun lock() = controller.lockImages()
    override fun link() { selection?.let { onLink(it.page, it.bounds) } }

    override fun color(anchor: View) {
        val host = popups ?: return
        val first = controller.items().firstNotNullOfOrNull {
            when (it) { is Stroke -> it.style.color; is Shape -> it.style.color; is TextBox -> it.color; else -> null }
        } ?: return
        ColorPalettePopup(host, anchor, first, first ushr 24) { controller.recolor(it) }.show()
    }

    override fun thickness(anchor: View) {
        val host = popups ?: return
        val first = controller.items().firstNotNullOfOrNull {
            when (it) { is Stroke -> it.style.widthPt; is Shape -> it.style.widthPt; else -> null }
        } ?: return
        SelectionWidthPopup(host, anchor, first / app.notate.core.model.Units.PT_PER_MM) { controller.rewidth(it) }.show()
    }

    // --- Long-press menu ---

    /**
     * Opens the long-press menu at the window point ([windowX], [windowY]); "Paste" drops the clipboard centred on
     * ([pageX], [pageY]) of [page] and selects it. "Insert text" and "Insert image" arrive with R2-7.
     */
    fun showPasteMenu(page: PageId, pageX: Float, pageY: Float, windowX: Int, windowY: Int) {
        val host = popups ?: return
        val enabled = clipboard.hasContent.value
        val entries = listOf(
            MenuEntry(view.context.getString(R.string.menu_paste), enabled) {
                scope.launch {
                    if (editor.content(page) == null) return@launch
                    val ids = clipboard.paste(editor, page, Vec2(pageX, pageY))
                    controller.selectIds(page, ids)
                }
            },
        )
        MoreMenu(host, null, entries, view.context, windowPoint = Point(windowX - view.dp(8), windowY - view.dp(8))).show()
    }
}
