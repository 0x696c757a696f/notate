package com.alexdremov.notate.ui.split

/**
 * Decides when a hovering pen has really moved to the other pane of a split view, so focus follows the
 * pen without flickering while it passes over the divider or wobbles near the edge.
 *
 * Feed it every hover event with the pane under the pen; it answers with the pane to focus once the pen
 * has stayed over a non-focused pane for [delayMs]. Pure; times are any monotonic clock in ms.
 */
class HoverFocusDebouncer(
    private val delayMs: Long = DEFAULT_DELAY_MS,
) {
    companion object {
        const val DEFAULT_DELAY_MS = 80L
        private const val NONE = -1
    }

    private var candidate = NONE
    private var since = 0L

    /** [pane]: the pane under the pen (-1 over the divider or outside), [active]: the focused pane. */
    fun onHover(
        pane: Int,
        active: Int,
        now: Long,
    ): Int? {
        if (pane < 0 || pane == active) {
            candidate = NONE
            return null
        }
        if (pane != candidate) {
            candidate = pane
            since = now
            return null
        }
        if (now - since < delayMs) return null
        candidate = NONE
        return pane
    }

    /** Milliseconds until the current candidate may take focus, or -1 if there is none. */
    fun remaining(now: Long): Long = if (candidate == NONE) -1L else (delayMs - (now - since)).coerceAtLeast(0L)

    fun reset() {
        candidate = NONE
    }
}
