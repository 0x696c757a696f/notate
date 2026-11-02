package app.notate.core.store.library

import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.PageId
import app.notate.core.store.TextNormalizer
import app.notate.core.testing.FakeClock
import app.notate.core.testing.JdbcSqlDriverFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Random

class SqliteLibraryStoreTest {
    private val clock = FakeClock(1_000)
    private fun store() = SqliteLibraryStore.open(JdbcSqlDriverFactory().openMemory(), Dispatchers.IO, clock, Random(1))

    private fun doc(
        id: String, title: String = id, kind: DocKind = DocKind.NOTEBOOK, folder: String? = null, modified: Long = 1,
        created: Long = 1, opened: Long? = null, pinned: Boolean = false, trashed: Long? = null,
    ) = DocumentEntry(DocId(id), title, kind, folder, created, modified, opened, 3, 0, pinned, trashed, emptySet())

    private suspend fun SqliteLibraryStore.ids(q: DocQuery) = observeDocuments(q).first().map { it.id.value }

    @Test fun upsertGetAndUpdate() = runBlocking<Unit> {
        val s = store()
        s.upsert(doc("A", "First"))
        assertThat(s.get(DocId("A"))!!.title).isEqualTo("First")
        s.upsert(doc("A", "Renamed", modified = 5))
        assertThat(s.get(DocId("A"))).isEqualTo(doc("A", "Renamed", modified = 5))
        assertThat(s.get(DocId("missing"))).isNull()
    }

    @Test fun updateCoverChangesOnlyCoverAndPageCount() = runBlocking<Unit> {
        val s = store()
        s.upsert(doc("A", "First", pinned = true))
        s.updateCover(DocId("A"), 4, 9)
        assertThat(s.get(DocId("A"))).isEqualTo(doc("A", "First", pinned = true).copy(coverRev = 4, pageCount = 9))
        s.updateCover(DocId("missing"), 1, 1)
    }

    @Test fun observeEmitsAgainAfterWrites() = runBlocking<Unit> {
        val s = store()
        val emissions = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val job = launch(Dispatchers.Default) {
            s.observeDocuments(DocQuery(scope = Scope.ALL)).collect { emissions += it.map { d -> d.id.value } }
        }
        suspend fun awaitCount(n: Int) = withTimeout(5000) { while (emissions.size < n) kotlinx.coroutines.delay(5) }
        awaitCount(1)
        s.upsert(doc("A"))
        awaitCount(2)
        s.upsert(doc("B", modified = 2))
        awaitCount(3)
        s.putKv("unrelated", "x")        // result unchanged -> no new emission
        kotlinx.coroutines.delay(100)
        job.cancel()
        assertThat(emissions.toList()).containsExactly(emptyList<String>(), listOf("A"), listOf("B", "A")).inOrder()
    }

    @Test fun scopesFilterCorrectly() = runBlocking<Unit> {
        val s = store()
        val folder = s.createFolder("Math", null)
        val tag = s.createTag("exam", 0xFF00FF00.toInt())
        s.upsert(doc("root1", modified = 1))
        s.upsert(doc("inFolder", folder = folder.id, modified = 2))
        s.upsert(doc("recent", opened = 50, modified = 3))
        s.upsert(doc("pinned", pinned = true, modified = 4))
        s.upsert(doc("book", kind = DocKind.WEB, modified = 5))
        s.upsert(doc("pdf", kind = DocKind.PDF, modified = 6))
        s.upsert(doc("gone", trashed = 77, modified = 7))
        s.setTags(DocId("pdf"), setOf(tag.id))

        assertThat(s.ids(DocQuery(folderId = null, scope = Scope.FOLDER))).containsExactly("pdf", "book", "pinned", "recent", "root1").inOrder()
        assertThat(s.ids(DocQuery(folderId = folder.id, scope = Scope.FOLDER))).containsExactly("inFolder")
        assertThat(s.ids(DocQuery(scope = Scope.ALL))).hasSize(6)
        assertThat(s.ids(DocQuery(scope = Scope.RECENT))).containsExactly("recent")
        assertThat(s.ids(DocQuery(scope = Scope.PINNED))).containsExactly("pinned")
        assertThat(s.ids(DocQuery(scope = Scope.BOOKS))).containsExactly("book")
        assertThat(s.ids(DocQuery(scope = Scope.PDFS))).containsExactly("pdf")
        assertThat(s.ids(DocQuery(folderId = tag.id, scope = Scope.TAG))).containsExactly("pdf")
        assertThat(s.ids(DocQuery(scope = Scope.TRASH))).containsExactly("gone")
        assertThat(s.observeDocuments(DocQuery(scope = Scope.TAG, folderId = tag.id)).first().single().tagIds).containsExactly(tag.id)
    }

