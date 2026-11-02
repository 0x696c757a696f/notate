package app.notate.export.legacy

import app.notate.core.legacy.DocIdSource
import app.notate.core.legacy.LegacyFormatException
import app.notate.core.legacy.LegacyLinkFixer
import app.notate.core.legacy.LegacyMapper
import app.notate.core.legacy.LegacyNotateReader
import app.notate.core.legacy.LegacyWebMapper
import app.notate.core.legacy.LegacyWebReader
import app.notate.core.legacy.MappedDocument
import app.notate.core.model.Background
import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.Item
import app.notate.core.model.edit.Edit
import app.notate.core.model.log.Logger
import app.notate.core.store.AssetKind
import app.notate.core.store.DocStore
import app.notate.core.store.DocStoreFactory
import app.notate.core.store.OutlineEntry
import app.notate.core.store.library.DocumentEntry
import app.notate.core.store.library.Folder
import app.notate.core.store.library.LibraryStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Random
import java.util.zip.ZipFile

/**
 * Copies old notes (and, for the in-app migration, old web documents) into the new library. The originals are never changed.
 * Each note is read, mapped, written as a document with its images, put into the library under the same folder path and
 * tagged; `kv["legacy:<uuid>"]` remembers it so a second run skips it. A note that fails is reported and leaves nothing behind.
 * Safe to call from any thread; cancellation stops after the current note and removes its partial files.
 */
