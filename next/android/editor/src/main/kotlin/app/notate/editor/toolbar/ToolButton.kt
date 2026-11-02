package app.notate.editor.toolbar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.ImageButton
import android.widget.ImageView
import app.notate.editor.ui.Ink
import app.notate.editor.ui.Tooltip
import app.notate.editor.ui.dp
import app.notate.editor.ui.invertingBackground

/**
 * Toolbar icon button: 48 dp target with a 28 dp icon. States: normal, selected (black, white icon), pressed (inverted) and
 * unavailable (38 % grey; it stays clickable so that a long press can explain why). Long press shows [label], or [reason]
 * when unavailable. Main thread only.
 */
class ToolButton(context: Context, icon: Int, val label: String, size: Int = 48) : ImageButton(context) {
    private var action: (() -> Unit)? = null
    private var longAction: (() -> Unit)? = null

    /** Why the button cannot be used, or null when it can. */
    var reason: String? = null
        set(value) {
            field = value
            applyTint()
        }

    val available: Boolean get() = reason == null

    init {
        setImageResource(icon)
        contentDescription = label
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp((size - ICON_DP) / 2)
        setPadding(pad, pad, pad, pad)
        background = invertingBackground()
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(size), dp(48))
        setOnClickListener { if (available) action?.invoke() }
        setOnLongClickListener {
            val r = reason
            if (r == null && longAction != null) longAction?.invoke() else Tooltip.show(this, r ?: label)
            true
        }
        applyTint()
    }

    fun onClick(block: () -> Unit): ToolButton { action = block; return this }

    /** Replaces the tooltip by [block] for an available button (e.g. the Axes kind chooser). */
    fun onLongClick(block: () -> Unit): ToolButton { longAction = block; return this }

    private fun applyTint() {
        imageTintList = if (reason != null) {
            ColorStateList.valueOf(Ink.DISABLED)
        } else {
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(Color.WHITE, Color.WHITE, Color.BLACK),
            )
        }
    }

    companion object {
        const val ICON_DP = 28
    }
}
