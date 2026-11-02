package app.notate.core.web

/** Rewrites a chapter's HTML on its way into the WebView so that the layout width is locked before the page lays out. */
object WebHtml {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val META_VIEWPORT = Regex("""<meta\b[^>]*\bname\s*=\s*["']?viewport["']?[^>]*>""", RegexOption.IGNORE_CASE)
    private val NUMERIC_WIDTH = Regex("""\bwidth\s*=\s*\d""", RegexOption.IGNORE_CASE)
    private val HEAD_OPEN = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HTML_OPEN = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * Replaces the page's viewport declarations by `width=<widthCss>` (marked `data-notate`), unless the page declares a fixed
     * pixel width itself (fixed-layout books), which wins. The text is handled as Latin-1 so that every byte survives, whatever
     * the document's encoding; UTF-16 pages and very large files are returned unchanged.
     */
    fun injectViewport(html: ByteArray, widthCss: Int): ByteArray {
        if (html.size > MAX_BYTES || widthCss <= 0 || looksUtf16(html)) return html
        val text = String(html, Charsets.ISO_8859_1)
        val existing = META_VIEWPORT.findAll(text).map { it.value }.toList()
        if (existing.any { NUMERIC_WIDTH.containsMatchIn(it) && !it.contains("data-notate") }) return html
        val tag = """<meta name="viewport" content="width=$widthCss, minimum-scale=0.1, maximum-scale=10, user-scalable=yes" data-notate="1"/>"""
        val stripped = META_VIEWPORT.replace(text, "")
        val head = HEAD_OPEN.find(stripped)
        val out = when {
            head != null -> stripped.substring(0, head.range.last + 1) + tag + stripped.substring(head.range.last + 1)
            else -> {
                val root = HTML_OPEN.find(stripped)
                if (root != null) stripped.substring(0, root.range.last + 1) + "<head>$tag</head>" + stripped.substring(root.range.last + 1)
                else tag + stripped
            }
        }
        return out.toByteArray(Charsets.ISO_8859_1)
    }

    private fun looksUtf16(b: ByteArray): Boolean {
        if (b.size >= 2 && ((b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) || (b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()))) return true
        for (i in 0 until minOf(b.size, 64)) if (b[i] == 0.toByte()) return true
        return false
    }
}
