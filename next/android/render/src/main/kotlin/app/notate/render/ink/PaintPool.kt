package app.notate.render.ink

import android.graphics.BlendMode
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build

/**
 * Reusable [Paint]s for one drawing thread. Every accessor resets the paint, so use the result immediately and do not
 * hold it across another call. Not thread-safe: one pool per render thread.
 */
class PaintPool {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun fill(color: Int, multiply: Boolean = false): Paint = fillPaint.apply {
        reset()
        isAntiAlias = true
        style = Paint.Style.FILL
        this.color = color
        applyBlend(this, multiply)
    }

    fun stroke(
        color: Int, width: Float, cap: Paint.Cap = Paint.Cap.ROUND, join: Paint.Join = Paint.Join.ROUND,
        dash: FloatArray? = null, multiply: Boolean = false,
    ): Paint = strokePaint.apply {
        reset()
        isAntiAlias = true
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = width
        strokeCap = cap
        strokeJoin = join
        if (join == Paint.Join.MITER) strokeMiter = 4f
        if (dash != null && dash.size >= 2) pathEffect = DashPathEffect(dash, 0f)
        applyBlend(this, multiply)
    }

    fun text(color: Int, sizePt: Float): Paint = textPaint.apply {
        reset()
        isAntiAlias = true
        style = Paint.Style.FILL
        this.color = color
        textSize = sizePt
    }

    private fun applyBlend(p: Paint, multiply: Boolean) {
        if (!multiply) return
        if (Build.VERSION.SDK_INT >= 29) p.blendMode = BlendMode.MULTIPLY
        else p.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
}
