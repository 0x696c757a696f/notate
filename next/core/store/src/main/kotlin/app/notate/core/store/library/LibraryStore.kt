package app.notate.core.store.library

import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.PageId
import kotlinx.coroutines.flow.Flow

/** Global document index (`library.db`): documents, folders, tags, full-text search and a small key-value store. */
interface LibraryStore {
    /** Emits the matching documents now and again after every library write that changes the result. */
    fun observeDocuments(query: DocQuery): Flow<List<DocumentEntry>>
    /** Inserts or updates the document row. [DocumentEntry.tagIds] is ignored; use [setTags]. */
    suspend fun upsert(entry: DocumentEntry)
    suspend fun get(id: DocId): DocumentEntry?
    suspend fun markOpened(id: DocId, at: Long)
    suspend fun trash(ids: List<DocId>, at: Long)
    suspend fun restore(ids: List<DocId>)
    /** Removes the index rows of a document (not its files). */
    suspend fun purge(id: DocId)
    suspend fun trashedBefore(at: Long): List<DocId>

    fun observeFolders(): Flow<List<Folder>>
    suspend fun createFolder(name: String, parent: String?): Folder
    suspend fun renameFolder(id: String, name: String)
    /** Throws [IllegalArgumentException] when [parent] is the folder itself or one of its descendants. */
    suspend fun moveFolder(id: String, parent: String?)
    /** Deletes the folder; its documents and subfolders move to its parent. */
    suspend fun deleteFolder(id: String)
    suspend fun moveDocuments(ids: List<DocId>, folder: String?)

    fun observeTags(): Flow<List<Tag>>
    /** Creates a tag, or returns the existing tag with the same name. */
    suspend fun createTag(name: String, color: Int): Tag
    suspend fun deleteTag(id: String)
    suspend fun setTags(doc: DocId, tagIds: Set<String>)

    /** Replaces the index text of one page ([normalizedText] from `TextNormalizer.normalize`). */
    suspend fun indexPage(doc: DocId, page: PageId, normalizedText: String)
    suspend fun removeIndex(doc: DocId, page: PageId? = null)
    /** [normalizedQuery] is an FTS MATCH expression, normally from `TextNormalizer.toMatchQuery`. */
    suspend fun search(normalizedQuery: String, limit: Int = 200): List<SearchHit>

    suspend fun getKv(key: String): String?
    /** Stores [value]; null deletes the key. */
    suspend fun putKv(key: String, value: String?)
}

data class DocumentEntry(
    val id: DocId, val title: String, val kind: DocKind, val folderId: String?, val created: Long,
    val modified: Long, val opened: Long?, val pageCount: Int, val coverRev: Long, val pinned: Boolean,
    val trashed: Long?, val tagIds: Set<String>,
)

data class DocQuery(
    val folderId: String? = null, val scope: Scope = Scope.FOLDER, val sort: Sort = Sort.MODIFIED, val text: String? = null,
)

/** For [TAG], [DocQuery.folderId] carries the tag id. */
enum class Scope { FOLDER, ALL, RECENT, PINNED, BOOKS, PDFS, TAG, TRASH }

enum class Sort { MODIFIED, CREATED, NAME, OPENED }

data class Folder(val id: String, val parentId: String?, val name: String, val ord: Double)
data class Tag(val id: String, val name: String, val color: Int)
data class SearchHit(val doc: DocId, val page: PageId, val snippet: String)
