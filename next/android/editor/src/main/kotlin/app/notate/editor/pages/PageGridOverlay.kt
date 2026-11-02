package app.notate.editor.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.editor.R
import app.notate.editor.popup.MenuEntry
import app.notate.editor.popup.MoreMenu
import app.notate.editor.popup.PopupHost
import app.notate.editor.popup.PopupUi
import app.notate.editor.toolbar.ToolButton
import app.notate.editor.ui.Ink
import app.notate.editor.ui.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** What the grid asks of the editor window. */
interface PageGridHost {
    /** Jump to [page] (the grid closes first). */
    fun onJump(page: PageId)

    /** Done pressed. */
    fun onClose()

    /** "Change template…" for [page]. */
    fun onChangeTemplate(page: PageId)
}

/**
 * The page grid (04 §3.4): a full-pane overlay with a top bar (Pages | All / Bookmarks | + | Done) over [PageGridView].
 * It is a view of the current window, not an activity; the window blocks the pen while it is up. Main thread only.
 */
class PageGridOverlay(
    context: Context,
    private val editor: DocumentEditor,
    private val thumbs: ThumbnailStore,
    private val actions: PageActions,
    private val popups: PopupHost,
    private val host: PageGridHost,
    scope: CoroutineScope,
    startPage: PageId?,
) : FrameLayout(context) {
    val grid: PageGridView
    private val all: TextView
    private val bookmarks: TextView
    private val scroll: ScrollView
    private val job: Job

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        bar.addView(PopupUi.text(context, context.getString(R.string.pages_title), 20f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        bar.addView(View(context), LinearLayout.LayoutParams(dp(24), 1))
        all = segment(R.string.pages_all) { setFilter(false) }
        bookmarks = segment(R.string.pages_bookmarks) { setFilter(true) }
        bar.addView(all)
        bar.addView(bookmarks)
        bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(
            ToolButton(context, R.drawable.ic_add, context.getString(R.string.more_add_page)).onClick {
                actions.addAfter(editor.pages.value.lastOrNull()?.id)
            },
        )
        bar.addView(PopupUi.button(context, context.getString(R.string.pages_done)) { host.onClose() })
        column.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        column.addView(View(context).apply { setBackgroundColor(Ink.BLACK) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)))

        grid = PageGridView(context, object : PageGridView.Thumbs {
            override fun get(page: PageId): Bitmap? = thumbs.get(page)
            override fun request(info: PageInfo) = thumbs.request(info)
        })
        grid.listener = object : PageGridView.Listener {
            override fun onOpen(page: PageId) = host.onJump(page)
            override fun onMenu(page: PageId) = showMenu(page)
            override fun onMove(page: PageId, toIndex: Int) = actions.move(page, toIndex)
        }
        grid.current = startPage
        grid.pages = editor.pages.value
        scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isSmoothScrollingEnabled = false
            addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        renderFilter()

        job = editor.pages.onEach { grid.pages = it }.launchIn(scope)
        thumbs.onReady = { grid.invalidate() }
        if (startPage != null) scroll.post { scroll.scrollTo(0, grid.rowTop(startPage)) }
    }

    private fun segment(label: Int, onClick: () -> Unit): TextView =
        PopupUi.button(context, context.getString(label), onClick).apply {
            minWidth = dp(96)
            setTextColor(
                android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                    intArrayOf(Color.WHITE, Color.WHITE, Color.BLACK),
                ),
            )
        }

    private fun setFilter(bookmarksOnly: Boolean) {
        grid.bookmarksOnly = bookmarksOnly
        renderFilter()
    }

    private fun renderFilter() {
        all.isSelected = !grid.bookmarksOnly
        bookmarks.isSelected = grid.bookmarksOnly
    }

    private fun showMenu(page: PageId) {
        val marked = editor.pages.value.firstOrNull { it.id == page }?.bookmarked == true
        val entries = listOf(
            MenuEntry(context.getString(R.string.page_insert_before)) { actions.insertBefore(page) },
            MenuEntry(context.getString(R.string.page_insert_after)) { actions.addAfter(page) },
            MenuEntry(context.getString(R.string.page_duplicate)) { actions.duplicate(page) },
            MenuEntry(context.getString(R.string.page_change_template)) { host.onChangeTemplate(page) },
            MenuEntry(context.getString(if (marked) R.string.page_remove_bookmark else R.string.page_bookmark)) { actions.toggleBookmark(page) },
            MenuEntry(context.getString(R.string.page_delete)) { actions.delete(page) },
        )
        MoreMenu(popups, null, entries, context = context, centered = true).show()
    }

    /** Stops observing; call when the overlay is removed. */
    fun release() {
        job.cancel()
        thumbs.onReady = null
    }
}
