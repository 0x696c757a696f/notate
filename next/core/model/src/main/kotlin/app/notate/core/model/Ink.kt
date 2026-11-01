package app.notate.core.model

import kotlinx.serialization.Serializable

/** Drawing layers, bottom to top. */
@Serializable
enum class Layer(val code: Int) {
    IMAGE(0), HIGHLIGHT(1), INK(2), OVERLAY(3);

    companion object {
        fun fromCode(code: Int): Layer =
            entries.firstOrNull { it.code == code } ?: throw CorruptDataException("Unknown Layer code $code")
    }
}

@Serializable
enum class PenType(val code: Int) {
    BALLPOINT(0), FOUNTAIN(1), FINELINER(2), PENCIL(3), HIGHLIGHTER(4), DASHED(5), DOTTED(6);

    companion object {
        fun fromCode(code: Int): PenType =
            entries.firstOrNull { it.code == code } ?: throw CorruptDataException("Unknown PenType code $code")
    }
}

@Serializable
enum class ShapeKind(val code: Int) {
    LINE(0), ARROW(1), DOUBLE_ARROW(2), RECT(3), TRIANGLE(4), POLYGON(5), POLYLINE(6), ELLIPSE(7), AXES_2D(8), AXES_Q1(9);

    companion object {
        fun fromCode(code: Int): ShapeKind =
            entries.firstOrNull { it.code == code } ?: throw CorruptDataException("Unknown ShapeKind code $code")
    }
}

@Serializable
enum class TextAlign { LEFT, CENTER, RIGHT }

/** Pen appearance; [widthPt] is the nominal width in pt. */
@Serializable
data class InkStyle(val pen: PenType, val color: Int, val widthPt: Float)

/**
 * Interleaved stroke samples: xy = [x0,y0,x1,y1,…]; pressure 0..1 (null = no pressure); tMs relative to the first point (null = none).
 * Treated as immutable: never mutate the arrays after construction.
 */
class StrokePoints(val xy: FloatArray, val pressure: FloatArray?, val tMs: IntArray?) {
    val size: Int get() = xy.size / 2

    override fun equals(other: Any?): Boolean =
        other is StrokePoints && xy.contentEquals(other.xy) && pressure.contentEquals(other.pressure) && tMs.contentEquals(other.tMs)

    override fun hashCode(): Int {
        var h = xy.contentHashCode()
        h = 31 * h + pressure.contentHashCode()
        h = 31 * h + tMs.contentHashCode()
        return h
    }

    override fun toString(): String = "StrokePoints(size=$size)"
}

/** Raw input sample in page coordinates, used inside tool sessions before it becomes a [StrokePoints]. */
data class InkPoint(val x: Float, val y: Float, val pressure: Float, val tiltX: Float, val tiltY: Float, val tMs: Long)
