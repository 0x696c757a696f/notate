package app.notate.core.doc.tools

import app.notate.core.model.Defaults
import app.notate.core.model.EraserMode
import app.notate.core.model.LassoMode
import app.notate.core.model.PenType
import app.notate.core.model.ShapeKind
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import app.notate.core.model.Units
import kotlinx.serialization.Serializable

/** Pen or highlighter slots: three colours and widths; [color] / [width] index the selected ones. */
@Serializable
data class PenSlots(val pen: PenType, val colors: List<Int>, val widthsPt: List<Float>, val color: Int = 0, val width: Int = 1)

/** Toolbar data model, persisted as JSON under the `tool_state` setting. */
@Serializable
data class ToolState(
    val active: ToolKind = ToolKind.PEN,
    val pen: PenSlots, val highlighter: PenSlots,
    val eraserMode: EraserMode = EraserMode.PARTIAL, val eraserSizesDp: List<Float>, val eraserSize: Int = 1,
    val eraseHighlighterOnly: Boolean = false,
    val shapeKind: ShapeKind = ShapeKind.LINE, val snapToGrid: Boolean = false,
    val lassoMode: LassoMode = LassoMode.FREEFORM,
    val textSizePt: Float = 14f, val textBold: Boolean = false, val textColor: Int = 0xFF000000.toInt(),
) {
    /** The tool currently in effect, with colour, width and eraser size resolved from the selected slots. */
    fun preset(): ToolPreset = when (active) {
        ToolKind.PEN -> penPreset(ToolKind.PEN, pen)
        ToolKind.HIGHLIGHTER -> penPreset(ToolKind.HIGHLIGHTER, highlighter)
        ToolKind.ERASER -> ToolPreset(
            ToolKind.ERASER, eraserMode = eraserMode, eraserRadiusDp = pick(eraserSizesDp, eraserSize) / 2f,
            eraseHighlighterOnly = eraseHighlighterOnly,
        )
        ToolKind.SHAPE -> penPreset(ToolKind.SHAPE, pen).copy(shapeKind = shapeKind, snapToGrid = snapToGrid)
        ToolKind.LASSO -> ToolPreset(ToolKind.LASSO, lassoMode = lassoMode)
        ToolKind.TEXT -> ToolPreset(ToolKind.TEXT, color = textColor, widthPt = textSizePt)
        else -> ToolPreset(active)
    }

    private fun penPreset(kind: ToolKind, s: PenSlots) =
        ToolPreset(kind, pen = s.pen, color = pick(s.colors, s.color), widthPt = pick(s.widthsPt, s.width))

    private fun <T> pick(list: List<T>, i: Int): T = list[i.coerceIn(0, list.size - 1)]

    companion object {
        /** Defaults from 04-ux-spec §3.2 (selected pen width 0.4 mm; highlighter 5 mm; eraser 5 mm). */
        fun defaults(): ToolState = ToolState(
            pen = PenSlots(PenType.BALLPOINT, Defaults.PEN_COLORS, Defaults.PEN_WIDTHS_MM.map(Units::mm), 0, 1),
            highlighter = PenSlots(PenType.HIGHLIGHTER, Defaults.HIGHLIGHTER_COLORS, Defaults.HIGHLIGHTER_WIDTHS_MM.map(Units::mm), 0, 1),
            eraserSizesDp = Defaults.ERASER_SIZES_DP,
        )
    }
}
