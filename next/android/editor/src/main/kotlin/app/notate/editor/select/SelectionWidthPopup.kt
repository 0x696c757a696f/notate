package app.notate.editor.select

import android.view.View
import android.widget.LinearLayout
import app.notate.core.model.Units
import app.notate.editor.popup.PenSettingsPopup
import app.notate.editor.popup.PopupHost
import app.notate.editor.popup.PopupUi
import app.notate.editor.popup.SliderView
import app.notate.editor.ui.dp

/** Thickness slider for the selected strokes and shapes; one undo step when the finger or pen lifts. */
class SelectionWidthPopup(
    private val host: PopupHost,
    private val anchor: View,
    private val currentMm: Float,
    private val onCommit: (widthPt: Float) -> Unit,
) {
    fun show() {
        val ctx = anchor.context
        val content = PopupUi.column(ctx)
        val v = currentMm.coerceIn(PenSettingsPopup.MIN_MM, PenSettingsPopup.MAX_MM)
        val label = PopupUi.text(ctx, PenSettingsPopup.mmText(v))
        content.addView(label)
        content.addView(
            SliderView(ctx, PenSettingsPopup.MIN_MM, PenSettingsPopup.MAX_MM, v, onLive = { label.text = PenSettingsPopup.mmText(it) }, onCommit = {
                onCommit(Units.mm(it))
            }),
            LinearLayout.LayoutParams(ctx.dp(260), LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        host.show(anchor, content)
    }
}
