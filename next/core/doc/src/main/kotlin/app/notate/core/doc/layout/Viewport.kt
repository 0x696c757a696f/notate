package app.notate.core.doc.layout

import app.notate.core.geometry.Affine
import app.notate.core.geometry.Rect
import app.notate.core.model.PageId
import app.notate.core.model.PageLayoutMode
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Window onto the document plane. [scrollX]/[scrollY] are the document-plane coordinates (pt) of the top-left pixel;
 * one pt covers [scale] = `zoom · pxPerPt` screen pixels. Immutable; any thread.
 */
data class Viewport(
    val scrollX: Float,
    val scrollY: Float,
    val zoom: Float,
    val pxPerPt: Float,
    val widthPx: Int,
    val heightPx: Int,
) {
    val scale: Float get() = zoom * pxPerPt

    /** Document plane (pt) → screen px. */
    fun docToScreen(): Affine = Affine.scale(scale, scale) * Affine.translate(-scrollX, -scrollY)

    /** Screen px → document plane (pt). */
    fun screenToDoc(): Affine = Affine.translate(scrollX, scrollY) * Affine.scale(1f / scale, 1f / scale)

    /** Part of the document plane currently on screen. */
    fun visibleDocRect(): Rect = Rect(scrollX, scrollY, scrollX + widthPx / scale, scrollY + heightPx / scale)

    /** Zoom by [factor] keeping the document point under screen pixel ([fx], [fy]) fixed; zoom is clamped to [min]..[max]. */
    fun zoomAround(factor: Float, fx: Float, fy: Float, min: Float = MIN_ZOOM, max: Float = MAX_ZOOM): Viewport {
        val newZoom = (zoom * factor).coerceIn(min, max)
        val newScale = newZoom * pxPerPt
        val docX = scrollX + fx / scale
        val docY = scrollY + fy / scale
        return copy(scrollX = docX - fx / newScale, scrollY = docY - fy / newScale, zoom = newZoom)
    }

    /**
     * Moves the content by ([dxPx], [dyPx]) screen pixels (finger drag: content follows the finger, so the scroll
     * position decreases) and clamps to [layout]. Pages narrower than the viewport are centered; the vertical range is
     * `[−margin, total + [extraBottomPt] − visibleHeight + margin]` with margin = [DocumentLayout.gapPt]; [extraBottomPt] makes room for
     * the "Add page" button below the last page or for the zoom window that covers the bottom of the pane. Documents with a whiteboard page
     * are not clamped. In SINGLE mode the range is that of the page nearest to the viewport center.
     */
    fun panBy(dxPx: Float, dyPx: Float, layout: DocumentLayout, extraBottomPt: Float = 0f): Viewport {
        val nx = scrollX - dxPx / scale
        val ny = scrollY - dyPx / scale
        if (layout.hasUnboundedPage || layout.pageCount == 0) return copy(scrollX = nx, scrollY = ny)
        val visW = widthPx / scale
        val visH = heightPx / scale
        val m = layout.gapPt
        val loX: Float; val hiX: Float; val loY: Float; val hiY: Float
        if (layout.mode == PageLayoutMode.CONTINUOUS) {
            loX = 0f; hiX = layout.maxWidth; loY = 0f; hiY = layout.totalHeight + extraBottomPt
        } else {
            val f = layout.frameAt(layout.nearestIndex(scrollX + visW / 2f, scrollY + visH / 2f))
            loX = f.l; hiX = f.r; loY = f.t; hiY = f.b + extraBottomPt
        }
        return copy(scrollX = clampAxis(nx, loX, hiX, visW, m), scrollY = clampAxis(ny, loY, hiY, visH, m))
    }

    /** Content [lo, hi] shown in a window of [vis] with [margin] of slack; centered when it fits entirely. */
    private fun clampAxis(v: Float, lo: Float, hi: Float, vis: Float, margin: Float): Float {
        val minV = lo - margin
        val maxV = hi - vis + margin
        return if (hi - lo + 2 * margin <= vis) (lo + hi - vis) / 2f else v.coerceIn(minV, maxV)
    }

    /** Zoom so [page] fills the width minus [marginPx] on both sides, scrolled to the page's top-left. */
    fun fitWidth(layout: DocumentLayout, page: PageId, marginPx: Float): Viewport {
        val f = layout.frameOf(page)
        if (f.isEmpty) return this
        val newZoom = ((widthPx - 2 * marginPx) / (f.width * pxPerPt)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val newScale = newZoom * pxPerPt
        return copy(zoom = newZoom, scrollX = f.l - (widthPx - f.width * newScale) / 2f / newScale, scrollY = f.t - marginPx / newScale)
    }

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 6f
        private val STEP = sqrt(2.0)

        /** Tile level of a scale: `round(log_√2(scale))`. */
        fun levelFor(scale: Float): Int = (ln(scale.toDouble()) / ln(STEP)).roundToInt()

        /** Scale of tile [level]: `√2^level`. */
        fun scaleOf(level: Int): Float = STEP.pow(level).toFloat()
    }
}
