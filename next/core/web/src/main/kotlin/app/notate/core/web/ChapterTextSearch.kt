package app.notate.core.web

/** One chapter's match count and a few text snippets around the first matches. */
data class ChapterHits(val href: String, val count: Int, val snippets: List<String>)

/** Plain-text search over chapter HTML (R4-3): tags are removed, matching ignores case. Pure. */
object ChapterTextSearch {
    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
    private val BLOCK = Regex("(?i)</?(p|div|br|li|tr|h[1-6]|section|article|blockquote|pre|table|ul|ol)\\b[^>]*>")
    private val HIDDEN = Regex("(?is)<(script|style|head)\\b[^>]*>.*?</\\1\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val TAG = Regex("(?s)<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val SPACES = Regex("\\s+")

    /** The visible text of [html]: no tags, scripts or styles, entities decoded, white space collapsed. */
    fun plainText(html: String): String {
        var s = COMMENT.replace(html, " ")
        s = HIDDEN.replace(s, " ")
        s = BLOCK.replace(s, " ")           // block boundaries separate words; inline tags do not ("a<b>b</b>" is "ab")
        s = TAG.replace(s, "")
        s = ENTITY.replace(s) { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("#x") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
                name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
                else -> ENTITIES[name]
            } ?: m.value
        }
        return SPACES.replace(s, " ").trim()
    }

    private fun codePoint(cp: Int): String? = if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else null

    /** Number of non-overlapping case-insensitive matches of [query] in [text]. */
    fun count(text: String, query: String): Int {
        if (query.isEmpty()) return 0
        var n = 0
        var i = text.indexOf(query, 0, ignoreCase = true)
        while (i >= 0) {
            n++
            i = text.indexOf(query, i + query.length, ignoreCase = true)
        }
        return n
    }

    /** Matches of [query] in the HTML of one chapter; null when there are none. */
    fun search(href: String, html: String, query: String, maxSnippets: Int = 3, context: Int = 30): ChapterHits? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val text = plainText(html)
        val snippets = ArrayList<String>()
        var count = 0
        var i = text.indexOf(q, 0, ignoreCase = true)
        while (i >= 0) {
            count++
            if (snippets.size < maxSnippets) {
                val from = (i - context).coerceAtLeast(0)
                val to = (i + q.length + context).coerceAtMost(text.length)
                snippets.add((if (from > 0) "…" else "") + text.substring(from, to) + (if (to < text.length) "…" else ""))
            }
            i = text.indexOf(q, i + q.length, ignoreCase = true)
        }
        return if (count == 0) null else ChapterHits(href, count, snippets)
    }
}
