package com.alexdremov.notate.webdoc

import kotlin.math.abs

/** One element that might be the page's main scroller; mirrors what `notate-bridge.js` collects. */
data class ScrollCandidate(
    val id: Int,
    val scrollHeight: Float,
    val clientHeight: Float,
    val overflowY: String,
    val area: Float,
    val isRoot: Boolean,
)

/**
 * Which element scrolls the page. Documents like `body{overflow:auto;height:100vh}` scroll an inner
 * element while the WebView itself never moves, so ink has to follow that element's scroll.
 *
 * Rule (the JavaScript in `assets/webdoc/notate-bridge.js` implements the same one):
 *  1. If the root scrolls, it is the main scroller.
 *  2. Otherwise the largest element with `overflow-y: auto|scroll|overlay` that really overflows and
 *     covers at least a quarter of the viewport.
 *  3. Otherwise the root.
 */
object ScrollElementPicker {
    const val MIN_VIEWPORT_FRACTION = 0.25f

    private val SCROLLABLE = setOf("auto", "scroll", "overlay")

    private fun overflows(c: ScrollCandidate) = c.scrollHeight - c.clientHeight > 1f

    fun pick(
        candidates: List<ScrollCandidate>,
        viewportArea: Float,
    ): Int? {
        val root = candidates.firstOrNull { it.isRoot }
        if (root != null && overflows(root)) return root.id
        val inner =
            candidates
                .filter { !it.isRoot && it.overflowY in SCROLLABLE && overflows(it) && it.area >= viewportArea * MIN_VIEWPORT_FRACTION }
                .maxWithOrNull(compareBy<ScrollCandidate>({ it.area }, { it.scrollHeight - it.clientHeight }))
        return inner?.id ?: root?.id
    }
}

/** Choosing and applying the fixed layout width of a web document. */
object WebLayoutLock {
    /** Wide panes still lay out at this width: long lines are unreadable and e-ink panes are small anyway. */
    const val MAX_WIDTH_CSS = 900f

    /** Below this a width is almost certainly a not-yet-laid-out view, not a real pane. */
    const val MIN_WIDTH_CSS = 200f

    /**
     * Width for a document with no stored layout: the one its existing ink was written at (the most
     * common recorded page width), else the current pane width capped at [MAX_WIDTH_CSS].
     */
    fun initialWidth(
        recordedPageWidths: Collection<Float>,
        paneWidthCss: Float,
    ): Float {
        val recorded = recordedPageWidths.filter { it >= MIN_WIDTH_CSS }
        if (recorded.isNotEmpty()) {
            return recorded
                .groupingBy { Math.round(it) }
                .eachCount()
                .maxByOrNull { it.value }!!
                .key
                .toFloat()
        }
        if (paneWidthCss < MIN_WIDTH_CSS) return 0f
        return minOf(paneWidthCss, MAX_WIDTH_CSS)
    }

    fun reflowWidth(paneWidthCss: Float): Float = paneWidthCss.coerceIn(MIN_WIDTH_CSS, MAX_WIDTH_CSS)

    /** True if the layouts differ enough that text moved relative to ink. */
    fun differs(
        a: Float,
        b: Float,
    ): Boolean = a > 0f && b > 0f && abs(a - b) / a > WebAnnotationStore.LAYOUT_DRIFT_FRACTION
}
