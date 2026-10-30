package com.alexdremov.notate.webdoc

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.net.URLDecoder

/**
 * Minimal EPUB 2/3 package reader: finds the OPF through META-INF/container.xml,
 * reads title, layout, manifest and spine, and labels spine items from the
 * EPUB 3 nav document or the EPUB 2 NCX when present.
 *
 * Works on an already-extracted EPUB directory so the WebView can serve the
 * files directly (scripts, iframes, fonts, SVG and MathML all load as-is).
 */
object EpubParser {
    data class Result(
        val title: String?,
        val pages: List<WebDocPage>,
        val fixedLayout: Boolean,
    )

    private class ManifestItem(
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    fun parse(root: File): Result {
        val containerFile = File(root, "META-INF/container.xml")
        if (!containerFile.isFile) throw IOException("Not an EPUB: META-INF/container.xml missing")

        var opfPath: String? = null
        readXml(containerFile) { p ->
            if (p.eventType == XmlPullParser.START_TAG && localName(p) == "rootfile" && opfPath == null) {
                opfPath = attr(p, "full-path")
            }
        }
        val opfRel = normalizePath(opfPath ?: throw IOException("EPUB container has no rootfile"))
        val opfFile = File(root, opfRel)
        if (!opfFile.isFile) throw IOException("EPUB package file not found: $opfRel")
        val opfDir = opfRel.substringBeforeLast('/', "")

        var title: String? = null
        var fixedLayout = false
        var ncxId: String? = null
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<String>()

        var inTitle = false
        var inLayoutMeta = false
        readXml(opfFile) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    when (localName(p)) {
                        "title" -> if (title == null) inTitle = true
                        "meta" -> {
                            if (attr(p, "property") == "rendition:layout") inLayoutMeta = true
                            // EPUB 2 style: <meta name="fixed-layout" content="true"/>
                            if (attr(p, "name") == "fixed-layout" && attr(p, "content") == "true") fixedLayout = true
                        }
                        "item" -> {
                            val id = attr(p, "id")
                            val href = attr(p, "href")
                            if (id != null && href != null) {
                                manifest[id] =
                                    ManifestItem(
                                        href = resolve(opfDir, href),
                                        mediaType = attr(p, "media-type") ?: "",
                                        properties = attr(p, "properties") ?: "",
                                    )
                            }
                        }
                        "spine" -> ncxId = attr(p, "toc")
                        "itemref" -> {
                            val idref = attr(p, "idref")
                            if (idref != null && attr(p, "linear") != "no") spine.add(idref)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = p.text?.trim().orEmpty()
                    if (inTitle && text.isNotEmpty()) title = text
                    if (inLayoutMeta && text == "pre-paginated") fixedLayout = true
                }
                XmlPullParser.END_TAG -> {
                    when (localName(p)) {
                        "title" -> inTitle = false
                        "meta" -> inLayoutMeta = false
                    }
                }
            }
        }

        val labels = HashMap<String, String>()
        manifest.values.firstOrNull { it.properties.split(' ').contains("nav") }?.let { nav ->
            runCatching { readNavLabels(root, nav.href, labels) }
        }
        val ncx = ncxId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        if (ncx != null) runCatching { readNcxLabels(root, ncx.href, labels) }

        val pages =
            spine.mapNotNull { manifest[it] }.mapIndexed { index, item ->
                WebDocPage(
                    href = item.href,
                    title = labels[item.href] ?: "Section ${index + 1}",
                )
            }
        if (pages.isEmpty()) throw IOException("EPUB spine is empty")
        return Result(title = title, pages = pages, fixedLayout = fixedLayout)
    }

