package app.notate.core.web

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.io.StringReader

/**
 * Minimal EPUB 2/3 package reader working on an already-extracted directory: finds the OPF through
 * `META-INF/container.xml`, then reads title, author, cover, layout, manifest, spine and the table of contents
 * (EPUB 3 nav document, else EPUB 2 NCX).
 *
 * [newParser] supplies a fresh non-validating pull parser per file (Android: the system one; tests: kxml2).
 * Any thread.
 */
class EpubParser(private val newParser: () -> XmlPullParser) {
    private class Item(val href: String, val mediaType: String, val properties: String)

    /** Parses the EPUB in [root]. Throws [IOException] when it is not an EPUB or has an empty spine. */
    fun parse(root: File): EpubInfo {
        val container = File(root, "META-INF/container.xml")
        if (!container.isFile) throw IOException("Not an EPUB: META-INF/container.xml missing")
        var opfPath: String? = null
        readXml(container) { p ->
            if (p.eventType == XmlPullParser.START_TAG && local(p) == "rootfile" && opfPath == null) opfPath = attr(p, "full-path")
        }
        val opfRel = WebResourcePaths.normalize(opfPath ?: throw IOException("EPUB container has no rootfile"))
        val opf = File(root, opfRel)
        if (!opf.isFile) throw IOException("EPUB package file not found: $opfRel")
        val opfDir = opfRel.substringBeforeLast('/', "")

        var title: String? = null
        var author: String? = null
        var fixed = false
        var viewport: Pair<Int, Int>? = null
        var ncxId: String? = null
        var coverMetaId: String? = null
        val manifest = LinkedHashMap<String, Item>()
        val spine = ArrayList<Pair<String, Boolean>>()
        var inTitle = false
        var inCreator = false
        var inLayoutMeta = false
        var inViewportMeta = false
        readXml(opf) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (local(p)) {
                    "title" -> if (title == null) inTitle = true
                    "creator" -> if (author == null) inCreator = true
                    "meta" -> {
                        val property = attr(p, "property")
                        if (property == "rendition:layout") inLayoutMeta = true
                        if (property == "rendition:viewport") inViewportMeta = true
                        if (attr(p, "name") == "fixed-layout" && attr(p, "content") == "true") fixed = true
                        if (attr(p, "name") == "cover") coverMetaId = attr(p, "content")
                    }
                    "item" -> {
                        val id = attr(p, "id")
                        val href = attr(p, "href")
                        if (id != null && href != null) {
                            manifest[id] = Item(resolve(opfDir, href), attr(p, "media-type") ?: "", attr(p, "properties") ?: "")
                        }
                    }
                    "spine" -> ncxId = attr(p, "toc")
                    "itemref" -> attr(p, "idref")?.let { spine.add(it to (attr(p, "linear") != "no")) }
                }
                XmlPullParser.TEXT -> {
                    val text = p.text?.trim().orEmpty()
                    if (inTitle && text.isNotEmpty()) title = text
                    if (inCreator && text.isNotEmpty()) author = text
                    if (inLayoutMeta && text == "pre-paginated") fixed = true
                    if (inViewportMeta && text.isNotEmpty()) viewport = parseViewport(text) ?: viewport
                }
                XmlPullParser.END_TAG -> when (local(p)) {
                    "title" -> inTitle = false
                    "creator" -> inCreator = false
                    "meta" -> { inLayoutMeta = false; inViewportMeta = false }
                }
            }
        }

        val spineItems = spine.mapNotNull { (idref, linear) -> manifest[idref]?.let { SpineItem(it.href, linear) } }
        if (spineItems.isEmpty()) throw IOException("EPUB spine is empty")

        val navItem = manifest.values.firstOrNull { it.properties.split(' ').contains("nav") }
        val ncxItem = ncxId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        var toc = navItem?.let { runCatching { readNav(root, it.href) }.getOrNull() }.orEmpty()
        if (toc.isEmpty() && ncxItem != null) toc = runCatching { readNcx(root, ncxItem.href) }.getOrNull().orEmpty()

        val cover = manifest.values.firstOrNull { it.properties.split(' ').contains("cover-image") }
            ?: coverMetaId?.let { manifest[it] }?.takeIf { it.mediaType.startsWith("image/") }
        if (fixed && viewport == null) viewport = spineItems.firstNotNullOfOrNull { readHtmlViewport(File(root, it.href)) }
        return EpubInfo(title, author, cover?.href, spineItems, fixed, viewport, toc)
    }

    private class Node(var title: String = "", var href: String? = null, val children: MutableList<TocEntry> = ArrayList())

    private fun readNav(root: File, navHref: String): List<TocEntry> {
        val navDir = navHref.substringBeforeLast('/', "")
        val top = ArrayList<TocEntry>()
        val stack = ArrayList<Node>()
        var inNav = false
        var done = false
        var labelNode: Node? = null
        var navDepth = 0
        var labelDepth = 0
        val label = StringBuilder()
        readXml(File(root, navHref)) { p ->
            if (done) return@readXml
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (local(p)) {
                    "nav" -> {
                        if (!inNav && attr(p, "type").orEmpty().split(' ').contains("toc")) { inNav = true; navDepth = 0 }
                        else if (inNav) navDepth++
                    }
                    "li" -> if (inNav) stack.add(Node())
                    "a", "span" -> if (inNav && stack.isNotEmpty()) {
                        if (labelNode != null) labelDepth++ else {
                            labelNode = stack.last(); label.setLength(0)
                            if (local(p) == "a") stack.last().href = attr(p, "href")?.let { resolveKeepFragment(navDir, it) }
                        }
                    }
                }
                XmlPullParser.TEXT -> if (labelNode != null) label.append(p.text)
                XmlPullParser.END_TAG -> when (local(p)) {
                    "a", "span" -> if (labelNode != null) {
                        if (labelDepth > 0) labelDepth-- else {
                            val text = label.toString().trim().replace(WHITESPACE, " ")
                            if (labelNode!!.title.isEmpty()) labelNode!!.title = text
                            labelNode = null
                        }
                    }
                    "li" -> if (inNav && stack.isNotEmpty()) {
                        val n = stack.removeAt(stack.size - 1)
                        val entry = TocEntry(n.title, n.href, n.children)
                        if (n.title.isNotEmpty() || n.children.isNotEmpty()) (stack.lastOrNull()?.children ?: top).add(entry)
                    }
                    "nav" -> if (inNav) { if (navDepth == 0) { inNav = false; done = true } else navDepth-- }
                }
            }
        }
        return top
    }

    private fun readNcx(root: File, ncxHref: String): List<TocEntry> {
        val ncxDir = ncxHref.substringBeforeLast('/', "")
        val top = ArrayList<TocEntry>()
        val stack = ArrayList<Node>()
        var inLabel = false
        var inText = false
        readXml(File(root, ncxHref)) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (local(p)) {
                    "navPoint" -> stack.add(Node())
                    "navLabel" -> inLabel = true
                    "text" -> inText = true
                    "content" -> if (stack.isNotEmpty()) stack.last().href = attr(p, "src")?.let { resolveKeepFragment(ncxDir, it) }
                }
                XmlPullParser.TEXT -> if (inLabel && inText && stack.isNotEmpty()) {
                    val t = p.text?.trim().orEmpty()
                    if (t.isNotEmpty() && stack.last().title.isEmpty()) stack.last().title = t.replace(WHITESPACE, " ")
                }
                XmlPullParser.END_TAG -> when (local(p)) {
                    "text" -> inText = false
                    "navLabel" -> inLabel = false
                    "navPoint" -> if (stack.isNotEmpty()) {
                        val n = stack.removeAt(stack.size - 1)
                        (stack.lastOrNull()?.children ?: top).add(TocEntry(n.title, n.href, n.children))
                    }
                }
            }
        }
        return top
    }

    private fun readXml(file: File, onEvent: (XmlPullParser) -> Unit) {
        val parser = newParser()
        // Real-world nav/NCX files often contain HTML entities (&nbsp;) that XML parsers reject.
        parser.setInput(StringReader(sanitizeEntities(file.readText())))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            onEvent(parser)
            event = try { parser.next() } catch (e: XmlPullParserException) { XmlPullParser.END_DOCUMENT } // keep what was read
        }
    }

    private fun readHtmlViewport(file: File): Pair<Int, Int>? {
        if (!file.isFile) return null
        val head = file.inputStream().use { it.readNBytesCompat(64 * 1024) }.toString(Charsets.UTF_8)
        val tag = Regex("<meta[^>]+name=[\"']viewport[\"'][^>]*>", RegexOption.IGNORE_CASE).find(head)?.value ?: return null
        val content = Regex("content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: return null
        return parseViewport(content)
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
        private val HTML_ENTITIES = mapOf(
            "nbsp" to 160, "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201, "ndash" to 8211, "mdash" to 8212,
            "lsquo" to 8216, "rsquo" to 8217, "ldquo" to 8220, "rdquo" to 8221, "hellip" to 8230, "middot" to 183,
            "copy" to 169, "reg" to 174, "times" to 215, "deg" to 176, "laquo" to 171, "raquo" to 187,
        )

        /** Parses `width=1200, height=800` (also `;`-separated); null unless both are positive integers. */
        fun parseViewport(s: String): Pair<Int, Int>? {
            val parts = s.split(',', ';', ' ').mapNotNull { kv ->
                val (k, v) = kv.split('=', ':').takeIf { it.size == 2 } ?: return@mapNotNull null
                k.trim().lowercase() to v.trim().removeSuffix("px").toFloatOrNull()?.toInt()
            }.toMap()
            val w = parts["width"] ?: return null
            val h = parts["height"] ?: return null
            return if (w > 0 && h > 0) w to h else null
        }

        /** Resolves [href] (relative, possibly %-encoded, with optional `#fragment` and `?query` dropped) against [baseDir]. */
        fun resolve(baseDir: String, href: String): String {
            val noFragment = href.substringBefore('#').substringBefore('?')
            val decoded = WebResourcePaths.decode(noFragment) ?: noFragment
            return WebResourcePaths.normalize(if (decoded.startsWith("/") || baseDir.isEmpty()) decoded else "$baseDir/$decoded")
        }

        /** Like [resolve] but keeps the `#fragment` (decoded) so the TOC can point inside a chapter. */
        fun resolveKeepFragment(baseDir: String, href: String): String {
            val path = resolve(baseDir, href)
            val fragment = href.substringAfter('#', "").takeIf { href.contains('#') && it.isNotEmpty() }
            return if (fragment == null) path else "$path#${WebResourcePaths.decode(fragment) ?: fragment}"
        }

        /** Rewrites HTML named entities to numeric references; unknown ones become literal text. */
        internal fun sanitizeEntities(xml: String): String = Regex("&([A-Za-z][A-Za-z0-9]*);").replace(xml) { m ->
            val name = m.groupValues[1]
            when {
                name in XML_ENTITIES -> m.value
                HTML_ENTITIES.containsKey(name) -> "&#${HTML_ENTITIES[name]};"
                else -> "&amp;$name;"
            }
        }

        private fun local(p: XmlPullParser): String = p.name.substringAfter(':')

        private fun attr(p: XmlPullParser, name: String): String? {
            for (i in 0 until p.attributeCount) if (p.getAttributeName(i).substringAfter(':') == name) return p.getAttributeValue(i)
            return null
        }
    }
}

/** `InputStream.readNBytes(int)` needs API 33; minSdk is 26. */
internal fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val buf = ByteArray(max)
    var read = 0
    while (read < max) {
        val n = read(buf, read, max - read)
        if (n < 0) break
        read += n
    }
    return buf.copyOf(read)
}