    @Test fun sortAndTextFilter() = runBlocking<Unit> {
        val s = store()
        s.upsert(doc("1", "banana", modified = 3, created = 1, opened = 10))
        s.upsert(doc("2", "Apple", modified = 1, created = 3, opened = 30))
        s.upsert(doc("3", "cherry 100%", modified = 2, created = 2))
        fun q(sort: Sort, text: String? = null) = DocQuery(scope = Scope.ALL, sort = sort, text = text)
        assertThat(s.ids(q(Sort.MODIFIED))).containsExactly("1", "3", "2").inOrder()
        assertThat(s.ids(q(Sort.CREATED))).containsExactly("2", "3", "1").inOrder()
        assertThat(s.ids(q(Sort.NAME))).containsExactly("2", "1", "3").inOrder()
        assertThat(s.ids(q(Sort.OPENED))).containsExactly("2", "1", "3").inOrder()
        assertThat(s.ids(q(Sort.NAME, "AN"))).containsExactly("1")
        assertThat(s.ids(q(Sort.NAME, "100%"))).containsExactly("3")
        assertThat(s.ids(q(Sort.NAME, "%"))).containsExactly("3")
    }

    @Test fun trashRestorePurgeAndTrashedBefore() = runBlocking<Unit> {
        val s = store()
        s.upsert(doc("A")); s.upsert(doc("B")); s.upsert(doc("C"))
        s.trash(listOf(DocId("A"), DocId("B")), at = 100)
        s.trash(listOf(DocId("C")), at = 500)
        assertThat(s.trashedBefore(200)).containsExactly(DocId("A"), DocId("B"))
        assertThat(s.trashedBefore(100)).isEmpty()
        s.restore(listOf(DocId("A")))
        assertThat(s.get(DocId("A"))!!.trashed).isNull()
        assertThat(s.ids(DocQuery(scope = Scope.TRASH))).containsExactly("C", "B").inOrder()
        s.indexPage(DocId("B"), PageId(1), "hello")
        s.purge(DocId("B"))
        assertThat(s.get(DocId("B"))).isNull()
        assertThat(s.search("\"hello*\"")).isEmpty()
    }

    @Test fun markOpenedAndMoveDocuments() = runBlocking<Unit> {
        val s = store()
        val f = s.createFolder("F", null)
        s.upsert(doc("A")); s.upsert(doc("B"))
        s.markOpened(DocId("A"), 42)
        assertThat(s.get(DocId("A"))!!.opened).isEqualTo(42L)
        s.moveDocuments(listOf(DocId("A"), DocId("B")), f.id)
        assertThat(s.get(DocId("B"))!!.folderId).isEqualTo(f.id)
        s.moveDocuments(listOf(DocId("A")), null)
        assertThat(s.get(DocId("A"))!!.folderId).isNull()
    }

    @Test fun deleteFolderMovesContentsUp() = runBlocking<Unit> {
        val s = store()
        val outer = s.createFolder("outer", null)
        val inner = s.createFolder("inner", outer.id)
        val deep = s.createFolder("deep", inner.id)
        s.upsert(doc("inInner", folder = inner.id))
        s.upsert(doc("inOuter", folder = outer.id))
        s.deleteFolder(inner.id)
        assertThat(s.get(DocId("inInner"))!!.folderId).isEqualTo(outer.id)
        assertThat(s.get(DocId("inOuter"))!!.folderId).isEqualTo(outer.id)
        val folders = s.observeFolders().first()
        assertThat(folders.map { it.name }).containsExactly("outer", "deep")
        assertThat(folders.first { it.id == deep.id }.parentId).isEqualTo(outer.id)
        s.deleteFolder(outer.id)
        assertThat(s.get(DocId("inInner"))!!.folderId).isNull()
    }

