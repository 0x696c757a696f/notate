package app.notate

import app.notate.core.doc.library.LibraryDocs
import app.notate.core.doc.registry.DocumentRegistry
import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.DocumentMeta
import app.notate.core.model.PageTemplate
import app.notate.core.model.edit.Edit
import app.notate.core.store.DocStoreFactory
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.store.library.DocumentEntry
import app.notate.core.store.library.LibraryStore
import app.notate.core.store.sql.SqlDriverFactory
import app.notate.platform.fs.AppPaths
import app.notate.platform.thread.DispatcherSet
import app.notate.web.sandbox.WebDocCleanup
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Random

/** File-level library operations on top of the document stores (see [LibraryDocs]). */
class LibraryDocsImpl(
    private val paths: AppPaths,
    private val documents: DocumentService,
    private val docStores: DocStoreFactory,
    private val drivers: SqlDriverFactory,
    private val registry: DocumentRegistry,
    private val library: () -> LibraryStore,
    private val dispatchers: DispatcherSet,
    private val clock: Clock,
) : LibraryDocs {
    private val random = Random()

    override suspend fun createNotebook(title: String, template: PageTemplate): DocumentEntry = documents.writeNotebook(title, template)

    /**
     * Flushes open documents, writes a consistent copy of `doc.db` with `VACUUM INTO` (safe while the document is open),
     * copies the other files and rewrites the copy's id and title.
     */
    override suspend fun duplicate(id: DocId, title: String): DocumentEntry = withContext(dispatchers.io) {
        val src = paths.docDir(id)
        val srcEntry = library().get(id) ?: error("Unknown document $id")
        registry.flushAll()
        val newId = DocId.new(clock, random)
        val dst = paths.docDir(newId)
        try {
            dst.mkdirs()
            val db = File(dst, SqliteDocStoreFactory.DB_NAME)
            drivers.open(File(src, SqliteDocStoreFactory.DB_NAME), readOnly = false).use { it.execute("VACUUM INTO ?", db.path) }
            src.listFiles()?.filter { it.name != SqliteDocStoreFactory.DB_NAME && !it.name.startsWith("${SqliteDocStoreFactory.DB_NAME}-") }
                ?.forEach { it.copyRecursively(File(dst, it.name), overwrite = true) }
            val now = clock.now()
            val pageCount = docStores.open(dst).use { store ->
                val before: DocumentMeta = store.readMeta()
                store.write(listOf(Edit.SetMeta(before, before.copy(id = newId, title = title, createdAt = now, modifiedAt = now))))
                store.readPages().size
            }
            srcEntry.copy(id = newId, title = title, created = now, modified = now, opened = null, pageCount = pageCount, pinned = false, trashed = null)
        } catch (t: Throwable) {
            dst.deleteRecursively()
            throw t
        }
    }

    /** Edits the title through the registry so an open editor and the on-disk document stay in step. */
    override suspend fun rename(id: DocId, title: String) {
        val handle = registry.acquire(id)
        try {
            withContext(dispatchers.main) {
                val before = handle.editor.meta.value
                handle.editor.apply(Edit.SetMeta(before, before.copy(title = title)), undoable = false)
            }
            handle.save.flush()
        } finally {
            registry.release(handle)
        }
    }

    override suspend fun deleteFiles(id: DocId) {
        withContext(dispatchers.io) { paths.docDir(id).deleteRecursively() }
        // WebView storage (localStorage, IndexedDB) of book documents; WebStorage must be used on the main thread.
        withContext(dispatchers.main) { WebDocCleanup.clear(id) }
    }
}
