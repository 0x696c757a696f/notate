package app.notate.core.doc.library

import app.notate.core.model.DocId
import app.notate.core.store.library.DocQuery
import app.notate.core.store.library.DocumentEntry
import app.notate.core.store.library.Folder
import app.notate.core.store.library.Scope
import app.notate.core.store.library.Sort
import app.notate.core.store.library.Tag

/** What the bookshelf is showing. [Folder] with a null id is the top level ("All"). */
sealed interface LibraryLocation {
    data class Folder(val id: String?) : LibraryLocation
    data object Recent : LibraryLocation
    data object Pinned : LibraryLocation
    data object Books : LibraryLocation
    data object Pdfs : LibraryLocation
    data object Trash : LibraryLocation
    data class Tag(val id: String) : LibraryLocation
}

/** Everything the bookshelf screen draws. */
data class LibraryUiState(
    val location: LibraryLocation = LibraryLocation.Folder(null),
    val sort: Sort = Sort.MODIFIED,
    /** Title filter typed in the search box; a non-empty filter searches the whole library. */
    val filter: String = "",
    /** Documents of the current location, filtered and sorted. */
    val documents: List<DocumentEntry> = emptyList(),
    val allFolders: List<Folder> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val selection: Set<DocId> = emptySet(),
    /** True while multi-select mode is on (even with nothing selected yet). */
    val selecting: Boolean = false,
    /** False until the first documents query has answered. */
    val loaded: Boolean = false,
) {
    private val folderId: String? get() = (location as? LibraryLocation.Folder)?.id

    /** Subfolders shown above the documents (only when browsing folders without a filter). */
    val folders: List<Folder>
        get() = if (location is LibraryLocation.Folder && filter.isBlank()) allFolders.filter { it.parentId == folderId } else emptyList()

    /** The folder being browsed, null at the top level or outside folder browsing. */
    val currentFolder: Folder? get() = folderId?.let { id -> allFolders.firstOrNull { it.id == id } }

    /** Folders from the top level down to the current one. */
    val breadcrumb: List<Folder>
        get() {
            val out = ArrayList<Folder>()
            var f = currentFolder
            while (f != null && out.size < 64) { out.add(0, f); f = f.parentId?.let { id -> allFolders.firstOrNull { it.id == id } } }
            return out
        }

    val canGoUp: Boolean get() = location is LibraryLocation.Folder && folderId != null
}

internal fun LibraryLocation.toQuery(sort: Sort, filter: String): DocQuery {
    val text = filter.trim().ifEmpty { null }
    return when (this) {
        is LibraryLocation.Folder ->
            if (text != null) DocQuery(scope = Scope.ALL, sort = sort, text = text) else DocQuery(id, Scope.FOLDER, sort)
        LibraryLocation.Recent -> DocQuery(scope = Scope.RECENT, sort = sort, text = text)
        LibraryLocation.Pinned -> DocQuery(scope = Scope.PINNED, sort = sort, text = text)
        LibraryLocation.Books -> DocQuery(scope = Scope.BOOKS, sort = sort, text = text)
        LibraryLocation.Pdfs -> DocQuery(scope = Scope.PDFS, sort = sort, text = text)
        LibraryLocation.Trash -> DocQuery(scope = Scope.TRASH, sort = sort, text = text)
        is LibraryLocation.Tag -> DocQuery(id, Scope.TAG, sort, text)
    }
}
