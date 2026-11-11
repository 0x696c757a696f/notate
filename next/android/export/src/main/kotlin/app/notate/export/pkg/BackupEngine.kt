package app.notate.export.pkg

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.log.Logger
import app.notate.core.store.library.DocQuery
import app.notate.core.store.library.LibraryStore
import app.notate.core.store.library.Scope
import app.notate.core.store.pkg.PackageInfo
import app.notate.core.store.pkg.PackageReader
import app.notate.core.store.sql.SqlDriverFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Outcome of one backup run. [firstError] is the message of the first failure, for "Last backup failed: …". */
data class BackupResult(val written: Int, val unchanged: Int, val failed: Int, val firstError: String? = null)

/** One `.notate` file found in a backup folder. */
class BackupItem(val file: DocumentFile, val info: PackageInfo)

/**
 * Backs the library up as one `.notate` package per document under `<chosen folder>/Notate Backup/` (04 §9) and restores
 * from such a folder. Backups are a safety net: files of documents that were deleted from the library are never removed.
 * Any thread.
 */
class BackupEngine(
    private val resolver: ContentResolver,
    private val exporter: PackageExporter,
    private val importer: PackageImporter,
    private val library: () -> LibraryStore,
    drivers: SqlDriverFactory,
    private val tempDir: File,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val logger: Logger = Logger.NONE,
) {
    private val reader = PackageReader(drivers)

    /**
     * Writes every non-trashed document modified since its last backup (or whose file is missing) and refreshes
     * `library.json`. [onProgress] is called before each document with (done, total) counting all documents.
     */
    suspend fun run(root: DocumentFile, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): BackupResult = withContext(io) {
        val lib = library()
        val dir = backupDir(root, create = true) ?: error("Backup folder is not writable")
        val docs = lib.observeDocuments(DocQuery(scope = Scope.ALL)).first().filter { it.trashed == null }
        val existing = dir.listFiles().filter { it.isFile }.associateBy { it.name }
        var written = 0; var unchanged = 0; var failed = 0
        var firstError: String? = null
        docs.forEachIndexed { index, d ->
            onProgress(index, docs.size)
            val name = PackageExporter.backupName(d.title, d.id)
            val key = KV_PREFIX + d.id.value
            val current = existing[name]
            if (current != null && lib.getKv(key) == d.modified.toString()) { unchanged++; return@forEachIndexed }
            try {
                writePackage(dir, d.id, name, current)
                // A renamed notebook leaves its old file behind; drop the stale name so there is one file per notebook.
                existing.values.filter { it.name != name && it.name?.endsWith(PackageExporter.backupSuffix(d.id)) == true }.forEach { it.delete() }
                lib.putKv(key, d.modified.toString())
                written++
            } catch (e: Exception) {
                logger.e(TAG, "Backing up ${d.id.value} failed", e)
                failed++
                if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
            }
        }
        onProgress(docs.size, docs.size)
        try {
            writeLayout(dir, lib, docs)
        } catch (e: Exception) {
            logger.e(TAG, "Writing library.json failed", e)
            failed++
            if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
        }
        BackupResult(written, unchanged, failed, firstError)
    }

    private suspend fun writePackage(dir: DocumentFile, id: DocId, name: String, current: DocumentFile?) {
        tempDir.mkdirs()
        val tmp = File.createTempFile("backup-", ".notate", tempDir)
        try {
            exporter.exportToFile(id, tmp)
            val target = current ?: dir.createFile(PackageExporter.MIME, name) ?: error("Cannot create $name")
            (resolver.openOutputStream(target.uri, "wt") ?: error("Cannot write $name")).use { out -> tmp.inputStream().use { it.copyTo(out) } }
        } finally {
            tmp.delete()
        }
    }

    private suspend fun writeLayout(dir: DocumentFile, lib: LibraryStore, docs: List<app.notate.core.store.library.DocumentEntry>) {
        val tags = lib.observeTags().first().associateBy { it.id }
        val layout = BackupLayout(
            folders = lib.observeFolders().first().map { BackupLayout.Folder(it.id, it.parentId, it.name) },
            docs = docs.map { d ->
                BackupLayout.Doc(d.id.value, d.title, d.folderId, d.tagIds.mapNotNull { tags[it]?.name }.sorted(), d.pinned)
            },
        )
        val target = dir.findFile(LAYOUT_FILE) ?: dir.createFile("application/json", LAYOUT_FILE) ?: error("Cannot create $LAYOUT_FILE")
        (resolver.openOutputStream(target.uri, "wt") ?: error("Cannot write $LAYOUT_FILE")).use { it.write(layout.toJson().toByteArray(Charsets.UTF_8)) }
    }

    /** Lists the packages in [root] (or in its `Notate Backup` subfolder), newest first. Unreadable files are skipped. */
    suspend fun list(root: DocumentFile): List<BackupItem> = withContext(io) {
        val dir = backupDir(root, create = false) ?: root
        dir.listFiles().filter { it.isFile && it.name?.endsWith(".${PackageExporter.EXTENSION}") == true }.mapNotNull { f ->
            val info = try { resolver.openInputStream(f.uri)?.use { reader.peek(it) } } catch (e: Exception) { null }
            info?.let { BackupItem(f, it) }
        }.sortedByDescending { it.info.modifiedAt }
    }

    /** The backup of document [id] in [root], if one exists. */
    suspend fun find(root: DocumentFile, id: DocId): BackupItem? = list(root).firstOrNull { it.info.docId == id.value }

    /** The saved folder/tag layout in [root], or null when there is none. */
    suspend fun readLayout(root: DocumentFile): BackupLayout? = withContext(io) {
        val dir = backupDir(root, create = false) ?: root
        val f = dir.findFile(LAYOUT_FILE) ?: return@withContext null
        resolver.openInputStream(f.uri)?.use { BackupLayout.parse(it.readBytes().toString(Charsets.UTF_8)) }
    }

    /**
     * Imports [items] (conflicts default to Keep both; [mode] overrides). With [layout] the folders are recreated
     * (matching existing folders by name and parent) and documents are filed, tagged and pinned as in the backup.
     * Returns the ids that were restored; a failing item is logged and skipped.
     */
    suspend fun restore(
        items: List<BackupItem>,
        layout: BackupLayout?,
        mode: ConflictMode = ConflictMode.KEEP_BOTH,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<DocId> = withContext(io) {
        val lib = library()
        val restored = ArrayList<Pair<String, DocId>>()
        items.forEachIndexed { i, item ->
            onProgress(i, items.size)
            try {
                val staged = importer.stage { resolver.openInputStream(item.file.uri) }
                restored += item.info.docId to importer.import(staged, mode, applyTags = layout != null)
            } catch (e: Exception) {
                logger.e(TAG, "Restoring ${item.file.name} failed", e)
            }
        }
        onProgress(items.size, items.size)
        if (layout != null) applyLayout(lib, layout, restored)
        restored.map { it.second }
    }

    private suspend fun applyLayout(lib: LibraryStore, layout: BackupLayout, restored: List<Pair<String, DocId>>) {
        val current = lib.observeFolders().first()
        val map = HashMap<String, String>()   // backup folder id -> library folder id
        for (f in layout.foldersParentFirst()) {
            val parent = f.parentId?.let(map::get)
            val same = current.firstOrNull { it.name == f.name && it.parentId == parent }
            map[f.id] = same?.id ?: lib.createFolder(f.name, parent).id
        }
        val byId = layout.docs.associateBy { it.id }
        for ((oldId, newId) in restored) {
            val d = byId[oldId] ?: continue
            d.folderId?.let(map::get)?.let { lib.moveDocuments(listOf(newId), it) }
            if (d.pinned) lib.get(newId)?.let { if (!it.pinned) lib.upsert(it.copy(pinned = true)) }
        }
    }

    private fun backupDir(root: DocumentFile, create: Boolean): DocumentFile? =
        if (root.name == DIR_NAME) root else root.findFile(DIR_NAME)?.takeIf { it.isDirectory } ?: if (create) root.createDirectory(DIR_NAME) else null

    companion object {
        private const val TAG = "BackupEngine"
        const val DIR_NAME = "Notate Backup"
        const val LAYOUT_FILE = "library.json"
        const val KV_PREFIX = "backup:"
    }
}
