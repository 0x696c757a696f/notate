package app.notate

import app.notate.core.model.Background
import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.DocumentMeta
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PageTemplate
import app.notate.core.store.DocStoreFactory
import app.notate.core.store.library.DocQuery
import app.notate.core.store.library.DocumentEntry
import app.notate.core.store.library.LibraryStore
import app.notate.core.store.library.Scope
import app.notate.core.store.library.Sort
import app.notate.platform.fs.AppPaths
import app.notate.platform.thread.DispatcherSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.Random

/** App-level use cases around documents and the library index. Callable from the main thread; the work runs on IO. */
class DocumentService(
    private val paths: AppPaths,
    private val docStores: DocStoreFactory,
    private val library: () -> LibraryStore,
    private val dispatchers: DispatcherSet,
    private val clock: Clock,
) {
    private val random = Random()

    /** Creates a notebook of [pageCount] pages of [template], registers it in the library and returns its id. */
    suspend fun createNotebook(title: String, template: PageTemplate = PageTemplate.DEFAULT, pageCount: Int = 1): DocId =
        withContext(dispatchers.io) {
            require(pageCount >= 1) { "A notebook needs at least one page" }
            val id = DocId.new(clock, random)
            val now = clock.now()
            val meta = DocumentMeta(id, title, DocKind.NOTEBOOK, createdAt = now, modifiedAt = now, newPageTemplate = template)
            val pages = (1..pageCount).map { n -> pageOf(n, template, now) }
            docStores.create(paths.docDir(id), meta, pages).close()
            library().upsert(
                DocumentEntry(
                    id = id, title = title, kind = DocKind.NOTEBOOK, folderId = null, created = now, modified = now, opened = null,
                    pageCount = pageCount, coverRev = 0, pinned = false, trashed = null, tagIds = emptySet(),
                ),
            )
            id
        }

    /** All documents that are not in the trash, most recently modified first. */
    fun listDocuments(): Flow<List<DocumentEntry>> =
        flow { emitAll(library().observeDocuments(DocQuery(scope = Scope.ALL, sort = Sort.MODIFIED))) }.flowOn(dispatchers.io)

    /** Bumps the modification time of [id] in the library (called when an editor closes it). */
    suspend fun touch(id: DocId) = withContext(dispatchers.io) {
        val store = library()
        val entry = store.get(id) ?: return@withContext
        store.upsert(entry.copy(modified = clock.now()))
    }

    private fun pageOf(n: Int, template: PageTemplate, now: Long): PageInfo {
        val size = template.size
        val w = size.widthPt
        val h = size.heightPt
        val (width, height) = if (template.landscape && h != null) h to w else w to h
        val background: Background = template.background
        return PageInfo(PageId(n.toLong()), ord = n.toDouble(), width = width, height = height, background = background, inkRev = 0, modifiedAt = now)
    }
}
