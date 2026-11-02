package app.notate.editor.chrome

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import app.notate.core.doc.save.SaveState
import app.notate.editor.R
import app.notate.editor.toolbar.ToolButton
import app.notate.editor.ui.dp

/**
 * The 40 dp tab row. For now it shows Home, the one open document as the current tab (title, unsaved dot, close) and a
 * disabled "+"; real tabs arrive with R5-1. Main thread only.
 */
class TabStripView(context: Context, onHome: () -> Unit, onClose: () -> Unit) : LinearLayout(context) {
    private val title = TextView(context)
    private val status = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = LayerDrawable(arrayOf(ColorDrawable(Color.WHITE), GradientDrawable().apply { setColor(Color.BLACK) })).also {
            it.setLayerInset(1, 0, dp(HEIGHT_DP - 1), 0, 0)
        }
        addView(small(R.drawable.ic_home, R.string.tab_home).onClick(onHome))
        val tab = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
            background = LayerDrawable(arrayOf(ColorDrawable(Color.WHITE), GradientDrawable().apply { setColor(Color.BLACK) })).also {
                it.setLayerInset(1, 0, dp(HEIGHT_DP - 4), 0, 0)    // 3 dp bar under the current tab
            }
        }
        title.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.BLACK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(MAX_TITLE_DP)
        }
        status.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.BLACK)
            setPadding(dp(6), 0, dp(2), 0)
        }
        tab.addView(title)
        tab.addView(status)
        tab.addView(small(R.drawable.ic_close, R.string.tab_close).onClick(onClose))
        addView(tab, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(small(R.drawable.ic_add, R.string.tab_new).also { it.reason = context.getString(R.string.coming_soon) })
    }

    private fun small(icon: Int, label: Int): ToolButton =
        ToolButton(context, icon, context.getString(label), size = HEIGHT_DP).also {
            it.layoutParams = LayoutParams(dp(HEIGHT_DP), dp(HEIGHT_DP))
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY))
    }

    fun setTitle(text: String) { title.text = text }

    /** "●" while edits wait to be written, "!" when writing failed, nothing when saved. */
    fun setSaveState(state: SaveState) {
        status.text = when (state) {
            SaveState.Clean -> ""
            is SaveState.Pending, SaveState.Writing -> "●"
            is SaveState.Error -> "!"
        }
    }

    companion object {
        const val HEIGHT_DP = 40
        private const val MAX_TITLE_DP = 220
    }
}
