package app.notate.editor.popup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.notate.editor.ui.Ink
import app.notate.editor.ui.dp
import app.notate.editor.ui.invertingBackground

/** Small view builders shared by the popups (code-built views: no layout XML, no theme dependence). */
internal object PopupUi {
    fun text(context: Context, text: CharSequence, sp: Float = 16f, color: Int = Color.BLACK): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
        }

    fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
    }

    /** A flat text button: inverts while pressed. */
    fun button(context: Context, label: CharSequence, onClick: () -> Unit): TextView =
        text(context, label).apply {
            gravity = Gravity.CENTER
            minHeight = context.dp(44)
            minWidth = context.dp(88)
            setPadding(context.dp(12), 0, context.dp(12), 0)
            background = invertingBackground()
            setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(Color.WHITE, Color.BLACK),
            ))
            setOnClickListener { onClick() }
        }
}

/**
 * Horizontal slider drawn by hand (no theme, no animation). [onLive] fires while dragging, [onCommit] once when the finger or
 * pen lifts. Values run from [min] to [max].
 */
class SliderView(
    context: Context,
    private val min: Float,
    private val max: Float,
    value: Float,
    private val onLive: (Float) -> Unit,
    private val onCommit: (Float) -> Unit,
) : View(context) {
    var value: Float = value.coerceIn(min, max)
        private set
    private val track = Paint().apply { color = Ink.BLACK; strokeWidth = dp(2).toFloat(); style = Paint.Style.STROKE }
    private val knob = Paint().apply { color = Ink.BLACK; style = Paint.Style.FILL; isAntiAlias = true }
    private val knobHole = Paint().apply { color = Ink.WHITE; style = Paint.Style.FILL; isAntiAlias = true }

    init {
        minimumHeight = dp(44)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(240), widthMeasureSpec), resolveSize(dp(44), heightMeasureSpec))
    }

    private fun knobX(): Float {
        val l = dp(KNOB_R).toFloat()
        val frac = if (max > min) (value - min) / (max - min) else 0f
        return l + frac * (width - 2 * l)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val l = dp(KNOB_R).toFloat()
        canvas.drawLine(l, cy, width - l, cy, track)
        val x = knobX()
        canvas.drawCircle(x, cy, dp(KNOB_R).toFloat(), knob)
        canvas.drawCircle(x, cy, dp(KNOB_R - 4).toFloat(), knobHole)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                set(ev.x)
                onLive(value)
            }
            MotionEvent.ACTION_UP -> {
                set(ev.x)
                onLive(value)
                onCommit(value)
            }
        }
        return true
    }

    private fun set(x: Float) {
        val l = dp(KNOB_R).toFloat()
        val span = (width - 2 * l).coerceAtLeast(1f)
        val frac = ((x - l) / span).coerceIn(0f, 1f)
        value = min + frac * (max - min)
        invalidate()
    }

    private companion object { const val KNOB_R = 14 }
}
