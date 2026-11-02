package app.notate.editor.popup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import app.notate.editor.R
import app.notate.editor.ui.Ink
import app.notate.editor.ui.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Hue ring (drag the marker round the ring). Self-drawn. */
internal class HueRingView(context: Context, hue: Float, private val onHue: (Float) -> Unit) : View(context) {
    var hue: Float = hue
        set(value) { field = value; invalidate() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ink.BLACK; strokeWidth = dp(3).toFloat() }
    private val markerIn = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ink.WHITE; strokeWidth = dp(1).toFloat() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(dp(200), dp(200))

    private fun thickness() = dp(30).toFloat()
    private fun radius() = min(width, height) / 2f - thickness() / 2f - dp(4)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val colors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        ring.shader = SweepGradient(cx, cy, colors, null)
        ring.strokeWidth = thickness()
        canvas.drawCircle(cx, cy, radius(), ring)
        val a = Math.toRadians(hue.toDouble())
        val mx = cx + radius() * cos(a).toFloat()
        val my = cy + radius() * sin(a).toFloat()
        canvas.drawCircle(mx, my, thickness() / 2f, marker)
        canvas.drawCircle(mx, my, thickness() / 2f - dp(2), markerIn)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE || ev.actionMasked == MotionEvent.ACTION_UP) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val deg = Math.toDegrees(atan2((ev.y - height / 2f).toDouble(), (ev.x - width / 2f).toDouble())).toFloat()
            hue = (deg + 360f) % 360f
            onHue(hue)
        }
        return true
    }
}

/** A horizontal gradient bar (saturation or value); [leftColor] and [rightColor] give the colours at the ends. */
internal class GradientBarView(
    context: Context,
    value: Float,
    private val leftColor: () -> Int,
    private val rightColor: () -> Int,
    private val onValue: (Float) -> Unit,
) : View(context) {
    var value: Float = value
        set(v) { field = v; invalidate() }
    private val fill = Paint()
    private val edge = Paint().apply { style = Paint.Style.STROKE; color = Ink.BLACK; strokeWidth = dp(1).toFloat() }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ink.BLACK; strokeWidth = dp(3).toFloat() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(resolveSize(dp(200), widthMeasureSpec), dp(40))

    override fun onDraw(canvas: Canvas) {
        val top = dp(8).toFloat()
        val bottom = height - dp(8).toFloat()
        fill.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, leftColor(), rightColor(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, top, width.toFloat(), bottom, fill)
        canvas.drawRect(0f, top, width.toFloat(), bottom, edge)
        val x = value * width
        canvas.drawRect(x - dp(3), 2f, x + dp(3), height - 2f, knob)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE || ev.actionMasked == MotionEvent.ACTION_UP) {
            parent?.requestDisallowInterceptTouchEvent(true)
            value = (ev.x / width).coerceIn(0f, 1f)
            onValue(value)
        }
        return true
    }
}

/** Custom colour: hue ring, saturation bar, value bar, preview and "Select" (04 §3.2). */
class HsvPickerPopup(
    private val host: PopupHost,
    private val anchor: View?,
    initial: Int,
    private val alpha: Int,
    private val onPick: (Int) -> Unit,
) {
    private val hsv = FloatArray(3).also { Color.colorToHSV(initial or 0xFF000000.toInt(), it) }

    fun show() {
        val ctx = anchor?.context ?: return
        val content = PopupUi.column(ctx)
        val preview = View(ctx).apply { setBackgroundColor(current()) }
        lateinit var satBar: GradientBarView
        lateinit var valBar: GradientBarView
        fun update() {
            preview.setBackgroundColor(current())
            satBar.invalidate()
            valBar.invalidate()
        }
        val ring = HueRingView(ctx, hsv[0]) { h -> hsv[0] = h; update() }
        satBar = GradientBarView(ctx, hsv[1], { Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])) }, { Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])) }) {
            hsv[1] = it; update()
        }
        valBar = GradientBarView(ctx, hsv[2], { Color.BLACK }, { Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)) }) {
            hsv[2] = it; update()
        }
        content.addView(ring, LinearLayout.LayoutParams(ctx.dp(200), ctx.dp(200)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
        content.addView(satBar)
        content.addView(valBar)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(preview, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(PopupUi.button(ctx, ctx.getString(R.string.action_select)) { host.dismiss(); onPick(current()) })
        content.addView(row)
        host.show(anchor, content)
    }

    private fun current(): Int = (Color.HSVToColor(hsv) and 0xFFFFFF) or (alpha shl 24)
}
