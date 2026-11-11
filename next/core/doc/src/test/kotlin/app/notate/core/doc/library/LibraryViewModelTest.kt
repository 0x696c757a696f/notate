package app.notate.core.doc.library

import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.PageTemplate
import app.notate.core.store.library.DocumentEntry
import app.notate.core.store.library.LibraryStore
import app.notate.core.store.library.Sort
import app.notate.core.store.library.SqliteLibraryStore
import app.notate.core.testing.FakeClock
import app.notate.core.testing.JdbcSqlDriverFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Random

/** Fake file layer: keeps the set of "existing" document directories. */
private class FakeDocs(private val clock: FakeClock) : LibraryDocs {
    val dirs = LinkedHashSet<String>()
    val renamed = ArrayList<Pair<DocId, String>>()
    private var n = 0

    private fun entry(title: String): DocumentEntry {
        val id = DocId("DOC%05d".format(++n))
        dirs += id.value
        val now = clock.now()
        return DocumentEntry(id, title, DocKind.NOTEBOOK, null, now, now, null, 1, 0, false, null, emptySet())
    }

    override suspend fun createNotebook(title: String, template: PageTemplate) = entry(title)
    override suspend fun duplicate(id: DocId, title: String) = entry(title)
    override suspend fun rename(id: DocId, title: String) { renamed += id to title }
    override suspend fun deleteFiles(id: DocId) { dirs -= id.value }
}

class LibraryViewModelTest {
    private val clock = FakeClock(1_000)
    private val docs = FakeDocs(clock)
    private lateinit var store: LibraryStore
    private lateinit var scope: CoroutineScope
    private lateinit var c: LibraryController

    @Before fun setUp() {
        store = SqliteLibraryStore.open(JdbcSqlDriverFactory().openMemory(), Dispatchers.IO, clock, Random(1))
        scope = CoroutineScope(Job() + Dispatchers.Default)
        c = LibraryController(scope, store, docs, clock)
    }

    @After fun tearDown() = scope.cancel()

    private suspend fun await(cond: (LibraryUiState) -> Boolean): LibraryUiState =
        withTimeout(10_000) { c.state.first { it.loaded && cond(it) } }

    private suspend fun create(title: String = LibraryController.DEFAULT_TITLE) = c.createNotebook(title, PageTemplate.DEFAULT)

    @Test fun duplicateTitlesGetNumbers() = runBlocking<Unit> {
        create(); create(); create()
        assertThat(await { it.documents.size == 3 }.documents.map { it.title })
            .containsExactly("Untitled Note", "Untitled Note 2", "Untitled Note 3")
        assertThat(c.suggestNotebookTitle()).isEqualTo("Untitled Note 4")
    }

    @Test fun foldersGetNumbersToo() = runBlocking<Unit> {
        c.createFolder(); c.createFolder()
        assertThat(await { it.folders.size == 2 }.folders.map { it.name }).containsExactly("New Folder", "New Folder 2")
    }

    @Test fun newNotebookLandsInTheBrowsedFolder() = runBlocking<Unit> {
        val physics = c.createFolder("Physics")
        c.openFolder(physics.id)
        create("Lecture 1")
        assertThat(await { it.documents.size == 1 && it.currentFolder != null }.currentFolder?.name).isEqualTo("Physics")
        c.up()
        assertThat(await { it.location == LibraryLocation.Folder(null) }.documents).isEmpty()
        // Same title is free at the top level because folders are separate namespaces.
        create("Lecture 1")
        assertThat(await { it.documents.size == 1 }.documents.single().title).isEqualTo("Lecture 1")
    }

    @Test fun scopesSplitDocuments() = runBlocking<Unit> {
        val a = create("A"); val b = create("B"); create("C")
        c.togglePin(a)
        c.markOpened(b)
        assertThat(await { it.documents.size == 3 }.documents).hasSize(3)
        c.open(LibraryLocation.Pinned)
        assertThat(await { it.location == LibraryLocation.Pinned }.documents.map { it.id }).containsExactly(a)
        c.open(LibraryLocation.Recent)
        assertThat(await { it.location == LibraryLocation.Recent }.documents.map { it.id }).containsExactly(b)
        val tag = c.createTag("Exam", 0xFF000000.toInt())
        c.setTags(a, setOf(tag.id))
        c.open(LibraryLocation.Tag(tag.id))
        assertThat(await { it.location is LibraryLocation.Tag }.documents.map { it.id }).containsExactly(a)
        c.togglePin(a)
        c.open(LibraryLocation.Pinned)
        assertThat(await { it.location == LibraryLocation.Pinned }.documents).isEmpty()
    }

    @Test fun sortOrders() = runBlocking<Unit> {
        clock.now = 10; val b = create("b")
        clock.now = 20; create("A")
        clock.now = 30; val z = create("Z")
        c.markOpened(b)
        clock.now = 40; c.rename(b, "b") // no change, stays at its old modified time
        assertThat(await { it.documents.size == 3 }.documents.map { it.title }).containsExactly("Z", "A", "b").inOrder()
        c.setSort(Sort.NAME)
        assertThat(await { it.sort == Sort.NAME && it.documents.size == 3 }.documents.map { it.title }).containsExactly("A", "b", "Z").inOrder()
        c.setSort(Sort.CREATED)
        assertThat(await { it.sort == Sort.CREATED && it.documents.size == 3 }.documents.map { it.title }).containsExactly("Z", "A", "b").inOrder()
        c.setSort(Sort.OPENED)
        assertThat(await { it.sort == Sort.OPENED && it.documents.size == 3 }.documents.first().id).isEqualTo(b)
        assertThat(z.value).isNotEmpty()
    }

