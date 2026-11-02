package app.notate.core.store.library

import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.PageId
import app.notate.core.store.schema.LibrarySchema
import app.notate.core.store.schema.Migrations
import app.notate.core.store.sql.SqlDriver
import app.notate.core.store.sql.SqlPragmas
import app.notate.core.store.sql.SqlRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Random

/** [LibraryStore] over `library.db`. All SQL runs on [io] (a single-threaded dispatcher owned by the caller). */
class SqliteLibraryStore private constructor(
    private val driver: SqlDriver,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val random: Random,
) : LibraryStore {
    private val writes = MutableStateFlow(0L)

    private suspend fun <T> write(block: () -> T): T = withContext(io) {
        val r = driver.transaction(block)
        writes.update { it + 1 }
        r
    }

    private suspend fun <T> read(block: () -> T): T = withContext(io) { block() }

    override fun observeDocuments(query: DocQuery): Flow<List<DocumentEntry>> =
        writes.map { queryDocuments(query) }.distinctUntilChanged().flowOn(io)

    private fun queryDocuments(q: DocQuery): List<DocumentEntry> {
        val where = ArrayList<String>()
        val args = ArrayList<Any?>()
        when (q.scope) {
            Scope.FOLDER -> { where += "trashed IS NULL"; where += "folder_id IS ?"; args += q.folderId }
            Scope.ALL -> where += "trashed IS NULL"
            Scope.RECENT -> { where += "trashed IS NULL"; where += "opened IS NOT NULL" }
            Scope.PINNED -> { where += "trashed IS NULL"; where += "pinned = 1" }
            Scope.BOOKS -> { where += "trashed IS NULL"; where += "kind = ${DocKind.WEB.code}" }
            Scope.PDFS -> { where += "trashed IS NULL"; where += "kind = ${DocKind.PDF.code}" }
            Scope.TAG -> { where += "trashed IS NULL"; where += "id IN (SELECT doc_id FROM doc_tag WHERE tag_id = ?)"; args += q.folderId }
            Scope.TRASH -> where += "trashed IS NOT NULL"
        }
        q.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where += "title LIKE ? ESCAPE '\\'"
            args += "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        val order = when {
            q.scope == Scope.TRASH -> "trashed DESC"
            q.scope == Scope.RECENT -> "opened DESC"
            else -> when (q.sort) {
                Sort.MODIFIED -> "modified DESC"
                Sort.CREATED -> "created DESC"
                Sort.NAME -> "title COLLATE NOCASE ASC"
                Sort.OPENED -> "opened DESC"
            }
        }
        val docs = driver.query("$DOC_COLS WHERE ${where.joinToString(" AND ")} ORDER BY $order, id", *args.toTypedArray(), map = ::entry)
        if (docs.isEmpty()) return docs
        val tags = HashMap<String, MutableSet<String>>()
        driver.query("SELECT doc_id, tag_id FROM doc_tag") { tags.getOrPut(it.string(0)!!) { HashSet() }.add(it.string(1)!!) }
        return docs.map { d -> tags[d.id.value]?.let { d.copy(tagIds = it) } ?: d }
    }

    private fun entry(r: SqlRow) = DocumentEntry(
        DocId(r.string(0)!!), r.string(1)!!, DocKind.fromCode(r.long(2).toInt()), r.string(3), r.long(4), r.long(5),
        if (r.isNull(6)) null else r.long(6), r.long(7).toInt(), r.long(8), r.long(9) != 0L,
        if (r.isNull(10)) null else r.long(10), emptySet(),
    )

    override suspend fun upsert(entry: DocumentEntry) = write {
        driver.execute(
            """INSERT INTO document(id, title, kind, folder_id, created, modified, opened, page_count, cover_rev, pinned, trashed)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET title = excluded.title, kind = excluded.kind, folder_id = excluded.folder_id,
                 created = excluded.created, modified = excluded.modified, opened = excluded.opened,
                 page_count = excluded.page_count, cover_rev = excluded.cover_rev, pinned = excluded.pinned,
                 trashed = excluded.trashed""",
            entry.id.value, entry.title, entry.kind.code, entry.folderId, entry.created, entry.modified, entry.opened,
            entry.pageCount, entry.coverRev, if (entry.pinned) 1 else 0, entry.trashed,
        )
    }

    override suspend fun get(id: DocId): DocumentEntry? = read {
        val d = driver.query("$DOC_COLS WHERE id = ?", id.value, map = ::entry).firstOrNull() ?: return@read null
        val tags = driver.query("SELECT tag_id FROM doc_tag WHERE doc_id = ?", id.value) { it.string(0)!! }.toSet()
        d.copy(tagIds = tags)
    }

    override suspend fun updateCover(id: DocId, coverRev: Long, pageCount: Int) =
        write { driver.execute("UPDATE document SET cover_rev = ?, page_count = ? WHERE id = ?", coverRev, pageCount, id.value) }

    override suspend fun markOpened(id: DocId, at: Long) = write { driver.execute("UPDATE document SET opened = ? WHERE id = ?", at, id.value) }

    override suspend fun trash(ids: List<DocId>, at: Long) = write { forIds(ids) { ph, a -> driver.execute("UPDATE document SET trashed = ? WHERE id IN ($ph)", at, *a) } }

    override suspend fun restore(ids: List<DocId>) = write { forIds(ids) { ph, a -> driver.execute("UPDATE document SET trashed = NULL WHERE id IN ($ph)", *a) } }

    override suspend fun purge(id: DocId) = write {
        driver.execute("DELETE FROM document WHERE id = ?", id.value)
        driver.execute("DELETE FROM search WHERE doc_id = ?", id.value)
    }

    override suspend fun trashedBefore(at: Long): List<DocId> = read {
        driver.query("SELECT id FROM document WHERE trashed IS NOT NULL AND trashed < ? ORDER BY trashed", at) { DocId(it.string(0)!!) }
    }

    private fun forIds(ids: List<DocId>, run: (String, Array<Any?>) -> Unit) {
        for (chunk in ids.chunked(500)) run(chunk.joinToString(",") { "?" }, chunk.map { it.value }.toTypedArray())
    }

    override fun observeFolders(): Flow<List<Folder>> = writes.map {
        driver.query("SELECT id, parent_id, name, ord FROM folder ORDER BY ord, name COLLATE NOCASE, id") {
            Folder(it.string(0)!!, it.string(1), it.string(2)!!, it.double(3))
        }
    }.distinctUntilChanged().flowOn(io)

    private fun nextOrd(parent: String?): Double =
        driver.query("SELECT MAX(ord) FROM folder WHERE parent_id IS ?", parent) { if (it.isNull(0)) 0.0 else it.double(0) }.single() + 1.0

    private fun newId(): String = DocId.new(clock, random).value

    override suspend fun createFolder(name: String, parent: String?): Folder = write {
        val f = Folder(newId(), parent, name, nextOrd(parent))
        driver.execute("INSERT INTO folder(id, parent_id, name, ord, created) VALUES (?, ?, ?, ?, ?)", f.id, f.parentId, f.name, f.ord, clock.now())
        f
    }

    override suspend fun renameFolder(id: String, name: String) = write { driver.execute("UPDATE folder SET name = ? WHERE id = ?", name, id) }

    override suspend fun moveFolder(id: String, parent: String?) = write {
        var cur = parent
        while (cur != null) {
            require(cur != id) { "Cannot move a folder into itself or its descendant" }
            cur = driver.query("SELECT parent_id FROM folder WHERE id = ?", cur) { it.string(0) }.firstOrNull()
        }
        driver.execute("UPDATE folder SET parent_id = ?, ord = ? WHERE id = ?", parent, nextOrd(parent), id)
    }

    override suspend fun deleteFolder(id: String) = write {
        val rows = driver.query("SELECT parent_id FROM folder WHERE id = ?", id) { it.string(0) }
        if (rows.isEmpty()) return@write
        val parent = rows.single()
        driver.execute("UPDATE document SET folder_id = ? WHERE folder_id = ?", parent, id)
        driver.execute("UPDATE folder SET parent_id = ? WHERE parent_id = ?", parent, id)
        driver.execute("DELETE FROM folder WHERE id = ?", id)
    }

    override suspend fun moveDocuments(ids: List<DocId>, folder: String?) =
        write { forIds(ids) { ph, a -> driver.execute("UPDATE document SET folder_id = ? WHERE id IN ($ph)", folder, *a) } }

    override fun observeTags(): Flow<List<Tag>> = writes.map {
        driver.query("SELECT id, name, color FROM tag ORDER BY name COLLATE NOCASE") { Tag(it.string(0)!!, it.string(1)!!, it.long(2).toInt()) }
    }.distinctUntilChanged().flowOn(io)

    override suspend fun createTag(name: String, color: Int): Tag = write {
        driver.execute("INSERT OR IGNORE INTO tag(id, name, color) VALUES (?, ?, ?)", newId(), name, color)
        driver.query("SELECT id, name, color FROM tag WHERE name = ?", name) { Tag(it.string(0)!!, it.string(1)!!, it.long(2).toInt()) }.single()
    }

    override suspend fun deleteTag(id: String) = write { driver.execute("DELETE FROM tag WHERE id = ?", id) }

    override suspend fun setTags(doc: DocId, tagIds: Set<String>) = write {
        driver.execute("DELETE FROM doc_tag WHERE doc_id = ?", doc.value)
        for (t in tagIds) driver.execute("INSERT INTO doc_tag(doc_id, tag_id) VALUES (?, ?)", doc.value, t)
    }

    override suspend fun indexPage(doc: DocId, page: PageId, normalizedText: String) = write {
        driver.execute("DELETE FROM search WHERE doc_id = ? AND page_id = ?", doc.value, page.value.toString())
        driver.execute("INSERT INTO search(doc_id, page_id, body) VALUES (?, ?, ?)", doc.value, page.value.toString(), normalizedText)
    }

    override suspend fun removeIndex(doc: DocId, page: PageId?) = write {
        if (page == null) driver.execute("DELETE FROM search WHERE doc_id = ?", doc.value)
        else driver.execute("DELETE FROM search WHERE doc_id = ? AND page_id = ?", doc.value, page.value.toString())
    }

    override suspend fun search(normalizedQuery: String, limit: Int): List<SearchHit> = read {
        if (normalizedQuery.isBlank()) return@read emptyList()
        driver.query(
            "SELECT doc_id, page_id, snippet(search, '[', ']', '…', 2, 12) FROM search WHERE body MATCH ? LIMIT ?",
            normalizedQuery, limit,
        ) { SearchHit(DocId(it.string(0)!!), PageId(it.string(1)!!.toLong()), it.string(2) ?: "") }
    }

    override suspend fun getKv(key: String): String? = read { driver.query("SELECT value FROM kv WHERE key = ?", key) { it.string(0) }.firstOrNull() }

    override suspend fun putKv(key: String, value: String?) = write {
        if (value == null) driver.execute("DELETE FROM kv WHERE key = ?", key)
        else driver.execute("INSERT OR REPLACE INTO kv(key, value) VALUES (?, ?)", key, value)
    }

    companion object {
        private const val DOC_COLS =
            "SELECT id, title, kind, folder_id, created, modified, opened, page_count, cover_rev, pinned, trashed FROM document"

        /** Applies PRAGMAs and migrations to [driver] (blocking; call at startup) and returns the store. */
        fun open(driver: SqlDriver, io: CoroutineDispatcher, clock: Clock, random: Random = Random()): SqliteLibraryStore {
            SqlPragmas.apply(driver, readOnly = false)
            Migrations.apply(driver, LibrarySchema.VERSION, LibrarySchema.MIGRATIONS)
            return SqliteLibraryStore(driver, io, clock, random)
        }
    }
}
