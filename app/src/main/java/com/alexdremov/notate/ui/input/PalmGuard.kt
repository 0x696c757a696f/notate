package com.alexdremov.notate.ui.input

/**
 * Notability-style palm rejection: fingers scroll and zoom, the pen writes, and the hand that holds
 * the pen never moves the page.
 *
 * A finger touch is treated as the writing hand while the pen hovers over the screen, touches it, or
 * left it less than [graceMs] ago, and whenever its contact is as large as a palm ([palmMinSizePx]).
 *
 * Pen calls may come from the pen service thread; times are [android.os.SystemClock.uptimeMillis].
 */
class PalmGuard(
    private val palmMinSizePx: Float,
    private val graceMs: Long = GRACE_MS,
) {
    companion object {
        /** How long after the pen leaves the screen (or hover range) a touch still counts as the palm. */
        const val GRACE_MS = 500L

        /** A stroke that never reported its end must not disable fingers for good. */
        private const val MAX_STROKE_MS = 15_000L

        /** Same for a hover whose exit was never delivered: a hovering pen keeps sending moves. */
        private const val MAX_HOVER_SILENCE_MS = 3_000L

        /** Contact size above which a touch is a palm or the side of the hand, not a fingertip. */
        const val PALM_MIN_SIZE_MM = 30f
    }

    @Volatile
    private var hovering = false

    @Volatile
    private var lastHoverAt = Long.MIN_VALUE / 2

    @Volatile
    private var penDownAt = -1L

    @Volatile
    private var penLeftAt = Long.MIN_VALUE / 2

    /** Hover enter or move. */
    fun onHover(now: Long) {
        hovering = true
        lastHoverAt = now
    }

    fun onHoverExit(now: Long) {
        hovering = false
        penLeftAt = now
    }

    fun onPenDown(now: Long) {
        penDownAt = now
    }

    fun onPenUp(now: Long) {
        penDownAt = -1L
        penLeftAt = now
    }

    fun isPenNear(now: Long): Boolean {
        if (hovering && now - lastHoverAt < MAX_HOVER_SILENCE_MS) return true
        val down = penDownAt
        if (down >= 0 && now - down < MAX_STROKE_MS) return true
        return now - penLeftAt < graceMs
    }

    /** [touchMajors]: the contact sizes of the touch's pointers, in pixels (0 if the panel doesn't report them). */
    fun isPalmSized(touchMajors: FloatArray): Boolean = touchMajors.any { it >= palmMinSizePx }
}
