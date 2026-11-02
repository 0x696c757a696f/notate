package app.notate.editor.debug

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.TextView

/** Overlay with diagnostics (02-architecture §9); refreshes twice a second while shown. Main thread only. */
class DebugHud(context: Context, private val lines: () -> List<String>) : TextView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            text = lines().joinToString("\n")
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.MONOSPACE
        setTextColor(Color.BLACK)
        setBackgroundColor(0xE6FFFFFF.toInt())
        val pad = (4 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        visibility = GONE
    }

    fun setShown(shown: Boolean) {
        handler.removeCallbacks(tick)
        visibility = if (shown) VISIBLE else GONE
        if (shown) handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private companion object { const val PERIOD_MS = 500L }
}
