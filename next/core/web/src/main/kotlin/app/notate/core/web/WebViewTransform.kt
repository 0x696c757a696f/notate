package app.notate.core.web

/**
 * Mapping between pane pixels and page points for a web chapter (02-architecture §7). Pure.
 *
 * [scale] is device px per CSS px (the layout-lock scale times the user's pinch zoom, i.e. what `WebView.getScale()` reports);
 * [scrollXPx]/[scrollYPx] is how far the page content has moved in device px, the WebView's own scroll plus the scroll of an
 * inner scrolling element. A page point is 1/72 in = 0.75 × CSS px.
 */
data class WebViewTransform(val scale: Float, val scrollXPx: Float, val scrollYPx: Float) {
    /** Page x (pt) of a pane pixel column. */
    fun viewToPtX(viewPx: Float): Float = (viewPx + scrollXPx) / scale * CSS_PX_TO_PT

    /** Page y (pt) of a pane pixel row. */
    fun viewToPtY(viewPx: Float): Float = (viewPx + scrollYPx) / scale * CSS_PX_TO_PT

    fun ptToViewX(pt: Float): Float = pt / CSS_PX_TO_PT * scale - scrollXPx

    fun ptToViewY(pt: Float): Float = pt / CSS_PX_TO_PT * scale - scrollYPx

    /** Device px of one page point. */
    val pxPerPt: Float get() = scale / CSS_PX_TO_PT

    companion object {
        const val CSS_PX_TO_PT = 0.75f

        fun cssToPt(css: Float): Float = css * CSS_PX_TO_PT

        fun ptToCss(pt: Float): Float = pt / CSS_PX_TO_PT
    }
}

/** Rules for the fixed layout of a web chapter (CSS px) and the "layout changed" banner. */
object WebLayoutLock {
    /**
     * Percentage for `WebView.setInitialScale` that fits [layoutWidthCss] to a pane of [paneWidthPx]. 100 means one CSS px per
     * device-independent px, so the density cancels out.
     */
    fun initialScalePercent(paneWidthPx: Int, layoutWidthCss: Float, density: Float): Int {
        if (paneWidthPx <= 0 || layoutWidthCss <= 0f || density <= 0f) return 0
        return Math.round(paneWidthPx / layoutWidthCss / density * 100f).coerceAtLeast(1)
    }

    /**
     * Whether to warn that a chapter's ink may no longer sit on its text: the chapter was reflowed ([layoutGen] above the
     * imported 1) and holds ink, and the user has not dismissed the notice.
     */
    fun showLayoutChangedBanner(layoutGen: Int, hasInk: Boolean, dismissed: Boolean): Boolean =
        layoutGen > INITIAL_GEN && hasInk && !dismissed

    const val INITIAL_GEN = 1
}

/** The private origin a web document is served from. */
object WebOrigin {
    private const val SUFFIX = ".webdoc.notate"

    /** `<first 16 chars of the doc id, lowercase>.webdoc.notate`; only letters and digits so it is always a valid host. */
    fun host(docId: String): String {
        val cleaned = docId.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(16)
        return (cleaned.ifEmpty { "doc" }) + SUFFIX
    }

    fun base(docId: String): String = "https://${host(docId)}/"

    fun urlFor(docId: String, href: String): String = base(docId) + href.removePrefix("/")

    /** Splits a same-origin URL into its root-relative, decoded path and the fragment (null when none); null for other origins. */
    fun parse(docId: String, url: String): Pair<String, String?>? {
        val prefix = base(docId)
        if (!url.startsWith(prefix)) return null
        val rest = url.substring(prefix.length)
        val fragment = rest.substringAfter('#', "").takeIf { rest.contains('#') && it.isNotEmpty() }
        val noFragment = rest.substringBefore('#').substringBefore('?')
        val path = WebResourcePaths.decode(noFragment) ?: return null
        return WebResourcePaths.normalize(path) to fragment
    }

    fun isSameOrigin(docId: String, url: String): Boolean = url.startsWith(base(docId))
}

/** Parses an HTTP `Range: bytes=a-b` header for a resource of [length] bytes. */
object ByteRange {
    /** Inclusive start/end, or null when absent, malformed or unsatisfiable. */
    fun parse(header: String?, length: Long): LongRange? {
        if (header == null || length <= 0) return null
        val spec = header.trim().removePrefix("bytes=").takeIf { header.trim().startsWith("bytes=") } ?: return null
        if (spec.contains(',')) return null
        val from = spec.substringBefore('-').trim()
        val to = spec.substringAfter('-', "").trim()
        val start: Long
        val end: Long
        if (from.isEmpty()) {
            val suffix = to.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            start = maxOf(0L, length - suffix)
            end = length - 1
        } else {
            start = from.toLongOrNull() ?: return null
            end = if (to.isEmpty()) length - 1 else minOf(to.toLongOrNull() ?: return null, length - 1)
        }
        if (start < 0 || start >= length || end < start) return null
        return start..end
    }
}
