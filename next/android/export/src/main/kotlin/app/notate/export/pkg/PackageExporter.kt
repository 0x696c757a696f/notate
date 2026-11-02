package app.notate.export.pkg

import app.notate.core.model.DocId
import app.notate.core.store.DocStoreFactory
import app.notate.core.store.library.LibraryStore
import app.notate.core.store.pkg.PackageWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Writes library documents as `.notate` packages (03 §8). Works on open documents: pending edits are flushed first and
 * the snapshot is taken with `VACUUM INTO`, so the editor does not have to be closed. Any thread.
 */
class PackageExporter(
    private val docs: DocStoreFactory,
    private val library: () -> LibraryStore,
    private val docDir: (DocId) -> File,
    /** Flushes the save queues of open documents (`DocumentRegistry.flushAll`). */
    private val flush: suspend () -> Unit,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Writes the package of [id] to [out] (not closed). */
    suspend fun export(id: DocId, out: OutputStream) = withContext(io) {
        flush()
        val tagNames = tagNames(id)
        // Same path as LibraryDocs.duplicate: VACUUM INTO on a normally opened connection.
        docs.open(docDir(id), readOnly = false).use { store ->
            PackageWriter.write(store, store.readMeta(), tagNames, out, now())
        }
    }

    /** Writes the package of [id] to [file] through a temporary file, so a failure never leaves a half-written [file]. */
    suspend fun exportToFile(id: DocId, file: File) = withContext(io) {
        val tmp = File(file.parentFile, file.name + ".part")
        try {
            file.parentFile?.mkdirs()
            tmp.outputStream().use { export(id, it) }
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true) }
        } finally {
            tmp.delete()
        }
    }

    private suspend fun tagNames(id: DocId): List<String> {
        val lib = library()
        val ids = lib.get(id)?.tagIds ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        return lib.observeTags().first().filter { it.id in ids }.map { it.name }.sorted()
    }

    companion object {
        const val EXTENSION = "notate"
        const val MIME = "application/octet-stream"

        /** A file-system safe name for [title]: `<title>.notate`. */
        fun fileName(title: String): String = sanitize(title) + ".$EXTENSION"

        /** `<title> [<first 8 chars of the id>].notate`, the name used inside a backup folder. */
        fun backupName(title: String, id: DocId): String = "${sanitize(title)} [${id.value.take(ID_CHARS)}].$EXTENSION"

        /** Suffix identifying the document in a backup file name. */
        fun backupSuffix(id: DocId): String = " [${id.value.take(ID_CHARS)}].$EXTENSION"

        private const val ID_CHARS = 8
        private const val MAX_TITLE = 80
        private val BAD = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

        private fun sanitize(title: String): String =
            title.replace(BAD, "_").trim().trim('.').take(MAX_TITLE).trim().ifEmpty { "Untitled" }
    }
}
