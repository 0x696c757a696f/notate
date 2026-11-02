package app.notate.editor.pane

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.doc.layout.Viewport
import app.notate.core.doc.links.LinkAction
import app.notate.core.doc.links.LinkResolver
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.doc.tools.ImageInsert
import app.notate.core.doc.tools.TextEdits
import app.notate.core.doc.tools.TextStyle
import app.notate.core.geometry.Rect
import app.notate.core.input.BlockReason
import app.notate.core.model.ImageItem
import app.notate.core.model.ItemId
import app.notate.core.model.LinkTarget
import app.notate.core.model.PageId
import app.notate.core.model.TextBox
import app.notate.core.model.edit.Edit
import app.notate.core.store.AssetKind
import app.notate.editor.EditorDeps
import app.notate.editor.R
import app.notate.editor.image.ImageImport
import app.notate.editor.popup.Placement
import app.notate.editor.popup.PopupUi
import app.notate.editor.text.TextEditorOverlay
import app.notate.editor.ui.dp
import app.notate.render.image.StaticLayoutMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Text boxes, images and links of a notebook pane (R2-7): the text editor overlay, image import, link taps and the
 * lock / unlock menu of images. Keeps [NotebookPane] free of this wiring. Main thread only.
 */
internal class ObjectsController(
    private val context: Context,
    private val handle: DocumentHandle,
    private val scope: CoroutineScope,
    private val deps: EditorDeps,
    private val host: PaneHost,
    private val pane: DocumentPane,
    private val view: View,
    private val viewport: () -> Viewport,
    private val frameOf: (PageId) -> Rect,
    private val pointAt: (Float, Float) -> PagePoint?,
    private val currentPage: () -> PageId?,
    private val goToPage: (PageId) -> Unit,
    private val refresh: () -> Unit,
) {
    private val editor: DocumentEditor get() = handle.editor
    private var overlay: TextEditorOverlay? = null
    private var styleJob: Job? = null
    private var editPage: PageId? = null
    private var editId: ItemId? = null
    private var editNew = false

    val editingText: Boolean get() = overlay != null

    // --- Text -----------------------------------------------------------------------------------------------

    /** Opens the editor over text box [id] of [page]; a box already under edit is committed first. */
    fun startTextEdit(page: PageId, id: ItemId, isNew: Boolean) {
        finishTextEdit()
        val box = editor.content(page)?.get(id) as? TextBox ?: return
        val parent = view.parent as? android.widget.FrameLayout ?: return
        // The toolbar shows (and changes) the style of the box being edited.
        deps.toolController.update { it.copy(textSizePt = box.fontSizePt, textBold = box.bold, textColor = box.color, textAlign = box.align) }
        editPage = page
        editId = id
        editNew = isNew
        val o = TextEditorOverlay(parent, box, { placementOf(page, box) }, StaticLayoutMetrics) { text -> commitText(text) }
        overlay = o
        host.arbiter.block(pane, BlockReason.POPUP, true)
        o.show()
        styleJob = scope.launch {
            deps.toolController.state.collect { s -> overlay?.applyStyle(TextStyle(s.textSizePt, s.textBold, s.textColor, s.textAlign)) }
        }
    }

    private fun placementOf(page: PageId, box: TextBox): TextEditorOverlay.Placement {
        val vp = viewport()
        val p = app.notate.editor.pane.PaneCoords.toScreen(vp, frameOf(page), box.frame.l, box.frame.t)
        return TextEditorOverlay.Placement(view.left + p.x, view.top + p.y, vp.scale)
    }

    /** Ends the text edit (tap outside, pen outside, Back): commits the text. No-op when nothing is edited. */
    fun finishTextEdit() {
        overlay?.finish()
    }

    private fun commitText(text: String) {
        val o = overlay ?: return
        val page = editPage
        val id = editId
        overlay = null
        styleJob?.cancel()
        host.arbiter.block(pane, BlockReason.POPUP, false)
        if (page != null && id != null) TextEdits.commit(editor, page, id, editNew, text, o.currentStyle(), StaticLayoutMetrics)
        editPage = null
        editId = null
        refresh()
    }

    /** The view moved or zoomed. */
    fun onViewportChanged() { overlay?.reposition() }

    private val loc = IntArray(2)

    /** Whether ([x], [y]) in window coordinates is on the text editor (its touches go to the EditText, not the pane). */
    fun hitsEditor(x: Float, y: Float): Boolean {
        val o = overlay ?: return false
        val parent = view.parent as? View ?: return false
        parent.getLocationInWindow(loc)
        return o.contains(x - loc[0], y - loc[1])
    }

    fun release() {
        styleJob?.cancel()
        overlay?.discard()
        overlay = null
    }

    // --- Images ---------------------------------------------------------------------------------------------

    /** Imports a picked image and places it in the middle of the page in view. */
    fun importImage(uri: Uri) {
        val page = currentPage() ?: return
        scope.launch {
            try {
                val prepared = withContext(deps.dispatchers.io) { ImageImport.prepare(context, uri, java.io.File(context.cacheDir, "imports")) }
                if (prepared == null) { deps.logger.e(TAG, "Could not read image $uri"); return@launch }
                val asset = try {
                    handle.putAsset(AssetKind.IMAGE, prepared.mime, prepared.file, prepared.ext)
                } finally {
                    prepared.file.delete()
                }
                val edit = ImageInsert.place(editor, page, asset, prepared.width, prepared.height, visibleOn(page)) ?: return@launch
                editor.apply(edit)
            } catch (e: Exception) {
                deps.logger.e(TAG, "Image import failed", e)
            }
        }
    }

    /** The part of [page] on screen, in page coordinates. */
    private fun visibleOn(page: PageId): Rect {
        val vp = viewport()
        val f = frameOf(page)
        val l = vp.scrollX - f.l
        val t = vp.scrollY - f.t
        return Rect(l, t, l + vp.widthPx / vp.scale, t + vp.heightPx / vp.scale)
    }

    /** Long press on an image: lock or unlock it. Returns true when a menu was shown. */
    fun onLongPress(x: Float, y: Float): Boolean {
        val p = pointAt(x, y) ?: return false
        val content = editor.content(p.page) ?: return false
        val img = content.query(Rect(p.x - 1f, p.y - 1f, p.x + 1f, p.y + 1f)).filterIsInstance<ImageItem>()
            .filter { it.frame.contains(p.x, p.y) }.maxByOrNull { it.order } ?: return false
        val label = context.getString(if (img.locked) R.string.image_unlock else R.string.image_lock)
        showMenu(listOf(label to { editor.apply(Edit.Items(p.page, listOf(img), listOf(img.copy(locked = !img.locked)))) }))
        return true
    }

    // --- Links ----------------------------------------------------------------------------------------------

    /** A finger tap at window-local ([x], [y]); true when it hit a link (and the link was followed). */
    fun onTap(x: Float, y: Float): Boolean {
        val p = pointAt(x, y) ?: return false
        val link = LinkResolver.hit(editor, p.page, p.x, p.y) ?: return false
        when (val a = LinkResolver.resolve(link.target, handle.id)) {
            is LinkAction.GoToPage -> goToPage(a.page)
            is LinkAction.OpenDocument -> host.openDocumentAt(a.doc, a.page)
            is LinkAction.OpenUrl -> confirmUrl(a.url)
        }
        return true
    }

    /** Adds a link over [frame] of [page]; the selection's "Link…" action (R2-4) calls this. */
    fun createLink(page: PageId, frame: Rect, target: LinkTarget) { LinkResolver.create(editor, page, frame, target) }

    private fun confirmUrl(url: String) {
        val box = PopupUi.column(context)
        box.minimumWidth = context.dp(320)
        box.addView(PopupUi.text(context, context.getString(R.string.link_open_browser), 18f))
        box.addView(PopupUi.text(context, url, 14f))
        lateinit var h: app.notate.editor.popup.PopupHandle
        val row = android.widget.LinearLayout(context)
        row.addView(PopupUi.button(context, context.getString(R.string.link_cancel)) { h.dismiss() }, android.widget.LinearLayout.LayoutParams(0, context.dp(48), 1f))
        row.addView(PopupUi.button(context, context.getString(R.string.link_open)) {
            h.dismiss()
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: android.content.ActivityNotFoundException) {
                deps.logger.e(TAG, "No browser for $url", e)
            }
        }, android.widget.LinearLayout.LayoutParams(0, context.dp(48), 1f))
        box.addView(row)
        h = host.showPopup(null, box, Placement.CENTER) ?: return
    }

    private fun showMenu(entries: List<Pair<String, () -> Unit>>) {
        val box = PopupUi.column(context)
        lateinit var h: app.notate.editor.popup.PopupHandle
        for ((label, action) in entries) {
            box.addView(PopupUi.button(context, label) { h.dismiss(); action() }.apply { minWidth = context.dp(220) })
        }
        h = host.showPopup(null, box, Placement.CENTER) ?: return
    }

    private companion object { const val TAG = "ObjectsController" }
}
