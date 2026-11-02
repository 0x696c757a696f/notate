package app.notate.render.image

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.notate.core.doc.tools.TextLayoutMetrics
import app.notate.core.model.TextAlign
import kotlin.math.ceil
import kotlin.math.max

/** Builds the `StaticLayout` that both draws a text box and measures it, so editing and display agree. Any thread. */
object TextLayouts {
    fun build(text: String, sizePt: Float, bold: Boolean, widthPt: Float, align: TextAlign, color: Int): StaticLayout {
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePt
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val a = when (align) {
            TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, ceil(widthPt).toInt()))
            .setAlignment(a).setIncludePad(false).setLineSpacing(0f, 1f).build()
    }
}

/** [TextLayoutMetrics] on `StaticLayout`. */
object StaticLayoutMetrics : TextLayoutMetrics {
    override fun measure(text: String, fontSizePt: Float, bold: Boolean, widthPt: Float): Float =
        max(TextLayouts.build(text, fontSizePt, bold, widthPt, TextAlign.LEFT, 0xFF000000.toInt()).height.toFloat(), fontSizePt * 1.2f)
}
