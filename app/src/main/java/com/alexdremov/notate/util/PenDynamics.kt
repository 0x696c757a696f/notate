package com.alexdremov.notate.util

import kotlin.math.pow

/**
 * Pressure response curves that give each pen its character, modeled on the pens of
 * GoodNotes, Notability and Boox Notes:
 * - Fineliner: no pressure response at all (constant width).
 * - Ballpoint: mild response; light touches are a bit thinner and lighter.
 * - Fountain: strong response with ink tapering at the start and end of a stroke.
 *
 * Pressures are normalized to 0..1. Kept free of Android types so the curves are unit-testable.
 */
object PenDynamics {
    // Fountain: width = base * (FLOOR + (1 - FLOOR) * p^EXP). A hairline when barely touching,
    // MAX_SCALE x the set width when pressing hard.
    const val FOUNTAIN_FLOOR = 0.2f
    const val FOUNTAIN_EXPONENT = 1.4f
    const val FOUNTAIN_MAX_SCALE = 1.5f

    // Number of input points over which a fountain stroke swells in and thins out.
    const val FOUNTAIN_TAPER_POINTS = 6
    const val FOUNTAIN_TAPER_MIN = 0.35f

    // Ballpoint: narrower width range than the fountain pen, plus slight ink lightening.
    const val BALLPOINT_MIN_WIDTH_SCALE = 0.55f
    const val BALLPOINT_EXPONENT = 0.7f
    const val BALLPOINT_MIN_ALPHA = 165

    fun fountainWidthScale(pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        return FOUNTAIN_MAX_SCALE * (FOUNTAIN_FLOOR + (1f - FOUNTAIN_FLOOR) * p.pow(FOUNTAIN_EXPONENT))
    }

    /** Ink entry/exit taper for point [index] of [count]: ramps from [FOUNTAIN_TAPER_MIN] to 1. */
    fun fountainTaper(
        index: Int,
        count: Int,
    ): Float {
        if (count <= 2) return 1f
        val fromEdge = minOf(index, count - 1 - index)
        // Short strokes (dots, commas) taper less so they don't vanish.
        val span = minOf(FOUNTAIN_TAPER_POINTS, count / 3).coerceAtLeast(1)
        if (fromEdge >= span) return 1f
        val t = fromEdge.toFloat() / span
        return FOUNTAIN_TAPER_MIN + (1f - FOUNTAIN_TAPER_MIN) * t
    }

    fun ballpointWidthScale(pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f).pow(BALLPOINT_EXPONENT)
        return BALLPOINT_MIN_WIDTH_SCALE + (1f - BALLPOINT_MIN_WIDTH_SCALE) * p
    }

    fun ballpointAlpha(pressure: Float): Int {
        val p = pressure.coerceIn(0f, 1f).pow(BALLPOINT_EXPONENT)
        return (BALLPOINT_MIN_ALPHA + (255 - BALLPOINT_MIN_ALPHA) * p).toInt().coerceIn(0, 255)
    }
}
