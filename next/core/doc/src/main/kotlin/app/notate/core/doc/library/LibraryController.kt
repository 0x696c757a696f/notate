package app.notate.core.doc.library

import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.PageTemplate
import app.notate.core.store.library.DocQuery
import app.notate.core.store.library.Folder
import app.notate.core.store.library.LibraryStore
import app.notate.core.store.library.Scope
import app.notate.core.store.library.Sort
import app.notate.core.store.library.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Bookshelf logic (04-ux-spec §2) without any Android types: navigation, sort, filter, selection and every library
 * action. The Android `LibraryViewModel` is a thin wrapper. Actions suspend; [state] updates from the store's flows.
 * Call from one coroutine context at a time (the view model's scope).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryController(
    scope: CoroutineScope,
    private val store: LibraryStore,
    private val docs: LibraryDocs,
    private val clock: Clock,
) {
    private data class Request(val location: LibraryLocation, val sort: Sort, val filter: String)
    private data class Selection(val ids: Set<DocId> = emptySet(), val active: Boolean = false)

    private val request = MutableStateFlow(Request(LibraryLocation.Folder(null), Sort.MODIFIED, ""))
    private val selection = MutableStateFlow(Selection())

    /** Documents tagged with the request they answer, so [state] never shows a new location with the old location's documents. */
    private val documents = request.flatMapLatest { r -> store.observeDocuments(r.location.toQuery(r.sort, r.filter)).map { r to it } }

    val state: StateFlow<LibraryUiState> = combine(
        documents, store.observeFolders(), store.observeTags(), selection,
    ) { (r, docs), folders, tags, sel ->
        val live = docs.mapTo(HashSet()) { it.id }
        LibraryUiState(
            r.location, r.sort, r.filter, docs, folders, tags, sel.ids.filterTo(LinkedHashSet()) { it in live }, sel.active, loaded = true,
        )
    }.stateIn(scope, SharingStarted.Eagerly, LibraryUiState())

    // ---- navigation, sort, filter -------------------------------------------------------------------------------

    fun open(location: LibraryLocation) {
        request.update { it.copy(location = location, filter = "") }
        clearSelection()
    }

    fun openFolder(id: String?) = open(LibraryLocation.Folder(id))

    /** Goes to the parent of the folder being browsed. */
    fun up() {
        val id = (request.value.location as? LibraryLocation.Folder)?.id ?: return
        openFolder(state.value.allFolders.firstOrNull { it.id == id }?.parentId)
    }

    private fun browsedFolder(): String? = (request.value.location as? LibraryLocation.Folder)?.id

    fun setSort(sort: Sort) = request.update { it.copy(sort = sort) }

    fun setFilter(text: String) = request.update { it.copy(filter = text) }

    // ---- selection ----------------------------------------------------------------------------------------------

    fun toggleSelect(id: DocId) = selection.update { s ->
        Selection(if (id in s.ids) s.ids - id else s.ids + id, active = true)
    }

    fun selectAll() = selection.update { Selection(state.value.documents.mapTo(LinkedHashSet()) { d -> d.id }, active = true) }

    /** Leaves multi-select mode. */
    fun clearSelection() = selection.update { Selection() }

    /** Enters multi-select mode with nothing selected. */
    fun startSelecting() = selection.update { it.copy(active = true) }

    // ---- create -------------------------------------------------------------------------------------------------

    /** Creates a notebook in the folder being browsed (top level elsewhere) with a title unique in that folder. */
    suspend fun createNotebook(title: String, template: PageTemplate): DocId {
        val folder = browsedFolder()
        val taken = store.observeDocuments(DocQuery(folder, Scope.FOLDER)).first().map { it.title }
        val entry = docs.createNotebook(uniqueName(title.trim().ifEmpty { DEFAULT_TITLE }, taken), template)
        store.upsert(entry.copy(folderId = folder))
        return entry.id
    }

    /** Creates a subfolder of [parent] (default: the folder being browsed); returns it. */
    suspend fun createFolder(name: String = DEFAULT_FOLDER, parent: String? = browsedFolder()): Folder {
        val taken = store.observeFolders().first().filter { it.parentId == parent }.map { it.name }
        return store.createFolder(uniqueName(name.trim().ifEmpty { DEFAULT_FOLDER }, taken), parent)
    }

    /** Free names for the dialogs' default text. */
    suspend fun suggestNotebookTitle(): String {
        val folder = browsedFolder()
        return uniqueName(DEFAULT_TITLE, store.observeDocuments(DocQuery(folder, Scope.FOLDER)).first().map { it.title })
    }

    // ---- edit ---------------------------------------------------------------------------------------------------

    suspend fun rename(id: DocId, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val entry = store.get(id) ?: return
        if (entry.title == clean) return
        docs.rename(id, clean)
        store.upsert(entry.copy(title = clean, modified = clock.now()))
    }

    suspend fun renameFolder(id: String, name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) store.renameFolder(id, clean)
    }

    /** Moves [ids] into [folder] (null = top level). */
    suspend fun move(ids: Collection<DocId>, folder: String?) {
        store.moveDocuments(ids.toList(), folder)
        clearSelection()
    }

    suspend fun moveFolder(id: String, parent: String?) = store.moveFolder(id, parent)

    /** Deletes a folder; its contents move to its parent. Leaves the folder first when browsing it. */
    suspend fun deleteFolder(id: String) {
        val inside = browsedFolder() == id
        val parent = store.observeFolders().first().firstOrNull { it.id == id }?.parentId
        store.deleteFolder(id)
        if (inside) openFolder(parent)
    }

    suspend fun setTags(id: DocId, tagIds: Set<String>) = store.setTags(id, tagIds)

    /** Adds [tag] to every document in [ids] (multi-select "Tags"). */
    suspend fun addTag(ids: Collection<DocId>, tag: String) {
        for (id in ids) store.get(id)?.let { store.setTags(id, it.tagIds + tag) }
    }

    suspend fun createTag(name: String, color: Int): Tag = store.createTag(name.trim(), color)

    suspend fun deleteTag(id: String) {
        store.deleteTag(id)
        if ((request.value.location as? LibraryLocation.Tag)?.id == id) openFolder(null)
    }

    suspend fun togglePin(id: DocId) {
        val e = store.get(id) ?: return
        store.upsert(e.copy(pinned = !e.pinned))
    }

    /** Copies each document next to the original with " copy" appended to its title. */
    suspend fun duplicate(ids: Collection<DocId>) {
        for (id in ids) {
            val src = store.get(id) ?: continue
            val taken = store.observeDocuments(DocQuery(src.folderId, Scope.FOLDER)).first().map { it.title }
            val copy = docs.duplicate(id, uniqueName(src.title + COPY_SUFFIX, taken))
            val now = clock.now()
            store.upsert(copy.copy(folderId = src.folderId, created = now, modified = now, opened = null, pinned = false, trashed = null))
            if (src.tagIds.isNotEmpty()) store.setTags(copy.id, src.tagIds)
        }
        clearSelection()
    }

    suspend fun markOpened(id: DocId) = store.markOpened(id, clock.now())

    // ---- trash --------------------------------------------------------------------------------------------------

    suspend fun trash(ids: Collection<DocId>) {
        store.trash(ids.toList(), clock.now())
        clearSelection()
    }

    suspend fun restore(ids: Collection<DocId>) {
        store.restore(ids.toList())
        clearSelection()
    }

    /** Deletes the documents' files and index rows for good. */
    suspend fun purge(ids: Collection<DocId>) {
        for (id in ids) {
            docs.deleteFiles(id)
            store.purge(id)
        }
        clearSelection()
    }

    suspend fun emptyTrash() {
        purge(store.observeDocuments(DocQuery(scope = Scope.TRASH)).first().map { it.id })
    }

    companion object {
        const val DEFAULT_TITLE = "Untitled Note"
        const val DEFAULT_FOLDER = "New Folder"
        const val COPY_SUFFIX = " copy"
        /** Trashed documents are deleted after this many days. */
        const val TRASH_DAYS = 30
    }
}
