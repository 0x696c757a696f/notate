package app.notate.editor.toolbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import app.notate.editor.ui.Ink
import app.notate.editor.ui.Tooltip
import app.notate.editor.ui.dp

/** A colour slot: 20 dp dot, selected = 2 dp ring around it. Tapping a selected slot edits it ([onEdit]). */
internal class ColorSlot(context: Context, var argb: Int, selected: Boolean, label: String, onSelect: () -> Unit, onEdit: () -> Unit) :
    View(context) {
    private var picked = selected
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ink.BLACK }

    init {
        contentDescription = label
        setOnClickListener { if (picked) onEdit() else onSelect() }
        setOnLongClickListener { Tooltip.show(this, label); true }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(dp(44), dp(48))

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // Translucent colours (highlighter) are shown on white so that the dot is visible.
        fill.color = (argb and 0xFFFFFF) or (0xFF shl 24)
        canvas.drawCircle(cx, cy, dp(10).toFloat(), fill)
        edge.strokeWidth = dp(1).toFloat()
        canvas.drawCircle(cx, cy, dp(10).toFloat(), edge)
        if (picked) {
            edge.strokeWidth = dp(2).toFloat()
            canvas.drawCircle(cx, cy, dp(15).toFloat(), edge)
        }
    }
}

/** A width / size slot: a black dot of [diameterDp]; selected = ring. Tapping a selected slot edits it ([onEdit], may be null). */
internal class WidthSlot(
    context: Context,
    private val diameterDp: Float,
    selected: Boolean,
    label: String,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)?,
) : View(context) {
    private val picked = selected
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Ink.BLACK }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ink.BLACK }

    init {
        contentDescription = label
        setOnClickListener { if (picked && onEdit != null) onEdit() else if (!picked) onSelect() }
        setOnLongClickListener { Tooltip.show(this, label); true }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(dp(44), dp(48))

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, dp(1) * diameterDp / 2f, dot)
        if (picked) {
            ring.strokeWidth = dp(2).toFloat()
            canvas.drawCircle(cx, cy, dp(15).toFloat(), ring)
        }
    }
}
