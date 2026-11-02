package app.notate.editor.pane

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import app.notate.core.model.DocId
import app.notate.core.model.LinkTarget
import app.notate.core.model.PageId
import app.notate.editor.R
import app.notate.editor.popup.Placement
import app.notate.editor.popup.PopupHandle
import app.notate.editor.popup.PopupUi
import app.notate.editor.ui.dp

/**
 * "Link…": choose where a new link goes — a page of this notebook, another document (with a page number), or a web address.
 * A popup of the window (a real dialog would take the window focus and switch the pen driver off). Main thread only.
 */
class LinkDialog(
    private val host: PaneHost,
    private val docId: DocId,
    private val pages: List<PageId>,
    /** Opens the document picker for "Another document"; null hides that choice. */
    private val pickDocument: ((onPick: (DocId) -> Unit) -> Unit)?,
    /** Page id of page number [Int] (1-based) of another document, or null when it has no such page. */
    private val resolvePage: (DocId, Int, (PageId?) -> Unit) -> Unit,
    private val onTarget: (LinkTarget) -> Unit,
) {
    private var handle: PopupHandle? = null

    fun show(context: Context) {
        val box = PopupUi.column(context)
        box.minimumWidth = context.dp(320)
        box.addView(PopupUi.text(context, context.getString(R.string.link_title), 20f))
        box.addView(PopupUi.button(context, context.getString(R.string.link_this_notebook)) { askNumber(context, R.string.link_page_number) { n -> pageLink(n) } })
        pickDocument?.let { pick ->
            box.addView(PopupUi.button(context, context.getString(R.string.link_other_document)) {
                close()
                pick { doc -> askNumber(context, R.string.link_page_number) { n -> resolvePage(doc, n) { p -> p?.let { onTarget(LinkTarget.Page(doc, it)) } } } }
            })
        }
        box.addView(PopupUi.button(context, context.getString(R.string.link_web_address)) { askText(context) })
        handle = host.showPopup(null, box, Placement.CENTER)
    }

    private fun pageLink(n: Int) {
        val page = pages.getOrNull(n - 1) ?: return
        close()
        onTarget(LinkTarget.Page(docId, page))
    }

    private fun askNumber(context: Context, hint: Int, done: (Int) -> Unit) {
        val field = field(context, context.getString(hint), InputType.TYPE_CLASS_NUMBER)
        show(context, field) { s -> s.toIntOrNull()?.takeIf { it >= 1 }?.let { close(); done(it) } }
    }

    private fun askText(context: Context) {
        val field = field(context, context.getString(R.string.link_url_hint), InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT)
        show(context, field) { s ->
            var url = s.trim()
            if (url.isNotEmpty() && !url.contains("://")) url = "https://$url"
            if (app.notate.core.doc.links.LinkResolver.isSafeUrl(url)) { close(); onTarget(LinkTarget.Url(url)) }
        }
    }

    private fun field(context: Context, hint: String, type: Int) = EditText(context).apply {
        this.hint = hint
        inputType = type
        setSingleLine(true)
        setTextColor(Color.BLACK)
    }

    private fun show(context: Context, field: EditText, ok: (String) -> Unit) {
        val box = PopupUi.column(context)
        box.minimumWidth = context.dp(320)
        box.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(52)))
        val row = LinearLayout(context)
        row.addView(PopupUi.button(context, context.getString(R.string.link_cancel)) { close() }, LinearLayout.LayoutParams(0, context.dp(48), 1f))
        row.addView(PopupUi.button(context, context.getString(R.string.link_ok)) { ok(field.text.toString()) }, LinearLayout.LayoutParams(0, context.dp(48), 1f))
        box.addView(row)
        handle?.dismiss()
        handle = host.showPopup(null, box, Placement.CENTER)
    }

    private fun close() {
        handle?.dismiss()
        handle = null
    }
}
