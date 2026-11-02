package app.notate.editor.popup

import android.view.View
import android.widget.LinearLayout
import app.notate.core.doc.tools.ToolController
import app.notate.core.model.ToolKind
import app.notate.editor.toolbar.ToolEdits
import app.notate.editor.ui.dp

/** Thickness slider for the selected width slot of the pen or highlighter, with the value shown as "0.40 mm". */
class WidthPopup(
    private val host: PopupHost,
    private val anchor: View?,
    private val controller: ToolController,
    private val kind: ToolKind,
) {
    fun show() {
        val ctx = anchor?.context ?: return
        val slots = ToolEdits.slots(controller.state.value, kind)
        val mm = ToolEdits.mm(slots.widthsPt[slots.width.coerceIn(0, slots.widthsPt.size - 1)])
        val (lo, hi) = if (kind == ToolKind.HIGHLIGHTER) 1f to 12f else PenSettingsPopup.MIN_MM to PenSettingsPopup.MAX_MM
        val content = PopupUi.column(ctx)
        val label = PopupUi.text(ctx, PenSettingsPopup.mmText(mm))
        content.addView(label)
        content.addView(
            SliderView(ctx, lo, hi, mm.coerceIn(lo, hi), onLive = { label.text = PenSettingsPopup.mmText(it) }, onCommit = {
                ToolEdits.setWidthMm(controller, kind, it)
            }),
            LinearLayout.LayoutParams(ctx.dp(260), LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        host.show(anchor, content)
    }
}
