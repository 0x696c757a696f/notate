package app.notate.export.pkg

import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.edit.Edit
import app.notate.core.model.log.Logger
import app.notate.core.store.DocStoreFactory
import app.notate.core.store.library.DocumentEntry
import app.notate.core.store.library.LibraryStore
import app.notate.core.store.pkg.PackageException
import app.notate.core.store.pkg.PackageInfo
import app.notate.core.store.pkg.PackageReader
import app.notate.core.store.sql.SqlDriverFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Random

/** What to do when the package's document already exists in the library. */
enum class ConflictMode { REPLACE, KEEP_BOTH }

/** A package copied to the staging folder and checked; pass to [PackageImporter.import] or [PackageImporter.discard]. */
class StagedPackage internal constructor(internal val file: File, val info: PackageInfo)

/** The document is open in an editor, so its files cannot be replaced now. */
class PackageBusyException(message: String) : Exception(message)

/** Imports `.notate` packages into the library (03 §8). Any thread. */
class PackageImporter(
    private val docs: DocStoreFactory,
    private val library: () -> LibraryStore,
    private val docDir: (DocId) -> File,
    private val stagingRoot: File,
    drivers: SqlDriverFactory,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val exporter: PackageExporter,
    private val isOpen: suspend (DocId) -> Boolean,
    private val logger: Logger = Logger.NONE,
    private val random: Random = Random(),
    /** Called after a document reached the library (e.g. to render its cover). */
    private val onImported: (DocId) -> Unit = {},
) {
    private val reader = PackageReader(drivers)

    /** Copies the stream from [open] to the staging folder and checks that it is a current Notate package. */
    suspend fun stage(open: () -> InputStream?): StagedPackage = withContext(io) {
        stagingRoot.mkdirs()
        val file = File.createTempFile("import-", ".notate", stagingRoot)
        try {
            (open() ?: throw PackageException(PackageException.Reason.NOT_A_PACKAGE, "Cannot open file")).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            StagedPackage(file, reader.inspect(file))
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    /** The library row that [staged] would collide with, trashed or not. */
    suspend fun existing(staged: StagedPackage): DocumentEntry? = withContext(io) { library().get(DocId(staged.info.docId)) }

    fun discard(staged: StagedPackage) { staged.file.delete() }

    /**
     * Adds the package's document to the library and deletes the staged file. With a collision, [ConflictMode.KEEP_BOTH]
     * creates a new id and the title gets " (imported)"; [ConflictMode.REPLACE] first moves the old notebook to the Trash
     * (as a copy under a new id) and then installs the package under the old id. [applyTags] sets the package's tags.
     * Throws [PackageBusyException] when REPLACE targets a notebook that is open.
     */
    suspend fun import(staged: StagedPackage, mode: ConflictMode = ConflictMode.KEEP_BOTH, applyTags: Boolean = true): DocId =
        withContext(io) {
            try {
                val lib = library()
                val packageId = DocId(staged.info.docId)
                val old = lib.get(packageId)
                val id = when {
                    old == null -> packageId
                    mode == ConflictMode.KEEP_BOTH -> DocId.new(clock, random)
                    else -> packageId
                }
                if (old != null && mode == ConflictMode.REPLACE) {
                    if (isOpen(id)) throw PackageBusyException("Notebook is open")
                    trashCopyOf(old)
                    installReplacing(staged, id)
                } else {
                    reader.extract(staged.file, docDir(id), newDocId = id.takeIf { it != packageId })
                }
                try {
                    register(id, old, keepBoth = old != null && mode == ConflictMode.KEEP_BOTH, staged.info, applyTags)
                } catch (t: Throwable) {
                    if (old == null || mode == ConflictMode.KEEP_BOTH) { docDir(id).deleteRecursively(); runCatching { lib.purge(id) } }
                    throw t
                }
                onImported(id)
                id
            } finally {
                staged.file.delete()
            }
        }

    /** Keeps the notebook that REPLACE overwrites: a trashed copy under a fresh id. */
    private suspend fun trashCopyOf(old: DocumentEntry) {
        val lib = library()
        val copy = DocId.new(clock, random)
        val tmp = File.createTempFile("replaced-", ".notate", stagingRoot)
        try {
            exporter.exportToFile(old.id, tmp)
            reader.extract(tmp, docDir(copy), newDocId = copy)
        } catch (e: Exception) {
            // The old notebook may be damaged (restore after a failed open): keep its raw files instead.
            logger.w(TAG, "Packing ${old.id.value} for the Trash failed; keeping a raw copy", e)
            docDir(copy).deleteRecursively()
            docDir(old.id).copyRecursively(docDir(copy), overwrite = true)
        } finally {
            tmp.delete()
        }
        lib.upsert(old.copy(id = copy, opened = null, pinned = false, trashed = null))
        lib.setTags(copy, old.tagIds)
        lib.trash(listOf(copy), clock.now())
    }

    private fun installReplacing(staged: StagedPackage, id: DocId) {
        val tmp = File(stagingRoot, "replace-${java.lang.Long.toHexString(random.nextLong())}")
        try {
            reader.extract(staged.file, tmp)
            val target = docDir(id)
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!tmp.renameTo(target)) tmp.copyRecursively(target, overwrite = true)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private suspend fun register(id: DocId, old: DocumentEntry?, keepBoth: Boolean, info: PackageInfo, applyTags: Boolean) {
        val lib = library()
        val (meta, pageCount) = docs.open(docDir(id)).use { store ->
            if (keepBoth) {
                val before = store.readMeta()
                store.write(listOf(Edit.SetMeta(before, before.copy(title = before.title + IMPORTED_SUFFIX))))
            }
            store.readMeta() to store.readPages().size
        }
        val replacing = old != null && !keepBoth
        lib.upsert(
            DocumentEntry(
                id, meta.title, meta.kind, if (replacing) old!!.folderId else null, meta.createdAt, meta.modifiedAt,
                if (replacing) old!!.opened else null, pageCount, coverRev = (old?.coverRev ?: 0L) + if (replacing) 1 else 0,
                pinned = replacing && old!!.pinned, trashed = null, tagIds = emptySet(),
            ),
        )
        if (applyTags && info.tags.isNotEmpty()) {
            lib.setTags(id, info.tags.map { lib.createTag(it, 0).id }.toSet())
        } else if (replacing && old!!.tagIds.isNotEmpty()) {
            lib.setTags(id, old.tagIds)
        }
        logger.i(TAG, "Imported package '${meta.title}' as ${id.value}")
    }

    companion object {
        private const val TAG = "PackageImporter"
        const val IMPORTED_SUFFIX = " (imported)"
    }
}
