@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate.model

import android.graphics.Color
import com.onyx.android.sdk.pen.TouchHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
enum class ToolType {
    PEN,
    ERASER,
    SELECT,
    TEXT,
}

@Serializable
enum class EraserType {
    STROKE, // Erases entire stroke
    LASSO, // Erases strokes inside selection
    STANDARD, // Erases parts of strokes
}

@Serializable
enum class SelectionType {
    RECTANGLE,
    LASSO,
}

@Serializable
data class PenTool(
    @ProtoNumber(1)
    val id: String,
    @ProtoNumber(2)
    val name: String,
    @ProtoNumber(3)
    val type: ToolType,
    @ProtoNumber(4)
    var color: Int = Color.BLACK,
    @ProtoNumber(5)
    var width: Float = 3f, // Acts as FontSize for TEXT tool
    @ProtoNumber(6)
    var strokeType: StrokeType = StrokeType.FOUNTAIN,
    @ProtoNumber(7)
    var eraserType: EraserType = EraserType.STANDARD,
    @ProtoNumber(8)
    var selectionType: SelectionType = SelectionType.RECTANGLE,
    @ProtoNumber(9)
    val shapeType: ShapeType = ShapeType.NONE,
    @ProtoNumber(10)
    val polygonSides: Int = 5,
) {
    /** True for pens that draw geometric shapes instead of freehand ink. */
    val isShapeTool: Boolean
        get() = type == ToolType.PEN && shapeType != ShapeType.NONE

    /** True for shape pens whose shape is defined by dragging from a start to an end point. */
    val isDragShapeTool: Boolean
        get() = type == ToolType.PEN && shapeType.isDragShape

    val displayColor: Int
        get() {
            val alpha = (Color.alpha(color) * strokeType.alphaMultiplier).toInt()
            return (color and 0x00FFFFFF) or (alpha shl 24)
        }

    companion object {
        /** Default writing width: a fine 0.4 mm line, like GoodNotes' and Boox Notes' default pens. */
        const val DEFAULT_PEN_WIDTH_MM = 0.4f

        /** Screen px for [mm] on this device (canvas units are screen px at 100% zoom). */
        fun mmToDefaultPx(mm: Float): Float {
            val xdpi = runCatching { android.content.res.Resources.getSystem().displayMetrics.xdpi }.getOrNull() ?: 0f
            // Fall back to the Boox Note X panel (227 dpi) if the metrics are unavailable.
            return mm * (if (xdpi > 0f) xdpi else 227f) / 25.4f
        }

        fun defaultPens(): List<PenTool> =
            listOf(
                PenTool("pen_0", "Ballpoint", ToolType.PEN, Color.BLACK, mmToDefaultPx(DEFAULT_PEN_WIDTH_MM), StrokeType.BALLPOINT),
                PenTool("pen_1", "Pen 1", ToolType.PEN, Color.BLACK, mmToDefaultPx(DEFAULT_PEN_WIDTH_MM), StrokeType.FINELINER),
                PenTool(
                    "pen_2",
                    "Pen 2",
                    ToolType.PEN,
                    Color.parseColor("#1A237E"),
                    mmToDefaultPx(DEFAULT_PEN_WIDTH_MM),
                    StrokeType.FOUNTAIN,
                ),
                PenTool("pen_3", "Pen 3", ToolType.PEN, Color.parseColor("#fff9c47c"), 60f, StrokeType.HIGHLIGHTER),
                PenTool("eraser_std", "Standard Eraser", ToolType.ERASER, Color.WHITE, 30f, StrokeType.FINELINER, EraserType.STANDARD),
                PenTool(
                    "select_tool",
                    "Select",
                    ToolType.SELECT,
                    Color.BLACK,
                    2f,
                    StrokeType.DASH,
                    EraserType.STANDARD,
                    SelectionType.RECTANGLE,
                ),
                PenTool("text_tool", "Text", ToolType.TEXT, Color.BLACK, 40f, StrokeType.FINELINER),
                defaultShapeTool("shape_tool"),
                defaultShapeTool("line_tool", ShapeType.LINE),
            )

        fun defaultShapeTool(
            id: String,
            shapeType: ShapeType = ShapeType.AUTO,
        ): PenTool =
            PenTool(
                id = id,
                name = if (shapeType.isLine) "Lines" else "Shapes",
                type = ToolType.PEN,
                color = Color.BLACK,
                width = mmToDefaultPx(DEFAULT_PEN_WIDTH_MM),
                strokeType = StrokeType.FINELINER,
                shapeType = shapeType,
            )
    }
}
