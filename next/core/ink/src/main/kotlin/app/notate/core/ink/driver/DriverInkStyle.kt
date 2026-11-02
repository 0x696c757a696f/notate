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
        val stroke = when (style.pen) {
            PenType.BALLPOINT, PenType.FOUNTAIN -> DriverStroke.FOUNTAIN
            PenType.FINELINER, PenType.PENCIL -> DriverStroke.PENCIL
            PenType.HIGHLIGHTER -> DriverStroke.MARKER
            PenType.DASHED, PenType.DOTTED -> DriverStroke.DASH
        }
        val color = if (style.pen == PenType.HIGHLIGHTER) LIGHT_GRAY else if (luma(style.color) < 0.5f) BLACK else DARK_GRAY
        return DriverInk(stroke, maxOf(style.widthPt * pxPerPt, 1f), color)
    }

    private fun luma(argb: Int): Float {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
}
