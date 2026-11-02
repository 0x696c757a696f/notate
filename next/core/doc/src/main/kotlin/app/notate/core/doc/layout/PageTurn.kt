package app.notate.core.doc.layout

import kotlin.math.abs

/** Single-page mode rules (04 §3.4): when a finger swipe turns the page. Pure; any thread. */
object PageTurn {
    /** Fraction of the view width a swipe must cover to turn. */
    const val DISTANCE_FRACTION = 0.3f

    /** Release speed (dp/s) above which a swipe turns. */
    const val VELOCITY_DP_PER_S = 1000f

    /**
     * +1 = next page, −1 = previous, 0 = stay. [totalDxPx] is the finger's horizontal travel since it went down (a swipe to
     * the left is negative and means "next"), [vxPxPerS] its release velocity, [widthPx] the view width and [density] px per dp.
     * Mostly vertical motion never turns.
     */
    fun direction(totalDxPx: Float, totalDyPx: Float, vxPxPerS: Float, widthPx: Int, density: Float): Int {
        if (abs(totalDyPx) > abs(totalDxPx)) return 0
        val fast = abs(vxPxPerS) > VELOCITY_DP_PER_S * density && vxPxPerS * totalDxPx >= 0f
        val far = abs(totalDxPx) > DISTANCE_FRACTION * widthPx
        if (!fast && !far) return 0
        val dir = if (fast) vxPxPerS else totalDxPx
        return if (dir < 0f) 1 else -1
    }

    /**
     * True when the viewport already shows the page's left edge (dir −1) or right edge (dir +1), so a swipe may turn instead of
     * panning inside a zoomed page. [slackPt] tolerates rounding.
     */
    fun atEdge(vp: Viewport, layout: DocumentLayout, dir: Int, slackPt: Float = 2f): Boolean {
        if (layout.pageCount == 0) return true
        val visW = vp.widthPx / vp.scale
        val f = layout.frameAt(layout.nearestIndex(vp.scrollX + visW / 2f, vp.scrollY + vp.heightPx / vp.scale / 2f))
        return if (dir > 0) vp.scrollX + visW >= f.r - slackPt else vp.scrollX <= f.l + slackPt
    }
}
