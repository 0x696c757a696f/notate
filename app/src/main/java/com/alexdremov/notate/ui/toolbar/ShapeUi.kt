package com.alexdremov.notate.ui.toolbar

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.alexdremov.notate.R
import com.alexdremov.notate.model.ShapeType

/** Icon and label for each shape-pen type, shared by the toolbar, its edit panel and the pen settings popup. */
object ShapeUi {
    /** Shape types offered to the user, in display order. */
    val ORDERED: List<ShapeType> =
        listOf(
            ShapeType.AUTO,
            ShapeType.LINE,
            ShapeType.ARROW,
            ShapeType.DOUBLE_ARROW,
            ShapeType.RECTANGLE,
            ShapeType.ELLIPSE,
            ShapeType.TRIANGLE,
            ShapeType.RIGHT_TRIANGLE,
            ShapeType.POLYGON,
            ShapeType.AXES,
            ShapeType.AXES_QUADRANT,
            ShapeType.AXES_3D,
            ShapeType.GRID,
            ShapeType.NUMBER_LINE,
        )

    /** Choices shown in the popup of a button whose current type is [current]: lines or shapes, never both. */
    fun optionsFor(current: ShapeType): List<ShapeType> = ORDERED.filter { it.isLine == current.isLine }

    @DrawableRes
    fun iconRes(type: ShapeType): Int =
        when (type) {
            ShapeType.NONE, ShapeType.AUTO -> R.drawable.ic_tool_shape
            ShapeType.LINE -> R.drawable.ic_shape_line
            ShapeType.ARROW -> R.drawable.ic_shape_arrow
            ShapeType.DOUBLE_ARROW -> R.drawable.ic_shape_double_arrow
            ShapeType.RECTANGLE -> R.drawable.ic_shape_rectangle
            ShapeType.ELLIPSE -> R.drawable.ic_shape_ellipse
            ShapeType.TRIANGLE -> R.drawable.ic_shape_triangle
            ShapeType.RIGHT_TRIANGLE -> R.drawable.ic_shape_right_triangle
            ShapeType.POLYGON -> R.drawable.ic_shape_polygon
            ShapeType.AXES -> R.drawable.ic_shape_axes
            ShapeType.AXES_QUADRANT -> R.drawable.ic_shape_axes_quadrant
            ShapeType.AXES_3D -> R.drawable.ic_shape_axes_3d
            ShapeType.GRID -> R.drawable.ic_shape_grid
            ShapeType.NUMBER_LINE -> R.drawable.ic_shape_number_line
        }

    @StringRes
    fun labelRes(type: ShapeType): Int =
        when (type) {
            ShapeType.NONE, ShapeType.AUTO -> R.string.shape_auto
            ShapeType.LINE -> R.string.shape_line
            ShapeType.ARROW -> R.string.shape_arrow
            ShapeType.DOUBLE_ARROW -> R.string.shape_double_arrow
            ShapeType.RECTANGLE -> R.string.shape_rectangle
            ShapeType.ELLIPSE -> R.string.shape_ellipse
            ShapeType.TRIANGLE -> R.string.shape_triangle
            ShapeType.RIGHT_TRIANGLE -> R.string.shape_right_triangle
            ShapeType.POLYGON -> R.string.shape_polygon
            ShapeType.AXES -> R.string.shape_axes
            ShapeType.AXES_QUADRANT -> R.string.shape_axes_quadrant
            ShapeType.AXES_3D -> R.string.shape_axes_3d
            ShapeType.GRID -> R.string.shape_grid
            ShapeType.NUMBER_LINE -> R.string.shape_number_line
        }
}
