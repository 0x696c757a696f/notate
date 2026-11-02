package app.notate.editor.split

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.notate.editor.R
import app.notate.editor.toolbar.ToolButton
import app.notate.editor.ui.dp

/**
 * What a pane looks like inside a split window (04 §6): a 3 dp black strip when it has the focus, a 32 dp bar with the title,
 * the page and the maximize / close buttons, and the pane's own view below. Outside a split only [content] shows. Panes that
 * draw their own bar (web pages) get the strip but no second bar. Main thread only.
 */
class PaneFrame(context: Context, private val ownsBar: Boolean) : LinearLayout(context) {
    /** Where the pane's view (and its page indicator) go. */
    val content = FrameLayout(context)

    private val strip = View(context)
    private val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(8), 0, context.dp(4), 0)
    }
    private val page = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(4), 0, context.dp(8), 0)
    }
    private val maximize = ToolButton(context, R.drawable.ic_open_in_full, context.getString(R.string.pane_maximize), size = BAR_DP)
    private val close = ToolButton(context, R.drawable.ic_close, context.getString(R.string.pane_close), size = BAR_DP)
    private val bar = LinearLayout(context)

    var split: Boolean = false
        private set
    var focused: Boolean = false
        private set

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.WHITE)
        bar.addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bar.addView(page, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        bar.addView(maximize, LayoutParams(dp(BUTTON_DP), dp(BAR_DP)))
        bar.addView(close, LayoutParams(dp(BUTTON_DP), dp(BAR_DP)))
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, dp(STRIP_DP)))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(BAR_DP)))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        applyState()
    }

    /** Shows the bar and focus strip ([split] true) with the given buttons, or only the pane. */
    fun setSplit(split: Boolean, onMaximize: () -> Unit = {}, onClose: () -> Unit = {}) {
        this.split = split
        maximize.onClick(onMaximize)
        close.onClick(onClose)
        applyState()
    }

    fun setFocused(focused: Boolean) {
        if (this.focused == focused) return
        this.focused = focused
        applyState()
    }

    fun setTitle(text: String) {
        if (title.text.toString() != text) title.text = text
    }

    /** [index] is 0-based; a count of 0 hides the page. */
    fun setPage(index: Int, count: Int) {
        val t = if (count > 0) "${index + 1}/$count" else ""
        if (page.text.toString() != t) page.text = t
    }

    private fun applyState() {
        strip.visibility = if (split) VISIBLE else GONE
        strip.setBackgroundColor(if (focused) Color.BLACK else Color.WHITE)
        bar.visibility = if (split && !ownsBar) VISIBLE else GONE
        val ink = if (focused) Color.BLACK else GRAY
        title.setTextColor(ink)
        page.setTextColor(ink)
    }

    /** True when ([x], [y]) in window coordinates is on the bar or the focus strip, which handle their own touches. */
    fun hitsBar(x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        return x >= loc[0] && x < loc[0] + width && y >= loc[1] && y < loc[1] + dp(BAR_DP + STRIP_DP)
    }

    /** Test hook: the colour of the title text. */
    internal fun titleColor(): Int = title.currentTextColor

    internal fun barVisible(): Boolean = bar.visibility == VISIBLE

    internal fun stripVisible(): Boolean = strip.visibility == VISIBLE

    internal fun stripBlack(): Boolean = (strip.background as? android.graphics.drawable.ColorDrawable)?.color == Color.BLACK

    companion object {
        const val GRAY = 0xFF808080.toInt()
        const val BAR_DP = 32
        const val STRIP_DP = 3
        private const val BUTTON_DP = 44
    }
}