    @Test fun filterSearchesTitlesAcrossFolders() = runBlocking<Unit> {
        val f = c.createFolder("Physics")
        c.openFolder(f.id)
        create("Optics notes")
        c.openFolder(null)
        create("Algebra")
        c.setFilter("optic")
        val s = await { it.filter == "optic" && it.documents.size == 1 }
        assertThat(s.documents.single().title).isEqualTo("Optics notes")
        assertThat(s.folders).isEmpty()
    }

    @Test fun renameUpdatesIndexAndDocument() = runBlocking<Unit> {
        val a = create("Old")
        c.rename(a, "  New  ")
        assertThat(await { it.documents.singleOrNull()?.title == "New" }.documents).hasSize(1)
        assertThat(docs.renamed).containsExactly(a to "New")
        c.rename(a, "   ")
        assertThat(store.get(a)!!.title).isEqualTo("New")
    }

    @Test fun bulkMoveAndTrashFromMultiSelect() = runBlocking<Unit> {
        val ids = listOf(create("A"), create("B"), create("C"))
        val f = c.createFolder("Box")
        await { it.documents.size == 3 }
        ids.forEach(c::toggleSelect)
        assertThat(await { it.selection.size == 3 }.selecting).isTrue()
        c.move(c.state.value.selection, f.id)
        assertThat(await { it.documents.isEmpty() && !it.selecting }.selection).isEmpty()
        c.openFolder(f.id)
        assertThat(await { it.documents.size == 3 }.documents).hasSize(3)
        c.selectAll()
        assertThat(await { it.selection.size == 3 }.selection).hasSize(3)
        c.trash(c.state.value.selection)
        assertThat(await { it.documents.isEmpty() }.selection).isEmpty()
        c.open(LibraryLocation.Trash)
        assertThat(await { it.location == LibraryLocation.Trash && it.documents.size == 3 }.documents).hasSize(3)
    }

    @Test fun restoreBringsBackOneAndEmptyTrashDeletesTheRest() = runBlocking<Unit> {
        val ids = listOf(create("A"), create("B"), create("C"))
        c.trash(ids)
        c.open(LibraryLocation.Trash)
        await { it.documents.size == 3 }
        c.restore(listOf(ids[0]))
        await { it.documents.size == 2 }
        c.emptyTrash()
        assertThat(await { it.documents.isEmpty() }.documents).isEmpty()
        assertThat(docs.dirs).containsExactly(ids[0].value)
        assertThat(store.get(ids[1])).isNull()
        c.openFolder(null)
        assertThat(await { it.location == LibraryLocation.Folder(null) && it.documents.size == 1 }.documents.single().id).isEqualTo(ids[0])
    }

    @Test fun trashedDocumentsAreHiddenFromTheShelf() = runBlocking<Unit> {
        val a = create("A"); create("B")
        c.trash(listOf(a))
        assertThat(await { st -> st.documents.map { it.title } == listOf("B") }.documents).hasSize(1)
    }

    @Test fun deletingABrowsedFolderMovesUpAndKeepsDocuments() = runBlocking<Unit> {
        val f = c.createFolder("Gone")
        c.openFolder(f.id)
        create("Inside")
        await { it.documents.size == 1 }
        c.deleteFolder(f.id)
        val s = await { it.location == LibraryLocation.Folder(null) && it.allFolders.isEmpty() && it.documents.size == 1 }
        assertThat(s.documents.single().title).isEqualTo("Inside")
    }

    @Test fun duplicateAddsCopyNextToTheOriginal() = runBlocking<Unit> {
        val a = create("Notes")
        val tag = c.createTag("Exam", 0xFF000000.toInt())
        c.setTags(a, setOf(tag.id))
        c.duplicate(listOf(a))
        c.duplicate(listOf(a))
        val s = await { st -> st.documents.size == 3 && st.documents.all { it.tagIds == setOf(tag.id) } }
        assertThat(s.documents.map { it.title }).containsExactly("Notes", "Notes copy", "Notes copy 2")
        assertThat(s.documents.filter { it.title != "Notes" }.all { it.tagIds == setOf(tag.id) }).isTrue()
    }

    @Test fun selectionDropsDocumentsThatLeaveTheView() = runBlocking<Unit> {
        val a = create("A"); val b = create("B")
        await { it.documents.size == 2 }
        c.toggleSelect(a); c.toggleSelect(b)
        c.trash(listOf(a))
        // trash() clears the selection; selecting again only keeps live ids.
        c.toggleSelect(a); c.toggleSelect(b)
        assertThat(await { it.documents.size == 1 && it.selecting }.selection).containsExactly(b)
    }

    @Test fun addTagToSeveralDocuments() = runBlocking<Unit> {
        val a = create("A"); val b = create("B")
        val tag = c.createTag("Exam", 0xFF000000.toInt())
        c.addTag(listOf(a, b), tag.id)
        assertThat(store.get(a)!!.tagIds).containsExactly(tag.id)
        assertThat(store.get(b)!!.tagIds).containsExactly(tag.id)
    }
}
