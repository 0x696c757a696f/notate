package app.notate.editor.outline

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.store.OutlineEntry
import app.notate.editor.R
import app.notate.editor.popup.PopupUi
import app.notate.editor.ui.Ink
import app.notate.editor.ui.dp

/** What the outline asks of the editor window. */
interface OutlineHost {
    /** Go to [page], scrolled to [yPt] (page pt from the top) when known; the overlay closes first. */
    fun onJump(page: PageId, yPt: Float?)

    /** Done pressed. */
    fun onClose()
}

/** One displayed line: a section header, an outline entry, or a bookmarked page. */
internal sealed interface OutlineRow {
    data class Header(val title: Int) : OutlineRow
    data class Entry(val title: String, val depth: Int, val page: PageId, val yPt: Float?, val pageNumber: Int) : OutlineRow
}

/** Builds the displayed rows; pure, so it is unit-tested without views. */
internal object OutlineRows {
    fun build(outline: List<OutlineEntry>, pages: List<PageInfo>, bookmarksLabel: (Int) -> String): List<OutlineRow> {
        val number = pages.withIndex().associate { (i, p) -> p.id to i + 1 }
        val byId = outline.associateBy { it.id }
        fun depth(e: OutlineEntry): Int {
            var d = 0
            var p = e.parentId
            while (p != null && d < MAX_DEPTH) { d++; p = byId[p]?.parentId }
            return d
        }
        val rows = ArrayList<OutlineRow>()
        val children = outline.groupBy { it.parentId }
        fun walk(parent: Long?) {
            for (e in children[parent].orEmpty().sortedWith(compareBy({ it.ord }, { it.id }))) {
                val page = e.page
                if (page != null && page in number) rows += OutlineRow.Entry(e.title, depth(e), page, e.yPt, number.getValue(page))
                walk(e.id)
            }
        }
        walk(null)
        if (rows.isNotEmpty()) rows.add(0, OutlineRow.Header(R.string.outline_title))
        val marks = pages.filter { it.bookmarked }
        if (marks.isNotEmpty()) {
            rows += OutlineRow.Header(R.string.outline_bookmarks)
            for (p in marks) rows += OutlineRow.Entry(bookmarksLabel(number.getValue(p.id)), 0, p.id, null, number.getValue(p.id))
        }
        return rows
    }

    private const val MAX_DEPTH = 32
}

/**
 * The outline panel (04 §3.3): a full-pane overlay listing the document's outline (PDF bookmarks, book contents), indented by
 * level, and the bookmarked pages under "Bookmarks". A tap jumps there. Top bar: "Outline" … "Done". Main thread only.
 */
class OutlineOverlay(
    context: Context,
    outline: List<OutlineEntry>,
    pages: List<PageInfo>,
    private val host: OutlineHost,
) : FrameLayout(context) {
    private val rows: List<OutlineRow> = OutlineRows.build(outline, pages) { context.getString(R.string.outline_page, it) }

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        bar.addView(PopupUi.text(context, context.getString(R.string.outline_title), 20f).apply { setTypeface(typeface, Typeface.BOLD) })
        bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(PopupUi.button(context, context.getString(R.string.outline_done)) { host.onClose() })
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        column.addView(View(context).apply { setBackgroundColor(Ink.BLACK) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        if (rows.isEmpty()) {
            column.addView(
                PopupUi.text(context, context.getString(R.string.outline_empty)).apply { setPadding(dp(16), dp(24), dp(16), dp(24)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        } else {
            val list = ListView(context).apply {
                divider = null
                isVerticalScrollBarEnabled = false
                overScrollMode = OVER_SCROLL_NEVER
                adapter = Adapter()
                setOnItemClickListener { _, _, position, _ ->
                    (rows[position] as? OutlineRow.Entry)?.let { host.onJump(it.page, it.yPt) }
                }
            }
            column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is OutlineRow.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is OutlineRow.Entry

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val row = rows[position]) {
            is OutlineRow.Header -> ((convertView as? TextView) ?: PopupUi.text(context, "", 14f, 0xFF555555.toInt()).apply {
                setPadding(dp(16), dp(14), dp(16), dp(6))
                setTypeface(typeface, Typeface.BOLD)
            }).also { it.setText(row.title) }
            is OutlineRow.Entry -> bindEntry((convertView as? LinearLayout) ?: newEntry(), row)
        }

        private fun newEntry(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            addView(PopupUi.text(context, "", 16f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(PopupUi.text(context, "", 14f, 0xFF555555.toInt()).apply { setPadding(dp(12), 0, 0, 0) })
        }

        private fun bindEntry(v: LinearLayout, row: OutlineRow.Entry): LinearLayout {
            v.setPadding(dp(16 + 20 * row.depth), dp(6), dp(16), dp(6))
            (v.getChildAt(0) as TextView).text = row.title
            (v.getChildAt(1) as TextView).text = context.getString(R.string.outline_page, row.pageNumber)
            return v
        }
    }
}
