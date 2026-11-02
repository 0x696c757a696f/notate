package app.notate.editor.popup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import app.notate.core.model.Defaults
import app.notate.editor.R
import app.notate.editor.ui.Ink
import app.notate.editor.ui.dp

/** Round colour swatch; the selected one has a 2 dp ring around it. */
internal class SwatchView(context: Context, val argb: Int, selected: Boolean, private val sizeDp: Int = 44) : View(context) {
    var ring: Boolean = selected
        set(value) { field = value; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb or 0xFF000000.toInt(); style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.BLACK; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(dp(sizeDp), dp(sizeDp))

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        canvas.drawCircle(c, c, dp(14).toFloat(), fill)
        canvas.drawCircle(c, c, dp(14).toFloat(), stroke.apply { strokeWidth = dp(1).toFloat() })
        if (ring) canvas.drawCircle(c, c, dp(19).toFloat(), stroke.apply { strokeWidth = dp(2).toFloat() })
    }
}

/**
 * The 12-colour palette (04 §3.2) plus "Custom…". [alpha] is applied to the picked colour (highlighter slots keep their
 * transparency); [onPick] gets the final ARGB.
 */
class ColorPaletteView(
    context: Context,
    current: Int,
    private val colorAlpha: Int,
    private val onPick: (Int) -> Unit,
    private val onCustom: () -> Unit,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        val grid = GridLayout(context).apply { columnCount = 6 }
        val cur = current and 0xFFFFFF
        for (c in Defaults.PALETTE_12) {
            val v = SwatchView(context, c.argb, (c.argb and 0xFFFFFF) == cur)
            v.contentDescription = c.name
            v.setOnClickListener { onPick((colorAlpha shl 24) or (c.argb and 0xFFFFFF)) }
            grid.addView(v)
        }
        addView(grid)
        addView(PopupUi.button(context, context.getString(R.string.color_custom)) { onCustom() })
    }
}

/** Colour palette in a popup. */
class ColorPalettePopup(
    private val host: PopupHost,
    private val anchor: View?,
    private val current: Int,
    private val alpha: Int = 0xFF,
    private val onPick: (Int) -> Unit,
) {
    fun show() {
        val ctx = anchor?.context ?: return
        val content = PopupUi.column(ctx)
        content.addView(ColorPaletteView(ctx, current, alpha, { c -> host.dismiss(); onPick(c) }, {
            HsvPickerPopup(host, anchor, current, alpha, onPick).show()
        }))
        host.show(anchor, content)
    }
}
