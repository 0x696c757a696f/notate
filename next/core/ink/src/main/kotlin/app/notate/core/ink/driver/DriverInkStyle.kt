package app.notate.core.ink.driver

/** Live-ink look the pen driver can draw itself; the mapping to Onyx constants lives in `:next:platform`. */
enum class DriverStroke { PENCIL, FOUNTAIN, MARKER, DASH }

/** What the driver draws while the pen is down: [widthPx] in view pixels, [color] as ARGB int (grayscale). */
data class DriverInk(val stroke: DriverStroke, val widthPx: Float, val color: Int)
