package app.notate.editor.pages

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.notate.core.doc.pages.TemplateCatalog
import app.notate.core.model.Background
import app.notate.editor.R
import app.notate.editor.popup.PopupHost
import app.notate.editor.popup.PopupUi
import app.notate.editor.ui.dp
import app.notate.editor.ui.invertingBackground

/**
 * "Page template" chooser: the paper kinds of [TemplateCatalog] as rows plus an "Apply to all pages" check. Picking a row
 * closes the popup and reports the paper and the check state. Main thread only.
 */
class TemplatePicker(
    private val host: PopupHost,
    private val context: Context,
    private val current: Background?,
    private val onPick: (Background.Paper, Boolean) -> Unit,
) {
    fun show() {
        val col = PopupUi.column(context)
        col.addView(PopupUi.text(context, context.getString(R.string.template_title), 18f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        var all = false
        val check = PopupUi.button(context, "", {}).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            text = label(false)
            background = invertingBackground()
        }
        check.setOnClickListener {
            all = !all
            check.text = label(all)
            host.refresh()
        }
        for (e in TemplateCatalog.entries) {
            val row = PopupUi.button(context, name(e.id)) {
                host.dismiss()
                onPick(e.background, all)
            }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                minWidth = dp(240)
                isSelected = current == e.background
                setTextColor(
                    android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                        intArrayOf(Color.WHITE, Color.WHITE, Color.BLACK),
                    ),
                )
            }
            col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(48)))
        }
        col.addView(check, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(48)))
        host.show(null as View?, col, app.notate.editor.popup.Placement.CENTER)
    }

    private fun label(on: Boolean) = (if (on) "[x] " else "[ ] ") + context.getString(R.string.template_apply_all)

    private fun name(id: String): String = context.getString(
        when (id) {
            "blank" -> R.string.template_blank
            "lined" -> R.string.template_lined
            "wide_lined" -> R.string.template_wide_lined
            "grid_5mm" -> R.string.template_grid_5mm
            "dots_5mm" -> R.string.template_dots_5mm
            "engineering" -> R.string.template_engineering
            "cornell" -> R.string.template_cornell
            else -> R.string.template_isometric
        },
    )
}
