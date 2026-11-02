package app.notate.core.web

import kotlin.math.abs

/** One element that might be the page's main scroller; mirrors what the bridge script collects. */
data class ScrollCandidate(
    val id: Int,
    val scrollHeight: Float,
    val clientHeight: Float,
    val overflowY: String,
    val area: Float,
    val isRoot: Boolean,
) {
    companion object {
        const val MIN_VIEWPORT_FRACTION = 0.25f
        private val SCROLLABLE = setOf("auto", "scroll", "overlay")

        private fun overflows(c: ScrollCandidate) = c.scrollHeight - c.clientHeight > 1f

        /**
         * Which element scrolls the page: the root if it overflows; else the largest `overflow-y: auto|scroll|overlay`
         * element that overflows and covers at least a quarter of the viewport; else the root. Null if no candidates.
         */
        fun pick(candidates: List<ScrollCandidate>, viewportArea: Float): Int? {
            val root = candidates.firstOrNull { it.isRoot }
            if (root != null && overflows(root)) return root.id
            val inner = candidates
                .filter { !it.isRoot && it.overflowY in SCROLLABLE && overflows(it) && it.area >= viewportArea * MIN_VIEWPORT_FRACTION }
                .maxWithOrNull(compareBy<ScrollCandidate>({ it.area }, { it.scrollHeight - it.clientHeight }))
            return inner?.id ?: root?.id
        }
    }
}

/** Pure arithmetic of the fixed layout width of a web document (CSS px). */
object WebLayoutMath {
    /** Wide panes still lay out at this width: long lines are unreadable and e-ink panes are small anyway. */
    const val MAX_WIDTH_CSS = 900f
    const val MIN_WIDTH_CSS = 320f

    /** Relative change above which text has moved noticeably against its ink. */
    const val DRIFT_FRACTION = 0.05f

    /** Layout width of a freshly imported document in a pane [paneWidthDp] wide. */
    fun initialWidth(paneWidthDp: Float): Float = minOf(paneWidthDp, MAX_WIDTH_CSS).coerceAtLeast(MIN_WIDTH_CSS)

    /** Width to reflow to when the user chooses "Reflow to fit". */
    fun reflowWidth(paneWidthCss: Float): Float = paneWidthCss.coerceIn(MIN_WIDTH_CSS, MAX_WIDTH_CSS)

    /** True if the widths differ by more than [DRIFT_FRACTION] relative to [a]. */
    fun differs(a: Float, b: Float): Boolean = a > 0f && b > 0f && abs(a - b) / a > DRIFT_FRACTION
}
