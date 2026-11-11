package app.notate.core.doc.zoom

import app.notate.core.geometry.Affine
import app.notate.core.geometry.Rect
import app.notate.core.model.PageId
import app.notate.core.model.Units
import kotlin.math.max
import kotlin.math.min

/**
 * State of the zoom writing window: a magnified writing area shows the page rectangle [box] and everything written there lands
 * in [box]. Pure; coordinates are page coordinates (pt). Main thread only.
 *
 * The box is [baseWidthPt] / [magnification] wide, where `baseWidthPt` is the page width that the writing area would show at the
 * pane's own zoom (so a magnification of 3 writes three times larger than the page is drawn), and has the writing area's [aspect]
 * (width / height). The box always stays inside [pageFrame].
 */
class ZoomWindowState(
    page: PageId,
    pageFrame: Rect,
    baseWidthPt: Float,
    aspect: Float,
    lineSpacingPt: Float? = null,
    val marginPt: Float = Units.mm(10f),
    magnification: Float = DEFAULT_MAGNIFICATION,
) {
    /** The page being written on. */
    var page: PageId = page
        private set

    /** Extent of that page in page coordinates. */
    var pageFrame: Rect = pageFrame
        private set

    /** Ruled-line spacing of the page's paper (a new line moves down by it); null = no ruling, the box height is used. */
    var lineSpacingPt: Float? = lineSpacingPt
        private set

    /** Target rectangle on the page. */
    var box: Rect = Rect.EMPTY
        private set

    var magnification: Float = magnification.coerceIn(MIN_MAGNIFICATION, MAX_MAGNIFICATION)
        private set

    private var baseWidthPt = baseWidthPt
    private var aspect = aspect

    init {
        require(baseWidthPt > 0f && aspect > 0f) { "empty writing area" }
        place(pageFrame.l + marginPt, pageFrame.t + marginPt)
    }

    /** Height of one text line: the paper's ruling, or 80 % of the box. */
    val lineHeightPt: Float get() = lineSpacingPt?.takeIf { it > 0f } ?: (box.height * LINE_FRACTION)

    /** Moves the box so its top-left corner is at ([x], [y]) (kept inside the page). */
    fun moveTo(x: Float, y: Float) = place(x, y)

    /** Moves right by [fraction] of the box width (negative = left). */
    fun step(fraction: Float) = place(box.l + box.width * fraction, box.t)

    /** Back to the left margin, one line down. */
    fun newLine() = place(pageFrame.l + marginPt, box.t + lineHeightPt)

    /** Sets the magnification (clamped to [MIN_MAGNIFICATION]..[MAX_MAGNIFICATION]); the box keeps its top-left corner. */
    fun setMagnification(m: Float) {
        magnification = m.coerceIn(MIN_MAGNIFICATION, MAX_MAGNIFICATION)
        place(box.l, box.t)
    }

    /** The writing area or the pane's zoom changed: the box gets the new size and keeps its top-left corner. */
    fun resize(baseWidthPt: Float, aspect: Float) {
        if (baseWidthPt <= 0f || aspect <= 0f) return
        this.baseWidthPt = baseWidthPt
        this.aspect = aspect
        place(box.l, box.t)
    }

    /** Continues on another page (or a page whose paper changed): starts at its top-left margin. */
    fun switchPage(page: PageId, pageFrame: Rect, lineSpacingPt: Float?) {
        this.page = page
        this.pageFrame = pageFrame
        this.lineSpacingPt = lineSpacingPt
        place(pageFrame.l + marginPt, pageFrame.t + marginPt)
    }

    /** The page's paper changed (ruling or size) but the box stays where it is. */
    fun updatePage(pageFrame: Rect, lineSpacingPt: Float?) {
        this.pageFrame = pageFrame
        this.lineSpacingPt = lineSpacingPt
        place(box.l, box.t)
    }

    /**
     * Called after a stroke was written ([strokeBounds] in page coordinates). A stroke that ends beyond [writingAreaFraction] of
     * the box width moves the box right by 60 % of its width, or to the next line when the box is already at the right margin.
     * Returns true when the box moved.
     */
    fun advanceAfterStroke(strokeBounds: Rect, writingAreaFraction: Float = WRITING_AREA_FRACTION): Boolean {
        if (strokeBounds == Rect.EMPTY) return false
        if (strokeBounds.r < box.l + box.width * writingAreaFraction) return false
        val before = box
        val rightLimit = pageFrame.r - marginPt
        if (strokeBounds.r >= rightLimit || box.r >= pageFrame.r - EPS) newLine() else step(ADVANCE_FRACTION)
        return box != before
    }

    /** View-local writing-area pixels (origin at the area's top-left) → page coordinates. */
    fun writingToPage(writingWidthPx: Float, writingHeightPx: Float): Affine {
        require(writingWidthPx > 0f && writingHeightPx > 0f) { "empty writing area" }
        return Affine.translate(box.l, box.t) * Affine.scale(box.width / writingWidthPx, box.height / writingHeightPx)
    }

    private fun place(x: Float, y: Float) {
        val w = baseWidthPt / magnification
        val h = w / aspect
        val l = clamp(x, pageFrame.l, pageFrame.r - w)
        val t = clamp(y, pageFrame.t, pageFrame.b - h)
        box = Rect(l, t, l + w, t + h)
    }

    /** [v] in `lo..hi`; when the range is empty (the box is larger than the page) the low end wins. */
    private fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(v, hi))

    companion object {
        const val MIN_MAGNIFICATION = 2f
        const val MAX_MAGNIFICATION = 5f
        const val DEFAULT_MAGNIFICATION = 3f

        /** Step of the − / + buttons. */
        const val MAGNIFICATION_STEP = 0.5f

        /** Share of the box width after which a stroke end moves the box on. */
        const val WRITING_AREA_FRACTION = 0.85f

        /** How far the box moves after such a stroke (of its width). */
        const val ADVANCE_FRACTION = 0.6f

        /** The ← / → buttons move by this share of the box width. */
        const val BUTTON_STEP_FRACTION = 0.8f

        private const val LINE_FRACTION = 0.8f
        private const val EPS = 0.01f
    }
}