    private fun readNavLabels(
        root: File,
        navHref: String,
        out: MutableMap<String, String>,
    ) {
        val navDir = navHref.substringBeforeLast('/', "")
        var currentHref: String? = null
        val text = StringBuilder()
        readXml(File(root, navHref)) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG ->
                    if (localName(p) == "a") {
                        currentHref = attr(p, "href")?.let { resolve(navDir, it) }
                        text.setLength(0)
                    }
                XmlPullParser.TEXT -> if (currentHref != null) text.append(p.text)
                XmlPullParser.END_TAG ->
                    if (localName(p) == "a") {
                        val href = currentHref
                        val label = text.toString().trim().replace(Regex("\\s+"), " ")
                        if (href != null && label.isNotEmpty()) out.putIfAbsent(href, label)
                        currentHref = null
                    }
            }
        }
    }

    private fun readNcxLabels(
        root: File,
        ncxHref: String,
        out: MutableMap<String, String>,
    ) {
        val ncxDir = ncxHref.substringBeforeLast('/', "")
        var pendingLabel: String? = null
        var inText = false
        readXml(File(root, ncxHref)) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG ->
                    when (localName(p)) {
                        "navPoint" -> pendingLabel = null
                        "text" -> inText = true
                        "content" -> {
                            val src = attr(p, "src")
                            val label = pendingLabel
                            if (src != null && label != null) out.putIfAbsent(resolve(ncxDir, src), label)
                        }
                    }
                XmlPullParser.TEXT ->
                    if (inText) {
                        val t = p.text?.trim().orEmpty()
                        if (t.isNotEmpty()) pendingLabel = t
                    }
                XmlPullParser.END_TAG -> if (localName(p) == "text") inText = false
            }
        }
    }

    /** Resolves [href] (relative, possibly %-encoded, possibly with #fragment) against [baseDir]. */
    fun resolve(
        baseDir: String,
        href: String,
    ): String {
        val noFragment = href.substringBefore('#').substringBefore('?')
        val decoded =
            try {
                URLDecoder.decode(noFragment.replace("+", "%2B"), "UTF-8")
            } catch (e: IllegalArgumentException) {
                noFragment
            }
        return if (decoded.startsWith("/")) normalizePath(decoded) else normalizePath(if (baseDir.isEmpty()) decoded else "$baseDir/$decoded")
    }

    /** Collapses `.`/`..` segments; never climbs above the root. */
    fun normalizePath(path: String): String {
        val out = ArrayList<String>()
        for (segment in path.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(segment)
            }
        }
        return out.joinToString("/")
    }

    private fun localName(p: XmlPullParser): String = p.name.substringAfter(':')

    private fun attr(
        p: XmlPullParser,
        name: String,
    ): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i).substringAfter(':') == name) return p.getAttributeValue(i)
        }
        return null
    }

    private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
    private val HTML_ENTITIES =
        mapOf(
            "nbsp" to 160, "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201, "ndash" to 8211, "mdash" to 8212,
            "lsquo" to 8216, "rsquo" to 8217, "ldquo" to 8220, "rdquo" to 8221, "hellip" to 8230, "middot" to 183,
            "copy" to 169, "reg" to 174, "times" to 215, "deg" to 176, "laquo" to 171, "raquo" to 187,
        )

    /** Rewrites HTML named entities to numeric references; unknown ones become literal text. */
    internal fun sanitizeEntities(xml: String): String =
        Regex("&([A-Za-z][A-Za-z0-9]*);").replace(xml) { m ->
            val name = m.groupValues[1]
            when {
                name in XML_ENTITIES -> m.value
                HTML_ENTITIES.containsKey(name) -> "&#${HTML_ENTITIES[name]};"
                else -> "&amp;$name;"
            }
        }

    private fun readXml(
        file: File,
        onEvent: (XmlPullParser) -> Unit,
    ) {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        // Real-world nav/NCX files often contain HTML entities (&nbsp;) that XML parsers reject.
        parser.setInput(java.io.StringReader(sanitizeEntities(file.readText())))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            onEvent(parser)
            event =
                try {
                    parser.next()
                } catch (e: org.xmlpull.v1.XmlPullParserException) {
                    // Stop at the first unrecoverable error but keep what was read.
                    XmlPullParser.END_DOCUMENT
                }
        }
    }
}
