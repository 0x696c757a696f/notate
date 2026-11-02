package app.notate.editor.select

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import app.notate.editor.R
import app.notate.editor.popup.PopupUi

/** What the floating bar over a selection can do. */
interface SelectionActions {
    fun cut()
    fun copy()
    fun duplicate()
    fun delete()
    /** [anchor] is the button, for placing the colour popup. */
    fun color(anchor: View)
    fun thickness(anchor: View)
    fun front()
    fun back()
}

/**
 * Row of text buttons (this one place uses words: there are too many actions for icons to stay clear). "Link…" arrives with
 * R2-7 and stays hidden until then.
 */
class SelectionActionBar(context: Context, private val actions: SelectionActions) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        add(R.string.sel_cut) { actions.cut() }
        add(R.string.sel_copy) { actions.copy() }
        add(R.string.sel_duplicate) { actions.duplicate() }
        add(R.string.sel_delete) { actions.delete() }
        addAnchored(R.string.sel_color) { actions.color(it) }
        addAnchored(R.string.sel_thickness) { actions.thickness(it) }
        add(R.string.sel_front) { actions.front() }
        add(R.string.sel_back) { actions.back() }
    }

    private fun add(label: Int, onClick: () -> Unit) {
        addView(PopupUi.button(context, context.getString(label), onClick).apply { minWidth = 0 })
    }

    private fun addAnchored(label: Int, onClick: (View) -> Unit) {
        lateinit var b: View
        b = PopupUi.button(context, context.getString(label)) { onClick(b) }.apply { minWidth = 0 }
        addView(b)
    }
}
