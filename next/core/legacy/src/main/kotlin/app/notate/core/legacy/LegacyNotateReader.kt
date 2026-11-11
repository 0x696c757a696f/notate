@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.notate.core.legacy

import app.notate.core.model.AssetId
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Reads an old `.notate` container (a ZIP of the old session directory):
 * `manifest.bin` (ProtoBuf [LegacyCanvasData]), `r_<x>_<y>.bin` (ProtoBuf [LegacyRegionProto], x/y may be negative),
 * optional `index.bin`/`thumbnails/` (ignored) and `images/<uuid>.<ext>` (image files).
 * Image URIs inside regions are container-relative (`images/…`), absolute paths, `file:` URIs or `content:` URIs.
 * Any thread; not thread-safe per instance.
 */
object LegacyNotateReader {
    private val proto = ProtoBuf
    private val REGION = Regex("^r_(-?\\d+)_(-?\\d+)\\.bin$")
    const val MANIFEST = "manifest.bin"

    /** Reads from an open [zip]; the caller closes it after it has finished using [LegacyCanvas.openAsset]. */
    fun read(zip: ZipFile): LegacyCanvas = read(ZipSource(zip))

    /** Reads from an already unpacked session directory. */
    fun read(dir: File): LegacyCanvas = read(DirSource(dir))

    /** Decodes just a `manifest.bin` (for the uuid and title-less metadata) without reading the rest of the container. */
    fun peek(manifest: ByteArray): LegacyCanvasData = try {
        proto.decodeFromByteArray(LegacyCanvasData.serializer(), manifest)
    } catch (e: Exception) {
        throw LegacyFormatException("Unreadable $MANIFEST: ${e.message}", e)
    }

    private fun read(src: Source): LegacyCanvas {
        val warnings = ArrayList<String>()
        val manifest = src.read(MANIFEST) ?: throw LegacyFormatException("Missing $MANIFEST")
        val data = try {
            proto.decodeFromByteArray(LegacyCanvasData.serializer(), manifest)
        } catch (e: Exception) {
            throw LegacyFormatException("Unreadable $MANIFEST: ${e.message}", e)
        }
        val regions = ArrayList<LegacyRegionProto>()
        for (name in src.names().filter { REGION.matches(it) }.sorted()) {
            try {
                val bytes = src.read(name) ?: continue
                regions.add(proto.decodeFromByteArray(LegacyRegionProto.serializer(), bytes))
            } catch (e: Exception) {
                warnings.add("Skipped unreadable region $name: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        regions.sortWith(compareBy({ it.idY }, { it.idX }))
        val assets = LinkedHashMap<String, LegacyAssetRef>()
        val openers = HashMap<String, () -> InputStream?>()
        for (uri in regions.flatMap { r -> r.images.map { it.uri } }.toSortedSet()) {
            val open = resolve(src, uri)
            if (open == null) {
                warnings.add("Image not found: $uri")
                continue
            }
            try {
                val (id, size) = open().use { hash(it ?: throw IOException("cannot open")) }
                assets[uri] = LegacyAssetRef(uri, id, extensionOf(uri), size)
                openers[uri] = open
            } catch (e: Exception) {
                warnings.add("Image unreadable: $uri (${e.message})")
            }
        }
        return LegacyCanvas(data, regions, assets, warnings) { openers[it]?.invoke() }
    }

    /** Finds where [uri] lives: container entry, local file, or null (e.g. `content:` URIs the JVM cannot open). */
    private fun resolve(src: Source, uri: String): (() -> InputStream?)? {
        if (uri.isBlank() || uri.startsWith("content:")) return null
        val path = if (uri.startsWith("file:")) uri.removePrefix("file://").removePrefix("file:") else uri
        val relative = !path.startsWith("/")
        if (relative) {
            val norm = path.replace('\\', '/')
            if (src.has(norm)) return { src.open(norm) }
        }
        // Absolute path written by the old app (same device) or the moved container's images/ entry with the same file name.
        val f = File(path)
        if (!relative && f.isFile) return { FileInputStream(f) }
        val inContainer = "images/" + path.substringAfterLast('/')
        if (src.has(inContainer)) return { src.open(inContainer) }
        return null
    }

    /** AssetId (first 32 hex of SHA-256) and byte size of [input]. */
    fun hash(input: InputStream): Pair<AssetId, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            total += n
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return AssetId(hex.take(32)) to total
    }

    private fun extensionOf(uri: String): String {
        val name = uri.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "")
        return if (ext.isEmpty() || ext.length > 5) "img" else ext.lowercase()
    }

    private interface Source {
        fun names(): List<String>
        fun has(name: String): Boolean
        fun open(name: String): InputStream?
        fun read(name: String): ByteArray? = open(name)?.use { it.readBytes() }
    }

    private class ZipSource(private val zip: ZipFile) : Source {
        override fun names() = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name.replace('\\', '/') }.toList()
        override fun has(name: String) = zip.getEntry(name) != null && !zip.getEntry(name).isDirectory
        override fun open(name: String): InputStream? = zip.getEntry(name)?.let { zip.getInputStream(it) }
    }

    private class DirSource(private val dir: File) : Source {
        override fun names() = dir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
        override fun has(name: String) = File(dir, name).isFile
        override fun open(name: String): InputStream? = File(dir, name).takeIf { it.isFile }?.let { FileInputStream(it) }
    }
}
