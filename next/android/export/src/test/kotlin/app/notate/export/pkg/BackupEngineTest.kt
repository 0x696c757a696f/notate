package app.notate.export.pkg

import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.PageTemplate
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.store.library.DocQuery
import app.notate.core.store.library.DocumentEntry
import app.notate.core.store.library.Scope
import app.notate.core.store.library.SqliteLibraryStore
import app.notate.core.testing.FakeClock
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.platform.sql.AndroidSqlDriverFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = FakeClock(1_000)
    private val drivers = AndroidSqlDriverFactory()
    private val io = Dispatchers.IO

    private inner class Env(name: String) {
        val root = tmp.newFolder(name)
        val docsRoot = File(root, "docs").also { it.mkdirs() }
        val factory = SqliteDocStoreFactory(drivers, { Dispatchers.IO.limitedParallelism(1) }, clock)
        val library = SqliteLibraryStore.open(drivers.open(File(root, "library.db"), false), Dispatchers.IO.limitedParallelism(1), clock)
        val docDir = { id: DocId -> File(docsRoot, id.value) }
        val exporter = PackageExporter(factory, { library }, docDir, {}, io) { clock.now() }
        val importer = PackageImporter(factory, { library }, docDir, File(root, "cache"), drivers, clock, io, exporter, { false })
        val engine = BackupEngine(
            ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver, exporter, importer, { library }, drivers,
            File(root, "tmp"), clock, io,
        )

        suspend fun addDoc(id: String, title: String, modified: Long = 5_000, folder: String? = null, tag: String? = null): DocId {
            val docId = DocId(id)
            factory.create(docDir(docId), meta(id = id, title = title).copy(modifiedAt = modified), listOf(page(1))).close()
            library.upsert(DocumentEntry(docId, title, DocKind.NOTEBOOK, folder, 1, modified, null, 1, 0, false, null, emptySet()))
            if (tag != null) library.setTags(docId, setOf(library.createTag(tag, 0).id))
            return docId
        }
    }

    private fun backupFiles(dir: File): List<File> =
        File(dir, "Notate Backup").listFiles()!!.filter { it.name.endsWith(".notate") }.sortedBy { it.name }

    @Test fun `first run backs up everything, trash is skipped, second run only writes changes`() = runBlocking<Unit> {
        val env = Env("a")
        val target = tmp.newFolder("target")
        val one = env.addDoc("01AAAAAAAA0000000000000001", "Algebra")
        env.addDoc("01BBBBBBBB0000000000000002", "Geometry")
        val gone = env.addDoc("01CCCCCCCC0000000000000003", "Trashed")
        env.library.trash(listOf(gone), 10)
        val root = DocumentFile.fromFile(target)

        val first = env.engine.run(root)
        assertThat(first.written).isEqualTo(2); assertThat(first.failed).isEqualTo(0)
        assertThat(backupFiles(target).map { it.name }).containsExactly("Algebra [01AAAAAA].notate", "Geometry [01BBBBBB].notate")
        assertThat(File(target, "Notate Backup/library.json").exists()).isTrue()

        val stamps = backupFiles(target).associate { it.name to it.readBytes().contentHashCode() }
        val second = env.engine.run(root)
        assertThat(second.written).isEqualTo(0); assertThat(second.unchanged).isEqualTo(2)

        env.library.upsert(env.library.get(one)!!.copy(modified = 9_000, title = "Algebra II"))
        val third = env.engine.run(root)
        assertThat(third.written).isEqualTo(1); assertThat(third.unchanged).isEqualTo(1)
        val names = backupFiles(target).map { it.name }
        assertThat(names).containsExactly("Algebra II [01AAAAAA].notate", "Geometry [01BBBBBB].notate")   // old name replaced
        assertThat(backupFiles(target).first { it.name.startsWith("Geometry") }.readBytes().contentHashCode()).isEqualTo(stamps["Geometry [01BBBBBB].notate"])
    }

    @Test fun `purged documents keep their backup file`() = runBlocking<Unit> {
        val env = Env("b")
        val target = tmp.newFolder("target2")
        val id = env.addDoc("01AAAAAAAA0000000000000001", "Keep me")
        env.engine.run(DocumentFile.fromFile(target))
        env.library.purge(id)
        env.engine.run(DocumentFile.fromFile(target))
        assertThat(backupFiles(target)).hasSize(1)
    }

    @Test fun `restore into an empty library brings back content, folders and tags`() = runBlocking<Unit> {
        val src = Env("src")
        val folder = src.library.createFolder("Physics", null)
        src.addDoc("01AAAAAAAA0000000000000001", "Optics", folder = folder.id, tag = "exam")
        val target = tmp.newFolder("target3")
        val root = DocumentFile.fromFile(target)
        src.engine.run(root)

        val dst = Env("dst")
        val items = dst.engine.list(root)
        assertThat(items.map { it.info.title }).containsExactly("Optics")
        assertThat(dst.engine.find(root, DocId("01AAAAAAAA0000000000000001"))).isNotNull()
        val layout = dst.engine.readLayout(root)
        assertThat(layout).isNotNull()
        val restored = dst.engine.restore(items, layout)
        assertThat(restored).hasSize(1)

        val doc = dst.library.observeDocuments(DocQuery(scope = Scope.ALL)).first().single()
        assertThat(doc.title).isEqualTo("Optics")
        val physics = dst.library.observeFolders().first().single()
        assertThat(physics.name).isEqualTo("Physics")
        assertThat(doc.folderId).isEqualTo(physics.id)
        assertThat(dst.library.observeTags().first().map { it.name }).containsExactly("exam")
        dst.factory.open(dst.docDir(doc.id), readOnly = true).use { assertThat(it.readPages()).hasSize(1) }
    }

    @Test fun `importing the same package twice offers conflict handling`() = runBlocking<Unit> {
        val env = Env("c")
        val id = env.addDoc("01AAAAAAAA0000000000000001", "Notes", tag = "t")
        val file = File(tmp.newFolder("out"), "Notes.notate")
        env.exporter.exportToFile(id, file)

        val staged = env.importer.stage { file.inputStream() }
        assertThat(env.importer.existing(staged)).isNotNull()
        val copy = env.importer.import(staged, ConflictMode.KEEP_BOTH)
        assertThat(copy).isNotEqualTo(id)
        assertThat(env.library.get(copy)!!.title).isEqualTo("Notes (imported)")

        val staged2 = env.importer.stage { file.inputStream() }
        env.importer.import(staged2, ConflictMode.REPLACE)
        val all = env.library.observeDocuments(DocQuery(scope = Scope.ALL)).first()
        assertThat(all.map { it.title }).containsExactly("Notes", "Notes (imported)")
        assertThat(env.library.observeDocuments(DocQuery(scope = Scope.TRASH)).first().map { it.title }).containsExactly("Notes")
    }
}
