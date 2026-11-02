package app.notate.editor.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView

/**
 * A one-line hint under a view, shown on long press and gone after a moment. A non-focusable [PopupWindow] does not take
 * window focus from the editor, so the pen driver is not disturbed. No animation. Main thread only.
 */
object Tooltip {
    private val main = Handler(Looper.getMainLooper())
    private var current: PopupWindow? = null
    private val hide = Runnable { dismiss() }

    fun show(anchor: View, text: CharSequence) {
        dismiss()
        if (!anchor.isAttachedToWindow) return
        val label = TextView(anchor.context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setPadding(anchor.dp(10), anchor.dp(6), anchor.dp(10), anchor.dp(6))
        }
        val w = PopupWindow(label, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        w.isFocusable = false
        w.isTouchable = false
        w.animationStyle = 0
        try {
            w.showAsDropDown(anchor, 0, 0)
        } catch (e: RuntimeException) {
            return      // the window went away while we were showing
        }
        current = w
        main.postDelayed(hide, SHOW_MS)
    }

    fun dismiss() {
        main.removeCallbacks(hide)
        current?.dismiss()
        current = null
    }

    private const val SHOW_MS = 1500L
}
