package app.notate.core.model

import kotlinx.serialization.Serializable

@Serializable enum class ToolKind { PEN, HIGHLIGHTER, ERASER, LASSO, SHAPE, TEXT, IMAGE, LINK, ZOOM_WINDOW, HAND }
@Serializable enum class EraserMode { PARTIAL, STROKE, LASSO }
@Serializable enum class LassoMode { FREEFORM, RECTANGLE }

/** A saved tool configuration (one toolbar slot). Unused fields stay null. */
@Serializable
data class ToolPreset(
    val kind: ToolKind,
    val pen: PenType? = null,
    val color: Int? = null,
    val widthPt: Float? = null,
    /** Pressure sensitivity 0..1 of FOUNTAIN / PENCIL; 0.7 leaves recorded pressure unchanged. */
    val pressure: Float = 0.7f,
    val eraserMode: EraserMode? = null,
    val eraserRadiusDp: Float? = null,
    val eraseHighlighterOnly: Boolean = false,
    val shapeKind: ShapeKind? = null,
    val snapToGrid: Boolean = false,
    /** Side count of a POLYGON shape, 3..8. */
    val polygonSides: Int = 5,
    val lassoMode: LassoMode = LassoMode.FREEFORM,
    /** TEXT tool: bold and alignment of new text boxes (colour and size are [color] / [widthPt]). */
    val bold: Boolean = false,
    val align: TextAlign = TextAlign.LEFT,
)
