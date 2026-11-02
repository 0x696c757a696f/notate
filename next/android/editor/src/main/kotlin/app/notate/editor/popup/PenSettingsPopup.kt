package app.notate.editor.popup

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.notate.core.doc.tools.ToolController
import app.notate.core.model.PenType
import app.notate.core.model.ToolKind
import app.notate.editor.R
import app.notate.editor.toolbar.ToolEdits
import app.notate.editor.ui.Ink
import app.notate.editor.ui.dp
import app.notate.editor.ui.popupBackground
import java.util.Locale

/** "Pen settings" (04 §7): pen types with a live sample, thickness, pressure sensitivity (Fountain / Pencil only), colours. */
class PenSettingsPopup(
    private val host: PopupHost,
    private val anchor: View?,
    private val controller: ToolController,
    private val xdpi: Float,
) {
    fun show() {
        val ctx = anchor?.context ?: return
        val state = controller.state.value
        val slots = state.pen
        val width = slots.widthsPt[slots.width.coerceIn(0, slots.widthsPt.size - 1)]
        val color = slots.colors[slots.color.coerceIn(0, slots.colors.size - 1)]
        var pen = slots.pen
        val content = PopupUi.column(ctx)

        val pressureBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun pressureVisible() { pressureBox.visibility = if (pen == PenType.FOUNTAIN || pen == PenType.PENCIL) View.VISIBLE else View.GONE }

        val rows = ArrayList<Pair<PenType, LinearLayout>>()
        val samples = ArrayList<Pair<PenType, SampleStrokeView>>()
        fun markRows() {
            for ((t, row) in rows) {
                row.background = if (t == pen) popupBackground(ctx) else null
            }
        }
        for (type in TYPES) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(6), 0, ctx.dp(6), 0)
            }
            val sample = SampleStrokeView(ctx, type, color, width, xdpi)
            row.addView(sample)
            row.addView(PopupUi.text(ctx, typeName(ctx, type)).apply { setPadding(ctx.dp(12), 0, 0, 0) })
            row.setOnClickListener {
                pen = type
                ToolEdits.setPenType(controller, type)
                pressureVisible()
                markRows()
                host.refresh()
            }
            rows.add(type to row)
            samples.add(type to sample)
            content.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(52)))
        }
        markRows()

        val widthLabel = PopupUi.text(ctx, mmText(ToolEdits.mm(width)), 14f, Ink.SECONDARY)
        content.addView(labelRow(ctx, ctx.getString(R.string.pen_thickness), widthLabel))
        content.addView(
            SliderView(ctx, MIN_MM, MAX_MM, ToolEdits.mm(width).coerceIn(MIN_MM, MAX_MM), onLive = { widthLabel.text = mmText(it) }, onCommit = {
                ToolEdits.setWidthMm(controller, ToolKind.PEN, it)
                val pt = controller.state.value.pen.widthsPt[controller.state.value.pen.width]
                samples.forEach { (t, v) -> v.setStyle(t, color, pt) }
                host.refresh()
            }),
        )

        val pLabel = PopupUi.text(ctx, pressureText(slots.pressure), 14f, Ink.SECONDARY)
        pressureBox.addView(labelRow(ctx, ctx.getString(R.string.pen_pressure), pLabel))
        pressureBox.addView(SliderView(ctx, 0f, 1f, slots.pressure, onLive = { pLabel.text = pressureText(it) }, onCommit = {
            ToolEdits.setPressure(controller, it)
        }))
        content.addView(pressureBox)
        pressureVisible()

        content.addView(PopupUi.text(ctx, ctx.getString(R.string.pen_color), 14f, Ink.SECONDARY).apply { setPadding(0, ctx.dp(8), 0, ctx.dp(2)) })
        content.addView(ColorPaletteView(ctx, color, 0xFF, { c ->
            ToolEdits.setColor(controller, ToolKind.PEN, c)
            host.dismiss()
        }, {
            HsvPickerPopup(host, anchor, color, 0xFF) { c -> ToolEdits.setColor(controller, ToolKind.PEN, c) }.show()
        }))
        host.show(anchor, content)
    }

    private fun labelRow(ctx: android.content.Context, title: String, value: TextView): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, ctx.dp(8), 0, 0)
        addView(PopupUi.text(ctx, title, 14f, Ink.SECONDARY), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(value)
    }

    private fun typeName(ctx: android.content.Context, t: PenType): String = ctx.getString(
        when (t) {
            PenType.BALLPOINT -> R.string.pen_ballpoint
            PenType.FOUNTAIN -> R.string.pen_fountain
            PenType.FINELINER -> R.string.pen_fineliner
            PenType.PENCIL -> R.string.pen_pencil
            PenType.DASHED -> R.string.pen_dashed
            PenType.DOTTED -> R.string.pen_dotted
            PenType.HIGHLIGHTER -> R.string.tool_highlighter
        },
    )

    companion object {
        val TYPES = listOf(PenType.BALLPOINT, PenType.FOUNTAIN, PenType.FINELINER, PenType.PENCIL, PenType.DASHED, PenType.DOTTED)
        const val MIN_MM = 0.1f
        const val MAX_MM = 3.0f
        fun mmText(mm: Float): String = String.format(Locale.US, "%.2f mm", mm)
        fun pressureText(v: Float): String = String.format(Locale.US, "%.2f", v)
    }
}
