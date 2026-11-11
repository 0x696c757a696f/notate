package app.notate.core.web

/** Rewrites a chapter's HTML on its way into the WebView so that the layout width is locked before the page lays out. */
object WebHtml {
    /**
     * How much of a page is read to find `<head>` and its viewport declarations; the rest streams through unread. A page's
     * head sits at its start, so a page of any size gets its viewport locked without being loaded into memory.
     */
    const val HEAD_BYTES = 64 * 1024
    private val META_VIEWPORT = Regex("""<meta\b[^>]*\bname\s*=\s*["']?viewport["']?[^>]*>""", RegexOption.IGNORE_CASE)
    private val NUMERIC_WIDTH = Regex("""\bwidth\s*=\s*\d""", RegexOption.IGNORE_CASE)
    private val HEAD_OPEN = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HTML_OPEN = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * Replaces the page's viewport declarations by `width=<widthCss>` (marked `data-notate`), unless the page declares a fixed
     * pixel width itself (fixed-layout books), which wins. The text is handled as Latin-1 so that every byte survives, whatever
     * the document's encoding; UTF-16 pages are returned unchanged.
     */
    fun injectViewport(html: ByteArray, widthCss: Int): ByteArray = injectViewport(html, widthCss, partial = false) ?: html

    /**
     * The page as served: only its first [HEAD_BYTES] are read and rewritten, the rest streams from [input] unread. [length]
     * is the whole file's size. Returns the stream to serve and its length; null when [widthCss] asks for no lock.
     * When the start holds neither `<head>` nor `<html>` it is left alone (the script bridge locks the width later), because
     * a tag put before a doctype that comes later would switch the page to quirks mode.
     */
    fun injectViewportStream(input: java.io.InputStream, length: Long, widthCss: Int): Pair<java.io.InputStream, Long>? {
        if (widthCss <= 0) return null
        val prefix = readUpTo(input, HEAD_BYTES)
        val out = if (prefix.size.toLong() >= length) injectViewport(prefix, widthCss, partial = false)
        else injectViewport(prefix, widthCss, partial = true)
        val head = out ?: prefix
        return java.io.SequenceInputStream(java.io.ByteArrayInputStream(head), input) to (length - prefix.size + head.size)
    }

    private fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = input.read(buf, n, max - n)
            if (r < 0) break
            n += r
        }
        return if (n == max) buf else buf.copyOf(n)
    }

    private fun injectViewport(html: ByteArray, widthCss: Int, partial: Boolean): ByteArray? {
        if (widthCss <= 0 || looksUtf16(html)) return null
        val text = String(html, Charsets.ISO_8859_1)
        val existing = META_VIEWPORT.findAll(text).map { it.value }.toList()
        if (existing.any { NUMERIC_WIDTH.containsMatchIn(it) && !it.contains("data-notate") }) return null
        val tag = """<meta name="viewport" content="width=$widthCss, minimum-scale=0.1, maximum-scale=10, user-scalable=yes" data-notate="1"/>"""
        val stripped = META_VIEWPORT.replace(text, "")
        val head = HEAD_OPEN.find(stripped)
        val out = when {
            head != null -> stripped.substring(0, head.range.last + 1) + tag + stripped.substring(head.range.last + 1)
            else -> {
                val root = HTML_OPEN.find(stripped)
                if (root != null) stripped.substring(0, root.range.last + 1) + "<head>$tag</head>" + stripped.substring(root.range.last + 1)
                else if (partial) return null
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
