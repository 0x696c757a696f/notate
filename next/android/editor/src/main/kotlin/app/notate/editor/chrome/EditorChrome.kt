package app.notate.editor.chrome

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.notate.core.doc.tools.ToolController
import app.notate.core.input.EpdMode
import app.notate.core.input.EpdScheduler
import app.notate.core.input.EpdTiming
import app.notate.core.input.InkArbiter
import app.notate.editor.R
import app.notate.editor.popup.PopupHost
import app.notate.editor.toolbar.ToolButton
import app.notate.editor.toolbar.ToolbarActions
import app.notate.editor.toolbar.ToolbarView
import app.notate.editor.ui.dp
import app.notate.editor.ui.popupBackground

/**
 * Root of the editor window: tab row, toolbar and the pane area underneath, with popups drawn on top. In focus mode both
 * rows are hidden and a 48 dp floating pen button in the top-left corner brings them back. Main thread only.
 */
class EditorChrome(
    context: Context,
    controller: ToolController,
    arbiter: InkArbiter,
    private val epd: EpdScheduler<View>,
    xdpi: Float,
    actions: ToolbarActions,
    tabCallbacks: TabStripView.Callbacks,
) : FrameLayout(context) {
    val popups = PopupHost(this, arbiter, epd)
    val tabs = TabStripView(context, tabCallbacks)
    val toolbar = ToolbarView(context, controller, popups, xdpi, actions)

    /** Where the document pane goes. */
    val paneArea = FrameLayout(context)
    private val floating: ToolButton
    private val body = LinearLayout(context)

    /** Toolbar on the left as a vertical column (landscape only); otherwise the usual top row. */
    var toolbarLeft: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            layoutToolbar()
        }

    private fun layoutToolbar() {
        val left = toolbarLeft
        popups.dismiss()
        body.removeAllViews()
        body.orientation = if (left) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        toolbar.vertical = left
        if (left) {
            body.addView(toolbar, LinearLayout.LayoutParams(dp(ToolbarView.WIDTH_DP), LinearLayout.LayoutParams.MATCH_PARENT))
            body.addView(paneArea, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        } else {
            body.addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ToolbarView.HEIGHT_DP)))
            body.addView(paneArea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        onFocusChanged?.invoke(focusMode)
    }

    var focusMode: Boolean = false
        private set

    /** Called after focus mode changed, e.g. to move the pen driver's exclusion rectangles. */
    var onFocusChanged: ((Boolean) -> Unit)? = null

    init {
        setBackgroundColor(Color.WHITE)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(TabStripView.HEIGHT_DP)))
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        layoutToolbar()
        floating = ToolButton(context, R.drawable.ic_pen, context.getString(R.string.exit_focus_mode)).onClick { setFocusMode(false) }
        floating.background = popupBackground(context)
        floating.visibility = View.GONE
        addView(floating, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply { leftMargin = dp(FLOATING_MARGIN_DP); topMargin = dp(FLOATING_MARGIN_DP) })
    }

    /** Hides or shows the tab row and toolbar; the whole panel is refreshed once. */
    fun setFocusMode(on: Boolean) {
        if (on == focusMode) return
        focusMode = on
        popups.dismiss()
        val v = if (on) View.GONE else View.VISIBLE
        tabs.visibility = v
        toolbar.visibility = v
        floating.visibility = if (on) View.VISIBLE else View.GONE
        onFocusChanged?.invoke(on)
        epd.refresh(this, EpdMode.GC, null, EpdTiming.AFTER_DRAW)
    }

    /** Rectangles over [target] (its own pixel coordinates) where the pen must not write: floating button and popup. */
    fun excludeRectsFor(target: View): List<Rect> {
        val t = IntArray(2)
        val r = IntArray(2)
        target.getLocationInWindow(t)
        getLocationInWindow(r)
        val dx = r[0] - t[0]
        val dy = r[1] - t[1]
        val out = ArrayList<Rect>(2)
        if (focusMode) {
            // Fixed position (margins of the layout params): the button may not have been laid out yet when the pane is resized.
            val m = dp(FLOATING_MARGIN_DP)
            out.add(Rect(m + dx, m + dy, m + dp(48) + dx, m + dp(48) + dy))
        }
        popups.rect()?.let { out.add(Rect(it.left + dx, it.top + dy, it.right + dx, it.bottom + dy)) }
        return out
    }

    private companion object {
        const val FLOATING_MARGIN_DP = 8
    }
}
