package app.notate.core.store.library

import app.notate.core.model.DocKind
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.testing.FakeClock
import app.notate.core.testing.JdbcSqlDriverFactory
import app.notate.core.testing.meta
import app.notate.core.testing.page
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random

class LibraryRebuilderTest {
    @Test fun rebuildsEntriesFromDocumentPackages() = runBlocking<Unit> {
        val clock = FakeClock(10)
        val drivers = JdbcSqlDriverFactory()
        val factory = SqliteDocStoreFactory(drivers, { Dispatchers.IO }, clock)
        val root = Files.createTempDirectory("lib").toFile()
        val docs = File(root, "docs")
        val libFile = File(root, "library.db")

        val ids = listOf("01AAAAAAAAAAAAAAAAAAAAAAAA", "01BBBBBBBBBBBBBBBBBBBBBBBB")
        factory.create(File(docs, ids[0]), meta(ids[0], title = "Calc"), listOf(page(1), page(2), page(3))).close()
        factory.create(File(docs, ids[1]), meta(ids[1], DocKind.PDF, "Paper"), listOf(page(1))).close()
        File(docs, "garbage").mkdirs()
        File(docs, "corrupt").mkdirs(); File(docs, "corrupt/doc.db").writeText("not a database")

        var lib = SqliteLibraryStore.open(drivers.open(libFile, false), Dispatchers.IO, clock, Random(1))
        ids.forEach { lib.upsert(DocumentEntry(app.notate.core.model.DocId(it), "x", DocKind.NOTEBOOK, null, 0, 0, null, 0, 0, false, null, emptySet())) }
        lib.putKv("k", "v")
        libFile.delete()
        File(root, "library.db-wal").delete(); File(root, "library.db-shm").delete()

        lib = SqliteLibraryStore.open(drivers.open(libFile, false), Dispatchers.IO, clock, Random(2))
        assertThat(lib.observeDocuments(DocQuery(scope = Scope.ALL)).first()).isEmpty()
        val result = LibraryRebuilder(docs, factory, lib).rebuild()
        assertThat(result.restored).isEqualTo(2)
        assertThat(result.failed).containsExactly("corrupt")
        val all = lib.observeDocuments(DocQuery(scope = Scope.ALL, sort = Sort.NAME)).first()
        assertThat(all.map { it.title }).containsExactly("Calc", "Paper").inOrder()
        assertThat(all.map { it.pageCount }).containsExactly(3, 1).inOrder()
        assertThat(all.map { it.kind }).containsExactly(DocKind.NOTEBOOK, DocKind.PDF).inOrder()
        assertThat(lib.getKv("k")).isNull()
    }
}
