package app.notate.core.ink.outline

import app.notate.core.model.PenType

/** Factory for the [OutlineGenerator] of each pen. */
object Outlines {
    fun forPen(type: PenType): OutlineGenerator = when (type) {
        PenType.BALLPOINT, PenType.FOUNTAIN, PenType.PENCIL -> VariableWidthOutline(type)
        PenType.FINELINER, PenType.HIGHLIGHTER, PenType.DASHED -> PolylineGeometry(type)
        PenType.DOTTED -> DotsGeometry
    }
}