class LegacyImporter(
    private val docs: DocStoreFactory,
    libraryProvider: () -> LibraryStore,
    private val docDir: (DocId) -> File,
    private val stagingRoot: File,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val logger: Logger = Logger.NONE,
    private val random: Random = Random(),
    /** Called after each document is in the library (e.g. to render its cover). */
    private val onImported: (DocId) -> Unit = {},
) {
    private val library: LibraryStore by lazy(libraryProvider)

    /** The document an earlier import created from the note with [uuid], if it still exists. */
    suspend fun existing(uuid: String?): DocId? {
        if (uuid.isNullOrBlank()) return null
        val id = library.getKv(KV_PREFIX + uuid)?.let { DocId(it) } ?: return null
        return id.takeIf { library.get(it) != null }
    }

    /**
     * Imports [notes] and [webs]. [onProgress] is called before each item with the number finished so far.
     * Links between notes of this batch (and earlier imports) are resolved to the new documents.
     */
    suspend fun importAll(
        notes: List<LegacyEntry>,
        webs: List<LegacyWebEntry> = emptyList(),
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
        /** Polled between items; once true the import stops and the report says [ImportReport.cancelled]. */
        stopRequested: () -> Boolean = { false },
    ): ImportReport = withContext(io) {
        val total = notes.size + webs.size
        val failures = ArrayList<ImportFailure>()
        var imported = 0; var already = 0; var warnings = 0; var done = 0
        // Ids are fixed up front so a link may point at a note that is imported later in the batch.
        val known = HashMap<String, DocId>()
        val planned = HashMap<LegacyEntry, DocId>()
        for (n in notes) {
            val prior = existing(n.uuid)
            if (prior != null) known[n.uuid!!] = prior
            else DocId.new(clock, random).also { id -> planned[n] = id; n.uuid?.takeIf { it.isNotBlank() }?.let { known[it] = id } }
        }
        val folderIds = HashMap<List<String>, String>()
        val folders = ArrayList<Folder>(library.observeFolders().first())
        var cancelled = false
        run {
            for (n in notes) {
                currentCoroutineContext().ensureActive()
                if (stopRequested()) { cancelled = true; return@run }
                onProgress(done, total, n.title)
                val id = planned[n]
                if (id == null) { already++; done++; continue }
                try {
                    warnings += importNote(n, id, known, folderIds, folders)
                    imported++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "Could not import ${n.title}", e)
                    failures.add(ImportFailure((n.folders + n.title).joinToString("/"), reasonOf(e)))
                }
                done++
            }
            for (w in webs) {
                currentCoroutineContext().ensureActive()
                if (stopRequested()) { cancelled = true; return@run }
                onProgress(done, total, w.dir.name)
                try {
                    val r = importWeb(w)
                    if (r < 0) already++ else { imported++; warnings += r }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "Could not import web document ${w.dir.name}", e)
                    failures.add(ImportFailure(w.dir.name, reasonOf(e)))
                }
                done++
            }
        }
        ImportReport(total, imported, already, failures, warnings, cancelled)
    }

    private suspend fun importNote(
        n: LegacyEntry, id: DocId, known: Map<String, DocId>, folderIds: MutableMap<List<String>, String>, folders: MutableList<Folder>,
    ): Int {
        val dir = docDir(id)
        var ok = false
        val staged = File(stagingRoot, "legacy-${java.lang.Long.toHexString(random.nextLong())}").apply { mkdirs() }
        try {
            val mapped = readAndWrite(n, id, known, staged, dir)
            val folderId = folderFor(n.folders, folderIds, folders)
            library.upsert(
                DocumentEntry(
                    id, mapped.meta.title, DocKind.NOTEBOOK, folderId, mapped.meta.createdAt, mapped.meta.modifiedAt, null,
                    mapped.pages.size, coverRev = 0, pinned = false, trashed = null, tagIds = emptySet(),
                ),
            )
            if (mapped.tags.isNotEmpty()) {
                library.setTags(id, mapped.tags.map { t -> library.createTag(t, mapped.tagColors[t] ?: 0).id }.toSet())
            }
            mapped.legacyUuid?.takeIf { it.isNotBlank() }?.let { library.putKv(KV_PREFIX + it, id.value) }
            ok = true
            logger.i(TAG, "Imported '${mapped.meta.title}' as ${id.value} (${mapped.pages.size} pages, ${mapped.warnings.size} warnings)")
            onImported(id)
            return mapped.warnings.size
        } finally {
            staged.deleteRecursively()
            if (!ok) { dir.deleteRecursively(); runCatching { library.purge(id) } }
        }
    }

    /** Reads the container, maps it and writes the document (with images) to [dir]; the library is not touched. */
    private suspend fun readAndWrite(n: LegacyEntry, id: DocId, known: Map<String, DocId>, staged: File, dir: File): MappedDocument {
        val directory = n.directory
        val zip: ZipFile?
        val canvas = if (directory != null) {
            zip = null
            LegacyNotateReader.read(directory)
        } else {
            val zipFile = File(staged, "note.zip")
            (n.open() ?: throw LegacyFormatException("Cannot open file")).use { input -> zipFile.outputStream().use { input.copyTo(it) } }
            zip = ZipFile(zipFile)
            LegacyNotateReader.read(zip)
        }
        try {
            val stamp = n.modified.takeIf { it > 0 } ?: clock.now()
            val raw = LegacyMapper.toDocument(canvas, DocIdSource { id }, clock, title = n.title.ifBlank { "Imported note" }, createdAt = stamp, modifiedAt = stamp)
            val mapped = LegacyLinkFixer.fix(listOf(raw), known).single()
            val assetFiles = HashMap<String, File>()
            for (ref in mapped.assets) {
                val f = File(staged, "asset-${ref.assetId.value}.${ref.extension}")
                (canvas.openAsset(ref.uri) ?: continue).use { input -> f.outputStream().use { input.copyTo(it) } }
                assetFiles[ref.uri] = f
            }
            writeDocument(dir, mapped) { store ->
                for (ref in mapped.assets) {
                    val f = assetFiles[ref.uri] ?: continue
                    store.putAsset(AssetKind.IMAGE, mimeOf(ref.extension), f, ref.extension)
                }
            }
            return mapped
        } finally {
            zip?.close()
        }
    }

    /** Returns the warning count, or -1 when this web document was imported before. */
    private suspend fun importWeb(w: LegacyWebEntry): Int {
        val web = LegacyWebReader.read(w.dir)
        existing(web.meta.id)?.let { return -1 }
        if (!web.contentDir.isDirectory) throw LegacyFormatException("Missing content folder")
        val id = DocId.new(clock, random)
        val dir = docDir(id)
        var ok = false
        try {
            val mapped = LegacyWebMapper.toDocument(web, DocIdSource { id }, clock)
            writeDocument(dir, mapped) { store ->
                store.putAsset(AssetKind.WEB, "application/x-notate-web", web.contentDir, "")
                var next = 1L
                val byHref = mapped.pages.associateBy { (it.background as Background.Web).href }
                val titles = web.meta.pages.mapIndexedNotNull { i, p ->
                    byHref[p.href]?.let { OutlineEntry(next++, null, i, p.title.ifBlank { p.href }, it.id, null, null) }
                }
                if (titles.isNotEmpty()) store.writeOutline(titles)
            }
            library.upsert(
                DocumentEntry(
                    id, mapped.meta.title, DocKind.WEB, null, mapped.meta.createdAt, mapped.meta.modifiedAt, null,
                    mapped.pages.size, coverRev = 0, pinned = false, trashed = null, tagIds = emptySet(),
                ),
            )
            library.putKv(KV_PREFIX + web.meta.id, id.value)
            ok = true
            onImported(id)
            return mapped.warnings.size
        } finally {
            if (!ok) { dir.deleteRecursively(); runCatching { library.purge(id) } }
        }
    }

    private suspend fun writeDocument(dir: File, mapped: MappedDocument, extra: suspend (DocStore) -> Unit) {
        val store = docs.create(dir, mapped.meta, mapped.pages)
        try {
            extra(store)
            for (page in mapped.pages) {
                val items: List<Item> = mapped.items[page.id].orEmpty()
                items.chunked(CHUNK).forEach { store.write(listOf(Edit.Items(page.id, emptyList(), it))) }
            }
        } finally {
            store.close()
        }
    }

    private suspend fun folderFor(path: List<String>, cache: MutableMap<List<String>, String>, folders: MutableList<Folder>): String? {
        var parent: String? = null
        for (i in path.indices) {
            val key = path.subList(0, i + 1).toList()
            val p = parent
            val id = cache[key] ?: (folders.firstOrNull { it.parentId == p && it.name == path[i] }
                ?: library.createFolder(path[i], p).also { folders.add(it) }).id
            cache[key] = id
            parent = id
        }
        return parent
    }

    private fun reasonOf(e: Exception): String = when (e) {
        is LegacyFormatException -> e.message ?: "Unreadable note"
        is java.util.zip.ZipException -> "Not a valid note file"
        is java.io.IOException -> "Could not read the file"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun mimeOf(ext: String) = when (ext.lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "LegacyImporter"
        private const val CHUNK = 2000
        const val KV_PREFIX = "legacy:"
    }
}
