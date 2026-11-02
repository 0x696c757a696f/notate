package app.notate.core.web

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException

/** Decides which pages a web import creates. Reads files in an already-extracted directory. Any thread. */
class WebImportPlanner(newXmlParser: () -> XmlPullParser) {
    private val epub = EpubParser(newXmlParser)

    /**
     * Plans the import of [root]. [displayName] (source file or folder name) is the title fallback.
     * A ZIP or folder that contains `META-INF/container.xml` is treated as an EPUB.
     * Throws [WebImportException] (NOT_SUPPORTED) when there is nothing to show.
     */
    fun plan(root: File, source: WebSourceKind, displayName: String): WebImportPlan {
        val isEpub = File(root, "META-INF/container.xml").isFile
        if (source == WebSourceKind.EPUB && !isEpub) throw notSupported("Not an EPUB")
        return if (isEpub) planEpub(root, displayName) else planHtml(root, displayName)
    }

    private fun planEpub(root: File, displayName: String): WebImportPlan {
        val info = try {
            epub.parse(root)
        } catch (e: IOException) {
            throw WebImportException(WebImportException.Reason.NOT_SUPPORTED, e.message ?: "Bad EPUB", e)
        }
        val labels = HashMap<String, String>()
        fun walk(entries: List<TocEntry>): Unit = entries.forEach { e ->
            e.href?.let { labels.putIfAbsent(it.substringBefore('#'), e.title) }
            walk(e.children)
        }
        walk(info.toc)
        val reading = info.spine.filter { it.linear }.ifEmpty { info.spine }
        val pages = reading.map { PlannedPage(it.href, labels[it.href]) }
        return WebImportPlan(
            title = info.title ?: displayName.substringBeforeLast('.'),
            author = info.author,
            kind = WebDocType.EPUB,
            entryHref = pages.first().href,
            pages = pages,
            toc = info.toc,
            coverHref = info.coverHref,
            fixedLayout = info.fixedLayout,
        )
    }

    private fun planHtml(root: File, displayName: String): WebImportPlan {
        val entry = findHtmlEntry(root) ?: throw notSupported("No HTML file found")
        val title = readHtmlTitle(File(root, entry)) ?: displayName.substringBeforeLast('.')
        return WebImportPlan(title, null, WebDocType.HTML, entry, listOf(PlannedPage(entry, title)), emptyList(), null)
    }

    companion object {
        private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")

        /** `index.*` closest to the root wins; else the only HTML at the root; else the shallowest (then alphabetically first). */
        fun findHtmlEntry(root: File): String? {
            val html = root.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in HTML_EXTENSIONS && !it.name.startsWith(".") }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .filterNot { it.startsWith("__MACOSX/") }
                .toList()
            if (html.isEmpty()) return null
            val depth = { p: String -> p.count { it == '/' } }
            html.filter { it.substringAfterLast('/').lowercase().startsWith("index.") }.minByOrNull(depth)?.let { return it }
            html.singleOrNull { depth(it) == 0 }?.let { return it }
            return html.minWith(compareBy(depth, { it }))
        }

        /** Text of the first `<title>` in the first 64 KB, whitespace collapsed; null if absent or blank. */
        fun readHtmlTitle(file: File): String? {
            val head = file.inputStream().use { it.readNBytesCompat(64 * 1024) }.toString(Charsets.UTF_8)
            return Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(head)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun notSupported(msg: String) = WebImportException(WebImportException.Reason.NOT_SUPPORTED, msg)
    }
}
