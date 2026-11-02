package app.notate.core.web

import java.io.File
import java.net.URLDecoder

/** Maps request paths on a web document's private origin to files, and file names to MIME types. Any thread. */
object WebResourcePaths {
    /** Collapses `.`/`..` segments; never climbs above the root. */
    fun normalize(path: String): String {
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

    /** Percent-decodes [s] (a literal `+` stays `+`); null when malformed. */
    fun decode(s: String): String? =
        try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: IllegalArgumentException) { null }

    /** True when [urlPath] must be refused: `..` segments, encoded slashes or backslashes, absolute paths, NUL. */
    fun isUnsafe(urlPath: String): Boolean {
        val lower = urlPath.lowercase()
        if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")) return true
        val decoded = decode(urlPath) ?: return true
        if (decoded.contains('\u0000')) return true
        if (decoded.startsWith("/") || decoded.startsWith("\\")) return true
        if (Regex("^[A-Za-z]:").containsMatchIn(decoded)) return true
        return decoded.replace('\\', '/').split('/').any { it == ".." }
    }

    /**
     * File for a request path below [root], or null when missing, unsafe, or escaping [root] (including through
     * symlinks). A leading single `/` is accepted (URL paths); a directory serves its `index.html`.
     */
    fun resolve(root: File, urlPath: String): File? {
        val path = urlPath.removePrefix("/")
        if (path.startsWith("/") || isUnsafe(path)) return null
        val relative = normalize(decode(path) ?: return null)
        val candidate = if (relative.isEmpty()) root else File(root, relative)
        val file = if (candidate.isDirectory) File(candidate, "index.html") else candidate
        val rootPath = root.canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(rootPath)) return null
        return file.takeIf { it.isFile }
    }

    fun mimeType(file: File): String = mimeForExtension(file.extension)

    fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
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
}
