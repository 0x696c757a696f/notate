package com.alexdremov.notate.webdoc

import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.ZipUtils
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Imported EPUB / HTML documents live in `<baseDir>/<id>/`:
 *  - `content/`         extracted files served to the WebView
 *  - `meta.json`        [WebDocMeta]
 *  - `annotations.json` [WebDocAnnotations] (ink + reading position)
 *
 * The id is a hash of the imported bytes, so importing the same book again reopens
 * the existing copy together with its notes.
 */
class WebDocLibrary(
    private val baseDir: File,
) {
    companion object {
        private const val TAG = "WebDocLibrary"
        const val MAX_IMPORT_BYTES = 1024L * 1024 * 1024
        private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")

        val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }

    init {
        baseDir.mkdirs()
    }

    fun docDir(id: String) = File(baseDir, id)

    fun contentDir(id: String) = File(docDir(id), "content")

    fun list(): List<WebDocMeta> =
        (baseDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { loadMeta(it.name) }
            .sortedByDescending { it.importedAt }

    fun loadMeta(id: String): WebDocMeta? {
        val f = File(docDir(id), "meta.json")
        if (!f.isFile) return null
        return try {
            json.decodeFromString(WebDocMeta.serializer(), f.readText())
        } catch (e: Exception) {
            Logger.w(TAG, "Corrupt meta for $id", e)
            null
        }
    }

    fun saveMeta(meta: WebDocMeta) = atomicWrite(File(docDir(meta.id), "meta.json"), json.encodeToString(WebDocMeta.serializer(), meta))

    fun loadAnnotations(id: String): WebDocAnnotations {
        val f = File(docDir(id), "annotations.json")
        if (!f.isFile) return WebDocAnnotations()
        return try {
            json.decodeFromString(WebDocAnnotations.serializer(), f.readText())
        } catch (e: Exception) {
            Logger.w(TAG, "Corrupt annotations for $id, keeping a backup", e)
            f.copyTo(File(docDir(id), "annotations.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
            WebDocAnnotations()
        }
    }

    fun saveAnnotations(
        id: String,
        annotations: WebDocAnnotations,
    ) = atomicWrite(File(docDir(id), "annotations.json"), json.encodeToString(WebDocAnnotations.serializer(), annotations))

    fun delete(id: String): Boolean = docDir(id).deleteRecursively()

    /**
     * Imports a single file: `.epub`, a `.zip` with an HTML site inside, or a plain HTML file.
     * [open] is called once; the stream is hashed while it is copied.
     */
    fun importFile(
        displayName: String,
        open: () -> InputStream,
        now: Long = System.currentTimeMillis(),
    ): WebDocMeta {
        val staging = File(baseDir, ".staging-${System.nanoTime()}").apply { mkdirs() }
        try {
            val raw = File(staging, "source")
            val digest = MessageDigest.getInstance("SHA-256")
            open().use { input ->
                DigestInputStream(input, digest).use { dis ->
                    raw.outputStream().use { out -> copyLimited(dis, out) }
                }
            }
            val id = digest.digest().toHex().take(32)
            loadMeta(id)?.let { return it }

            val content = File(staging, "content").apply { mkdirs() }
            val meta =
                if (isZip(raw)) {
                    ZipUtils.unzip(raw, content)
                    if (File(content, "META-INF/container.xml").isFile) {
                        buildEpubMeta(id, content, displayName, now)
                    } else {
                        buildHtmlMeta(id, content, displayName, now)
                    }
                } else {
                    val name = safeFileName(displayName).let { if (it.substringAfterLast('.', "").lowercase() in HTML_EXTENSIONS) it else "$it.html" }
                    raw.renameTo(File(content, name))
                    buildHtmlMeta(id, content, displayName, now)
                }
            return commit(staging, content, meta)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Imports a whole folder (HTML + JS + CSS + fonts + images ...). [files] yields
     * '/'-separated relative paths with a stream opener for each regular file.
     */
    fun importFolder(
        displayName: String,
        files: Sequence<Pair<String, () -> InputStream>>,
        now: Long = System.currentTimeMillis(),
    ): WebDocMeta {
        val staging = File(baseDir, ".staging-${System.nanoTime()}").apply { mkdirs() }
        try {
            val content = File(staging, "content").apply { mkdirs() }
            val rootCanonical = content.canonicalPath + File.separator
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            for ((rawPath, open) in files.sortedBy { it.first }) {
                val rel = EpubParser.normalizePath(rawPath)
                if (rel.isEmpty()) continue
                val target = File(content, rel)
                if (!target.canonicalPath.startsWith(rootCanonical)) continue
                target.parentFile?.mkdirs()
                digest.update(rel.toByteArray(Charsets.UTF_8))
                open().use { input ->
                    DigestInputStream(input, digest).use { dis ->
                        target.outputStream().use { out -> total += copyLimited(dis, out, MAX_IMPORT_BYTES - total) }
                    }
                }
            }
            val id = digest.digest().toHex().take(32)
            loadMeta(id)?.let { return it }
            val meta =
                if (File(content, "META-INF/container.xml").isFile) {
                    buildEpubMeta(id, content, displayName, now)
                } else {
                    buildHtmlMeta(id, content, displayName, now)
                }
            return commit(staging, content, meta)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun commit(
        staging: File,
        content: File,
        meta: WebDocMeta,
    ): WebDocMeta {
        val dir = docDir(meta.id)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        if (!content.renameTo(File(dir, "content"))) {
            content.copyRecursively(File(dir, "content"), overwrite = true)
        }
        saveMeta(meta)
        Logger.i(TAG, "Imported ${meta.kind} '${meta.title}' as ${meta.id} (${meta.pages.size} pages)")
        return meta
    }

    private fun buildEpubMeta(
        id: String,
        content: File,
        displayName: String,
        now: Long,
    ): WebDocMeta {
        val epub = EpubParser.parse(content)
        return WebDocMeta(
            id = id,
            title = epub.title ?: displayName.substringBeforeLast('.'),
            kind = WebDocKind.EPUB,
            pages = epub.pages,
            fixedLayout = epub.fixedLayout,
            sourceName = displayName,
            importedAt = now,
        )
    }

    private fun buildHtmlMeta(
        id: String,
        content: File,
        displayName: String,
        now: Long,
    ): WebDocMeta {
        val entry = findHtmlEntry(content) ?: throw IOException("No HTML file found in \"$displayName\"")
        val title = readHtmlTitle(File(content, entry)) ?: displayName.substringBeforeLast('.')
        return WebDocMeta(
            id = id,
            title = title,
            kind = WebDocKind.HTML,
            pages = listOf(WebDocPage(entry, title)),
            sourceName = displayName,
            importedAt = now,
        )
    }

    /** `index.html` closest to the root wins, otherwise the shallowest HTML file. */
    fun findHtmlEntry(root: File): String? {
        val htmlFiles =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in HTML_EXTENSIONS && !it.name.startsWith(".") }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .filterNot { it.startsWith("__MACOSX/") }
                .toList()
        if (htmlFiles.isEmpty()) return null
        val depth = { p: String -> p.count { it == '/' } }
        return htmlFiles.filter { it.substringAfterLast('/').lowercase().startsWith("index.") }.minByOrNull(depth)
            ?: htmlFiles.minWith(compareBy(depth, { it }))
    }

    private fun readHtmlTitle(file: File): String? {
        val head = readHead(file, 64 * 1024).toString(Charsets.UTF_8)
        return Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(head)
            ?.groupValues
            ?.get(1)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun isZip(file: File): Boolean {
        val magic = readHead(file, 4)
        if (magic.size < 4 || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) return false
        return runCatching { ZipFile(file).close() }.isSuccess
    }

    /** `InputStream.readNBytes` needs API 33; minSdk is 26. */
    private fun readHead(
        file: File,
        max: Int,
    ): ByteArray {
        val buf = ByteArray(max)
        var read = 0
        file.inputStream().use { input ->
            while (read < max) {
                val n = input.read(buf, read, max - read)
                if (n < 0) break
                read += n
            }
        }
        return buf.copyOf(read)
    }

    private fun copyLimited(
        input: InputStream,
        out: java.io.OutputStream,
        limit: Long = MAX_IMPORT_BYTES,
    ): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("Document is larger than ${MAX_IMPORT_BYTES / (1024 * 1024)} MB")
            out.write(buf, 0, n)
        }
        return total
    }

    private fun safeFileName(name: String): String = name.substringAfterLast('/').replace(Regex("[^\\w.\\- \\u4e00-\\u9fff]"), "_").ifBlank { "index.html" }

    private fun atomicWrite(
        target: File,
        text: String,
    ) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not write $target")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
