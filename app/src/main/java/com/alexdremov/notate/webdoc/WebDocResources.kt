package com.alexdremov.notate.webdoc

import java.io.File
import java.net.URLDecoder

/**
 * Maps requests on a document's private origin to files inside its content root.
 *
 * Every document gets its own origin (https://<id>.webdoc.notate/) so that
 * localStorage / IndexedDB state of one book cannot be read by another, and
 * so that ES modules, fetch() and XHR work under normal same-origin rules
 * (they do not over file://).
 */
object WebDocResources {
    const val HOST_SUFFIX = ".webdoc.notate"

    fun hostFor(docId: String): String = docId.take(16).lowercase() + HOST_SUFFIX

    fun originFor(docId: String): String = "https://" + hostFor(docId)

    fun urlFor(
        docId: String,
        href: String,
    ): String = originFor(docId) + "/" + href.split('/').joinToString("/") { encodeSegment(it) }

    /**
     * Returns the file for a request path, or null when it does not exist or would
     * escape [root] (`..`, encoded slashes, symlinks out of the tree).
     */
    fun resolve(
        root: File,
        rawPath: String,
    ): File? {
        val decoded =
            try {
                URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
            } catch (e: IllegalArgumentException) {
                return null
            }
        if (decoded.contains('\u0000')) return null
        val relative = EpubParser.normalizePath(decoded)
        val candidate = if (relative.isEmpty()) root else File(root, relative)
        val file = if (candidate.isDirectory) File(candidate, "index.html") else candidate
        val rootPath = root.canonicalPath + File.separator
        val canonical = file.canonicalPath
        if (!canonical.startsWith(rootPath)) return null
        return file.takeIf { it.isFile }
    }

    /** Path relative to the root for a URL on this document's origin, or null for foreign URLs. */
    fun hrefForUrl(
        docId: String,
        url: String,
    ): String? {
        val origin = originFor(docId)
        if (!url.startsWith("$origin/") && url != origin) return null
        val path = url.removePrefix(origin).substringBefore('#').substringBefore('?')
        return try {
            EpubParser.normalizePath(URLDecoder.decode(path.replace("+", "%2B"), "UTF-8"))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun mimeType(file: File): String =
        when (file.extension.lowercase()) {
            "html", "htm" -> "text/html"
            "xhtml", "xht" -> "application/xhtml+xml"
            "xml", "opf", "ncx" -> "application/xml"
            "css" -> "text/css"
            // ES modules are rejected unless served with a JavaScript MIME type.
            "js", "mjs", "cjs" -> "text/javascript"
            "json", "map" -> "application/json"
            "wasm" -> "application/wasm"
            "svg", "svgz" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "mml" -> "application/mathml+xml"
            else -> "application/octet-stream"
        }

    fun isTextual(mime: String): Boolean =
        mime.startsWith("text/") || mime.endsWith("+xml") || mime == "application/xml" || mime == "application/json"

    private fun encodeSegment(segment: String): String {
        val sb = StringBuilder()
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 0x80 || ch in "-._~!$&'()*+,;=:@") {
                sb.append(ch)
            } else {
                sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            }
        }
        return sb.toString()
    }
}
