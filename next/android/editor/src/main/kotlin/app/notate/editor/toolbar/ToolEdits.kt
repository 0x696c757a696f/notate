package app.notate.editor.toolbar

import app.notate.core.doc.tools.PenSlots
import app.notate.core.doc.tools.ToolController
import app.notate.core.doc.tools.ToolState
import app.notate.core.model.PenType
import app.notate.core.model.ToolKind
import app.notate.core.model.Units

/** Toolbar edits of the shared [ToolState]; every function is one persisted update. Main thread only. */
internal object ToolEdits {
    fun slots(s: ToolState, kind: ToolKind): PenSlots = if (kind == ToolKind.HIGHLIGHTER) s.highlighter else s.pen

    private fun withSlots(s: ToolState, kind: ToolKind, f: (PenSlots) -> PenSlots): ToolState =
        if (kind == ToolKind.HIGHLIGHTER) s.copy(highlighter = f(s.highlighter)) else s.copy(pen = f(s.pen))

    fun selectColor(c: ToolController, kind: ToolKind, i: Int) = c.update { withSlots(it, kind) { p -> p.copy(color = i) } }

    fun selectWidth(c: ToolController, kind: ToolKind, i: Int) = c.update { withSlots(it, kind) { p -> p.copy(width = i) } }

    /** Sets the colour of the selected colour slot. */
    fun setColor(c: ToolController, kind: ToolKind, argb: Int) = c.update {
        withSlots(it, kind) { p -> p.copy(colors = p.colors.toMutableList().also { l -> l[p.color.coerceIn(0, l.size - 1)] = argb }) }
    }

    /** Sets the width of the selected width slot, in mm. */
    fun setWidthMm(c: ToolController, kind: ToolKind, mm: Float) = c.update {
        withSlots(it, kind) { p ->
            p.copy(widthsPt = p.widthsPt.toMutableList().also { l -> l[p.width.coerceIn(0, l.size - 1)] = Units.mm(mm) })
        }
    }

    fun setPenType(c: ToolController, type: PenType) = c.update { it.copy(pen = it.pen.copy(pen = type)) }

    fun setPressure(c: ToolController, v: Float) = c.update { it.copy(pen = it.pen.copy(pressure = v.coerceIn(0f, 1f))) }

    fun mm(pt: Float): Float = pt / Units.PT_PER_MM
}
