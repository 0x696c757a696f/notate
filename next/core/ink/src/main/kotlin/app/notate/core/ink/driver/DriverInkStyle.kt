package app.notate.core.ink.driver

import app.notate.core.model.InkStyle
import app.notate.core.model.PenType

/** Stroke styles of the e-ink pen driver; the mapping to vendor constants lives in the platform module. */
enum class DriverStroke { PENCIL, FOUNTAIN, MARKER, DASH }

/** What the driver draws while the pen is down: always grayscale, final colours appear after the formal render. */
data class DriverInk(val stroke: DriverStroke, val widthPx: Float, val color: Int)

object DriverInkPolicy {
    private const val BLACK = 0xFF000000.toInt()
    private const val DARK_GRAY = 0xFF555555.toInt()
    private const val LIGHT_GRAY = 0xFFBBBBBB.toInt()

    /** Driver ink for [style] at [pxPerPt] screen pixels per page point. Colours turn gray: dark → black, light → dark gray. */
    fun forPen(style: InkStyle, pxPerPt: Float): DriverInk {
        // BALLPOINT is drawn at an almost constant width (0.85-1.0 w) once committed, so its live ink must be a constant-width
        // line, too: the vendor FOUNTAIN style swells with pressure up to the full width and then looks thick until the
        // formal render replaces it ("thick, then thin").
        val stroke = when (style.pen) {
            PenType.FOUNTAIN -> DriverStroke.FOUNTAIN
            PenType.BALLPOINT, PenType.FINELINER, PenType.PENCIL -> DriverStroke.PENCIL
            PenType.HIGHLIGHTER -> DriverStroke.MARKER
            PenType.DASHED, PenType.DOTTED -> DriverStroke.DASH
        }
        val color = if (style.pen == PenType.HIGHLIGHTER) LIGHT_GRAY else if (luma(style.color) < 0.5f) BLACK else DARK_GRAY
        return DriverInk(stroke, maxOf(style.widthPt * pxPerPt * widthFactor(style.pen), 1f), color)
    }

    /**
     * Ratio of the live line to the pen's width setting: the average of what the committed outline draws (see PenWidths),
     * so the stroke keeps its thickness when the driver hands over to the page renderer. The panel's un-antialiased
     * fast-refresh ink looks bolder than the same width rendered later, hence the small extra reduction.
     */
    internal fun widthFactor(pen: PenType): Float = when (pen) {
        PenType.BALLPOINT -> 0.88f
        PenType.PENCIL -> 0.75f
        PenType.FOUNTAIN -> 0.8f
        else -> 1f
    }

    private fun luma(argb: Int): Float {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
}