    @Test fun folderRenameMoveAndCycleGuard() = runBlocking<Unit> {
        val s = store()
        val a = s.createFolder("a", null)
        val b = s.createFolder("b", null)
        assertThat(b.ord).isGreaterThan(a.ord)
        s.renameFolder(a.id, "A2")
        s.moveFolder(b.id, a.id)
        val c = s.createFolder("c", b.id)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { s.moveFolder(a.id, c.id) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { s.moveFolder(a.id, a.id) } }
        val f = s.observeFolders().first()
        assertThat(f.first { it.id == a.id }.name).isEqualTo("A2")
        assertThat(f.first { it.id == b.id }.parentId).isEqualTo(a.id)
        s.moveFolder(b.id, null)
        assertThat(s.observeFolders().first().first { it.id == b.id }.parentId).isNull()
    }

    @Test fun tags() = runBlocking<Unit> {
        val s = store()
        val t1 = s.createTag("physics", 1)
        val again = s.createTag("physics", 2)
        assertThat(again).isEqualTo(t1)
        val t2 = s.createTag("Algebra", 3)
        assertThat(s.observeTags().first().map { it.name }).containsExactly("Algebra", "physics").inOrder()
        s.upsert(doc("A"))
        s.setTags(DocId("A"), setOf(t1.id, t2.id))
        assertThat(s.get(DocId("A"))!!.tagIds).containsExactly(t1.id, t2.id)
        s.setTags(DocId("A"), setOf(t2.id))
        assertThat(s.get(DocId("A"))!!.tagIds).containsExactly(t2.id)
        s.deleteTag(t2.id)
        assertThat(s.get(DocId("A"))!!.tagIds).isEmpty()
        // upsert keeps tags
        s.setTags(DocId("A"), setOf(t1.id))
        s.upsert(doc("A", "new title"))
        assertThat(s.get(DocId("A"))!!.tagIds).containsExactly(t1.id)
    }

    @Test fun searchEnglishPrefixAndChinese() = runBlocking<Unit> {
        val s = store()
        s.upsert(doc("A")); s.upsert(doc("B"))
        s.indexPage(DocId("A"), PageId(1), TextNormalizer.normalize("The Fundamental Integral theorem"))
        s.indexPage(DocId("A"), PageId(2), TextNormalizer.normalize("nothing relevant"))
        s.indexPage(DocId("B"), PageId(7), TextNormalizer.normalize("定积分公式 与 微分"))
        val en = s.search(TextNormalizer.toMatchQuery("integ"))
        assertThat(en.map { it.doc.value to it.page.value }).containsExactly("A" to 1L)
        assertThat(en.single().snippet).contains("[integral]")
        val zh = s.search(TextNormalizer.toMatchQuery("积分"))
        assertThat(zh.map { it.doc.value to it.page.value }).containsExactly("B" to 7L)
        assertThat(s.search(TextNormalizer.toMatchQuery("分积"))).isEmpty()
        assertThat(s.search("")).isEmpty()
        // re-indexing replaces; removal works
        s.indexPage(DocId("A"), PageId(1), "totally different")
        assertThat(s.search(TextNormalizer.toMatchQuery("integ"))).isEmpty()
        s.removeIndex(DocId("A"), PageId(2))
        s.removeIndex(DocId("B"))
        assertThat(s.search(TextNormalizer.toMatchQuery("积分"))).isEmpty()
        assertThat(s.search(TextNormalizer.toMatchQuery("different"))).hasSize(1)
        s.removeIndex(DocId("A"))
        assertThat(s.search(TextNormalizer.toMatchQuery("different"))).isEmpty()
    }

    @Test fun searchIgnoresIdColumns() = runBlocking<Unit> {
        val s = store()
        s.indexPage(DocId("ZZZDOC"), PageId(123), "alpha")
        assertThat(s.search(TextNormalizer.toMatchQuery("zzzdoc"))).isEmpty()
        assertThat(s.search(TextNormalizer.toMatchQuery("123"))).isEmpty()
    }

    @Test fun searchHonoursLimit() = runBlocking<Unit> {
        val s = store()
        for (i in 1..10) s.indexPage(DocId("D"), PageId(i.toLong()), "word")
        assertThat(s.search(TextNormalizer.toMatchQuery("word"), limit = 4)).hasSize(4)
    }

    @Test fun kv() = runBlocking<Unit> {
        val s = store()
        assertThat(s.getKv("workspace")).isNull()
        s.putKv("workspace", "{\"a\":1}")
        assertThat(s.getKv("workspace")).isEqualTo("{\"a\":1}")
        s.putKv("workspace", "x")
        assertThat(s.getKv("workspace")).isEqualTo("x")
        s.putKv("workspace", null)
        assertThat(s.getKv("workspace")).isNull()
    }
}
