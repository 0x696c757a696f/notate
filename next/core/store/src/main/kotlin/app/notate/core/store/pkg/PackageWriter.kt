package app.notate.core.store.pkg

import app.notate.core.model.DocumentMeta
import app.notate.core.store.DocStore
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a document as a `.notate` ZIP package. Call from a background thread. */
object PackageWriter {
    private val STORED_EXT = setOf("pdf", "png", "jpg", "jpeg", "webp", "gif", "zip", "mp3", "m4a", "aac", "ogg", "opus", "woff", "woff2")

    /**
     * Writes `manifest.json`, a `VACUUM`ed `doc.db` and the files the asset table refers to (nothing else) to [out].
     * [out] is flushed but not closed. [now] is stored as `exportedAt`.
     */
    suspend fun write(store: DocStore, meta: DocumentMeta, tags: List<String>, out: OutputStream, now: Long = System.currentTimeMillis()) {
        val tmp = Files.createTempDirectory("notate-export").toFile()
        try {
            val snap = File(tmp, PackageManifest.DB)
            store.snapshot(snap)
            val paths = store.assetPaths()
            val manifest = PackageManifest(
                docId = meta.id.value, title = meta.title, tags = tags, exportedAt = now, modifiedAt = meta.modifiedAt,
                kind = meta.kind.name,
            )
            val buffered = out.buffered()
            val zip = ZipOutputStream(buffered)
            zip.putNextEntry(ZipEntry(PackageManifest.MANIFEST))
            zip.write(PackageManifest.json.encodeToString(PackageManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            addFile(zip, PackageManifest.DB, snap, compress = true)
            for (rel in paths.distinct().sorted()) {
                val f = File(store.dir, rel)
                if (f.isDirectory) {
                    f.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
                        addFile(zip, it.relativeTo(store.dir).invariantSeparatorsPath, it, compress = true)
                    }
                } else if (f.isFile) {
                    addFile(zip, rel.replace('\\', '/'), f, compress = f.extension.lowercase() !in STORED_EXT)
                }
            }
            zip.finish()
            buffered.flush()
            out.flush()
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File, compress: Boolean) {
        zip.setLevel(if (compress) Deflater.DEFAULT_COMPRESSION else Deflater.NO_COMPRESSION)
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
