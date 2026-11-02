package app.notate.editor.chrome

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.notate.core.doc.save.SaveState
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.workspace.Workspace
import app.notate.editor.R
import app.notate.editor.toolbar.ToolButton
import app.notate.editor.ui.dp
import app.notate.editor.ui.invertingBackground

/**
 * The 40 dp tab row: Home, the open tabs in a horizontally scrolling strip, and "+" (04 §6). A tab shows a kind icon, the
 * title (at most 18 characters), a status mark (● waiting to be written, ! writing failed, "Saving…" while it closes) and ×.
 * The tab of the focused pane is black on white; a tab that the other pane shows is underlined. Main thread only.
 */
class TabStripView(context: Context, private val callbacks: Callbacks) : LinearLayout(context) {
    interface Callbacks {
        fun onHome()
        fun onSelect(index: Int)
        fun onClose(index: Int)
        fun onLongPress(index: Int, anchor: View)
        fun onNew(anchor: View)
    }

    /** What one tab looks like; the strip is rebuilt only when this list changes. */
    internal data class TabModel(val title: String, val kind: DocKind, val status: String, val focused: Boolean, val underlined: Boolean)

    private val strip = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(strip, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
    }
    private var models: List<TabModel> = emptyList()
    private var scrolledTo = -1

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = LayerDrawable(arrayOf(ColorDrawable(Color.WHITE), GradientDrawable().apply { setColor(Color.BLACK) })).also {
            it.setLayerInset(1, 0, dp(HEIGHT_DP - 1), 0, 0)
        }
        addView(small(R.drawable.ic_home, R.string.tab_home).onClick { callbacks.onHome() })
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        val add = small(R.drawable.ic_add, R.string.tab_new)
        add.onClick { callbacks.onNew(add) }
        addView(add)
    }

    /** Shows [ws]'s tabs. [states] holds the save state of the documents that are open, [closing] those waiting to close. */
    fun render(ws: Workspace, states: Map<DocId, SaveState>, closing: Set<DocId>) {
        val focused = ws.focusedSlot?.tab
        val other = (if (ws.focusRight) ws.left else ws.right)?.tab
        val next = ws.tabs.mapIndexed { i, t ->
            val status = when {
                t.doc in closing -> context.getString(R.string.tab_saving)
                else -> when (states[t.doc]) {
                    is SaveState.Pending, SaveState.Writing -> "●"
                    is SaveState.Error -> "!"
                    else -> ""
                }
            }
            TabModel(shorten(t.title), t.kind, status, i == focused, i == other)
        }
        if (next == models) return
        models = next
        strip.removeAllViews()
        next.forEachIndexed { i, m -> strip.addView(tabView(i, m), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)) }
        val f = next.indexOfFirst { it.focused }
        if (f >= 0 && f != scrolledTo) {
            scrolledTo = f
            scroll.post { strip.getChildAt(f)?.let { v -> scroll.scrollTo(maxOf(0, v.left - dp(HEIGHT_DP)), 0) } }
        }
    }

    private fun tabView(index: Int, m: TabModel): View {
        val fg = if (m.focused) Color.WHITE else Color.BLACK
        val tab = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
            background = if (m.focused) ColorDrawable(Color.BLACK) else if (m.underlined) underline() else null
            isClickable = true
            setOnClickListener { callbacks.onSelect(index) }
            setOnLongClickListener { callbacks.onLongPress(index, this); true }
        }
        tab.addView(
            ImageView(context).apply {
                setImageResource(iconOf(m.kind))
                imageTintList = ColorStateList.valueOf(fg)
                contentDescription = null
            },
            LayoutParams(dp(20), dp(20)),
        )
        tab.addView(
            TextView(context).apply {
                text = m.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(fg)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(6), 0, 0, 0)
            },
        )
        if (m.status.isNotEmpty()) {
            tab.addView(
                TextView(context).apply {
                    text = m.status
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(fg)
                    setPadding(dp(6), 0, dp(2), 0)
                },
            )
        }
        val close = ToolButton(context, R.drawable.ic_close, context.getString(R.string.tab_close), size = HEIGHT_DP)
        close.layoutParams = LayoutParams(dp(HEIGHT_DP), dp(HEIGHT_DP))
        close.onClick { callbacks.onClose(index) }
        if (m.focused) {
            close.background = ColorDrawable(Color.BLACK)
            close.imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        tab.addView(close)
        return tab
    }

    private fun underline() = LayerDrawable(arrayOf(ColorDrawable(Color.WHITE), GradientDrawable().apply { setColor(Color.BLACK) })).also {
        it.setLayerInset(1, 0, dp(HEIGHT_DP - 4), 0, 0)      // 3 dp bar under the tab
    }

    private fun iconOf(kind: DocKind) = when (kind) {
        DocKind.NOTEBOOK -> R.drawable.ic_edit
        DocKind.PDF -> R.drawable.ic_description
        DocKind.WEB -> R.drawable.ic_menu_book
    }

    private fun small(icon: Int, label: Int): ToolButton =
        ToolButton(context, icon, context.getString(label), size = HEIGHT_DP).also {
            it.layoutParams = LayoutParams(dp(HEIGHT_DP), dp(HEIGHT_DP))
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY))
    }

    companion object {
        const val HEIGHT_DP = 40
        const val MAX_TITLE_CHARS = 18

        /** Titles longer than 18 characters are cut and end with "…". */
        fun shorten(title: String): String =
            if (title.length <= MAX_TITLE_CHARS) title else title.substring(0, MAX_TITLE_CHARS - 1).trimEnd() + "…"
    }
}
