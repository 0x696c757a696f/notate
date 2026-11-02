package app.notate.editor.pages

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import app.notate.editor.R
import app.notate.editor.ui.Ink
import app.notate.editor.ui.dp

/**
 * The "3 / 12" capsule in the bottom-right corner of the page area. It follows the page in view; two seconds after the last
 * change it turns light grey (it never hides). A tap opens the page grid. Main thread only.
 */
class PageIndicator(context: Context, onClick: () -> Unit) : TextView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val fade = Runnable { setTextColor(Ink.GRAY); background = capsule(Ink.GRAY) }
    private var index = -1
    private var count = -1

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(6), dp(14), dp(6))
        minHeight = dp(36)
        isClickable = true
        setOnClickListener { onClick() }
        wake()
    }

    /** Shows page [index] (0-based) of [count]; restarts the fade timer only when the text changes. */
    fun setPage(index: Int, count: Int) {
        if (index == this.index && count == this.count) return
        this.index = index
        this.count = count
        text = context.getString(R.string.page_indicator, index + 1, count)
        wake()
    }

    private fun wake() {
        handler.removeCallbacks(fade)
        setTextColor(Color.BLACK)
        background = capsule(Ink.BLACK)
        handler.postDelayed(fade, FADE_MS)
    }

    private fun capsule(stroke: Int) = GradientDrawable().apply {
        setColor(Color.WHITE)
        setStroke(dp(2), stroke)
        cornerRadius = dp(20).toFloat()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(fade)
        super.onDetachedFromWindow()
    }

    companion object {
        const val FADE_MS = 2000L
    }
}
