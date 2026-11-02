package app.notate.editor.popup

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.notate.editor.ui.dp

/** One row of the More menu or another short list popup. */
class MenuEntry(val label: String, val enabled: Boolean = true, val action: () -> Unit)

/**
 * The More menu (also used for short chooser lists): a vertical list of text rows in a popup; a tap runs the action after
 * closing the popup. Without an [anchor] (or with [centered]) the popup opens in the middle of the window; [context] is then
 * required.
 */
class MoreMenu(
    private val host: PopupHost,
    private val anchor: View?,
    private val entries: List<MenuEntry>,
    private val context: Context? = null,
    private val centered: Boolean = false,
    /** Window position for the menu's top-left corner (long-press menus); overrides [anchor] placement. */
    private val windowPoint: android.graphics.Point? = null,
) {
    fun show() {
        val ctx = anchor?.context ?: context ?: return
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (e in entries) {
            val row = PopupUi.button(ctx, e.label) {
                if (e.enabled) {
                    host.dismiss()
                    e.action()
                }
            }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                minWidth = ctx.dp(220)
                if (!e.enabled) setTextColor(app.notate.editor.ui.Ink.DISABLED)
            }
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(52)))
        }
        if (windowPoint != null) host.show(null, list, Placement.POINT, windowPoint) else if (centered || anchor == null) host.show(null, list, Placement.CENTER) else host.show(anchor, list)
    }
}
