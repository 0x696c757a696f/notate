package app.notate.core.web

/** One `<itemref>` of an EPUB spine; [href] is relative to the content root. */
data class SpineItem(val href: String, val linear: Boolean)

/** Table-of-contents node. [href] is root-relative and keeps its `#fragment`; null for pure headings. */
data class TocEntry(val title: String, val href: String?, val children: List<TocEntry> = emptyList())

/** What [EpubParser] reads from an extracted EPUB package. */
data class EpubInfo(
    val title: String?,
    val author: String?,
    val coverHref: String?,
    val spine: List<SpineItem>,
    val fixedLayout: Boolean,
    val viewport: Pair<Int, Int>?,
    val toc: List<TocEntry>,
)

/** Where an import came from. */
enum class WebSourceKind { EPUB, HTML_FILE, ZIP, FOLDER }

enum class WebDocType { EPUB, HTML }

/** One page row to create. [title] is null when the source gives none (the UI supplies a numbered fallback). */
data class PlannedPage(val href: String, val title: String?)

data class WebImportPlan(
    val title: String,
    val author: String?,
    val kind: WebDocType,
    val entryHref: String,
    val pages: List<PlannedPage>,
    val toc: List<TocEntry>,
    val coverHref: String?,
    val fixedLayout: Boolean = false,
)

/** Import failure with a reason the UI maps to a message. */
class WebImportException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { NOT_SUPPORTED, TOO_LARGE, UNSAFE }
}
