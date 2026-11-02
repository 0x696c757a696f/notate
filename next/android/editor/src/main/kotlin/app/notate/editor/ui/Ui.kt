package app.notate.editor.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View

/** Converts dp to px for this view's density. */
fun View.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Colours of the e-ink UI (04 §10). */
object Ink {
    const val BLACK = Color.BLACK
    const val WHITE = Color.WHITE
    const val DISABLED = 0x61000000
    const val GRAY = 0xFF9E9E9E.toInt()
    const val SECONDARY = 0xFF5F5F5F.toInt()
}

/** Transparent normally, black while pressed or selected (inverted button, no ripple, no animation). */
fun invertingBackground(): StateListDrawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Ink.BLACK))
    addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(Ink.BLACK))
    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
}

/** White box with a 2 dp black border, used by every popup. */
fun popupBackground(context: Context): GradientDrawable = GradientDrawable().apply {
    setColor(Ink.WHITE)
    setStroke(context.dp(2), Ink.BLACK)
}
