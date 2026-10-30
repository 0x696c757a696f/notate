package com.alexdremov.notate.model

import kotlinx.serialization.Serializable

/**
 * Geometry produced by a shape pen (a [PenTool] of type PEN with a non-NONE [PenTool.shapeType]).
 *
 * Every value except [NONE] and [AUTO] is a "drag" shape: the user drags from a start point to an end
 * point and the shape is built from those two points (see [com.alexdremov.notate.util.ShapeGeometry]).
 * [AUTO] lets the user draw freely and snaps the stroke to the recognized shape on pen-up.
 *
 * Persisted by ordinal inside [PenTool]: append new values at the end only.
 */
@Serializable
enum class ShapeType {
    NONE,
    AUTO,
    LINE,
    ARROW,
    DOUBLE_ARROW,
    RECTANGLE,
    ELLIPSE,
    TRIANGLE,
    RIGHT_TRIANGLE,
    POLYGON,
    AXES,
    AXES_QUADRANT,
    AXES_3D,
    GRID,
    NUMBER_LINE,
    ;

    val isDragShape: Boolean get() = this != NONE && this != AUTO

    /** Line-family types live on the separate "Lines" toolbar button; everything else on "Shapes". */
    val isLine: Boolean get() = this == LINE || this == ARROW || this == DOUBLE_ARROW
}
