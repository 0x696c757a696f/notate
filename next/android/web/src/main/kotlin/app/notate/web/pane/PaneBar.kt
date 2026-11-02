package app.notate.web.pane

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import app.notate.editor.toolbar.ToolButton
import app.notate.editor.ui.dp
import app.notate.web.R

/**
 * The 32 dp bar above a web page (04 §4): chapter title, previous / next chapter, contents and the Write | Interact switch.
 * Icon buttons only. Main thread only.
 */
class PaneBar(
    context: Context,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onContents: () -> Unit,
    private val onMode: (interact: Boolean) -> Unit,
) : LinearLayout(context) {
    private val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.BLACK)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(8), 0, context.dp(4), 0)
    }
    private val previous = button(app.notate.editor.R.drawable.ic_back, R.string.web_previous) { onPrevious() }
    private val next = button(app.notate.editor.R.drawable.ic_back, R.string.web_next) { onNext() }.apply { scaleX = -1f }
    private val contents = button(R.drawable.ic_menu, R.string.web_contents) { onContents() }
    private val write = button(app.notate.editor.R.drawable.ic_pen, R.string.web_write) { onMode(false) }
    private val interact = button(R.drawable.ic_touch_app, R.string.web_interact) { onMode(true) }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        addView(previous, params())
        addView(next, params())
        addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(contents, params())
        addView(write, params())
        addView(interact, params())
        minimumHeight = context.dp(HEIGHT_DP)
        setMode(false)
    }

    private fun params() = LayoutParams(context.dp(44), context.dp(HEIGHT_DP))

    private fun button(icon: Int, label: Int, onClick: () -> Unit) =
        ToolButton(context, icon, context.getString(label), size = HEIGHT_DP).onClick(onClick).apply { layoutParams = params() }

    private var maximize: ToolButton? = null
    private var close: ToolButton? = null

    /** Adds (or removes, when both are null) the maximize and close buttons that a split window needs. */
    fun setSplitControls(onMaximize: (() -> Unit)?, onClose: (() -> Unit)?) {
        maximize?.let { removeView(it) }
        close?.let { removeView(it) }
        maximize = null
        close = null
        if (onMaximize != null) {
            maximize = button(app.notate.editor.R.drawable.ic_open_in_full, app.notate.editor.R.string.pane_maximize) { onMaximize() }.also { addView(it, params()) }
        }
        if (onClose != null) {
            close = button(app.notate.editor.R.drawable.ic_close, app.notate.editor.R.string.pane_close) { onClose() }.also { addView(it, params()) }
        }
    }

    fun setTitle(text: String) {
        title.text = text
    }

    fun setChapterNav(hasPrevious: Boolean, hasNext: Boolean) {
        previous.reason = if (hasPrevious) null else context.getString(R.string.web_no_previous)
        next.reason = if (hasNext) null else context.getString(R.string.web_no_next)
    }

    fun setMode(interactMode: Boolean) {
        write.isSelected = !interactMode
        interact.isSelected = interactMode
    }

    companion object {
        const val HEIGHT_DP = 32
    }
}
