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
    val eraserMode: EraserMode? = null,
    val eraserRadiusDp: Float? = null,
    val eraseHighlighterOnly: Boolean = false,
    val shapeKind: ShapeKind? = null,
    val snapToGrid: Boolean = false,
    val lassoMode: LassoMode = LassoMode.FREEFORM,
)
