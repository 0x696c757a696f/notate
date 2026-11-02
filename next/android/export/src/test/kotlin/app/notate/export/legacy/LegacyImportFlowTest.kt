@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.notate.export.legacy

import app.notate.core.legacy.LegacyBackground
import app.notate.core.legacy.LegacyCanvasData
import app.notate.core.legacy.LegacyCanvasType
import app.notate.core.legacy.LegacyImageData
import app.notate.core.legacy.LegacyLinkData
import app.notate.core.legacy.LegacyRegionProto
import app.notate.core.legacy.LegacyStrokeData
import app.notate.core.legacy.LegacyStrokeType
import app.notate.core.legacy.LegacyTag
import app.notate.core.model.DocKind
import app.notate.core.model.ImageItem
import app.notate.core.model.LinkItem
import app.notate.core.model.LinkTarget
import app.notate.core.model.Stroke
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.store.library.DocQuery
import app.notate.core.store.library.Scope
import app.notate.core.store.library.SqliteLibraryStore
import app.notate.core.testing.FakeClock
import app.notate.platform.sql.AndroidSqlDriverFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LegacyImportFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = FakeClock()
    private val drivers = AndroidSqlDriverFactory()
    private lateinit var docsRoot: File
    private lateinit var source: File
    private lateinit var library: SqliteLibraryStore
    private lateinit var factory: SqliteDocStoreFactory
    private lateinit var importer: LegacyImporter

    @Before fun setUp() {
        docsRoot = tmp.newFolder("docs"); source = tmp.newFolder("source")
        factory = SqliteDocStoreFactory(drivers, { Dispatchers.IO.limitedParallelism(1) }, clock)
        library = SqliteLibraryStore.open(drivers.open(File(tmp.root, "library.db"), false), Dispatchers.IO.limitedParallelism(1), clock)
        importer = LegacyImporter(factory, { library }, { File(docsRoot, it.value) }, tmp.newFolder("cache"), clock, Dispatchers.IO)
    }

    private fun stroke(y: Float) = LegacyStrokeData(
        pointsPacked = floatArrayOf(100f, y, 2048f, 1f, 0f, 0f, 200f, y + 10f, 2048f, 1f, 0f, 0f),
        timestampsPacked = longArrayOf(1000, 1010), color = 0xFF000000.toInt(), width = 10f, style = LegacyStrokeType.FINELINER, strokeOrder = 1,
    )

    private fun note(
        path: String, uuid: String?, regions: List<LegacyRegionProto> = listOf(LegacyRegionProto(0, 0, strokes = listOf(stroke(100f)))),
        manifest: LegacyCanvasData = LegacyCanvasData(uuid = uuid), extra: Map<String, ByteArray> = emptyMap(),
    ): File {
        val f = File(source, path).also { it.parentFile.mkdirs() }
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("manifest.bin", ProtoBuf.encodeToByteArray(LegacyCanvasData.serializer(), manifest))
            for (r in regions) put("r_${r.idX}_${r.idY}.bin", ProtoBuf.encodeToByteArray(LegacyRegionProto.serializer(), r))
            extra.forEach { (n, b) -> put(n, b) }
        }
        f.setLastModified(1_700_000_000_000L)
        return f
    }

    private fun scan() = LegacyScanner.scanDir(source)

    @Test fun `scanner finds notes in sub folders, skips packages and reports broken files`() {
        note("a.notate", "u1"); note("Physics/Optics/b.notate", "u2")
        File(source, "pkg.notate").also { f ->
            ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("manifest.json")); z.write("""{"format":"notate-package"}""".toByteArray()); z.closeEntry() }
        }
        File(source, "junk.notate").writeText("not a zip")
        File(source, "readme.txt").writeText("x")
        val r = scan()
        assertThat(r.notes.map { it.title }).containsExactly("a", "b")
        assertThat(r.notes.first { it.title == "b" }.folders).containsExactly("Physics", "Optics").inOrder()
        assertThat(r.notes.map { it.uuid }).containsExactly("u1", "u2")
        assertThat(r.packages).isEqualTo(1)
        assertThat(r.unreadable.map { it.name }).containsExactly("junk.notate")
    }

    @Test fun `import creates documents, folders, tags and images`() = runBlocking {
        val png = byteArrayOf(1, 2, 3, 4)
        note("Physics/Optics/lens.notate", "u-lens",
            regions = listOf(LegacyRegionProto(0, 0, strokes = listOf(stroke(100f)),
                images = listOf(LegacyImageData("images/p.png", 0f, 0f, 400f, 300f, 0f, 2)),
                links = listOf(LegacyLinkData("see", "u-other", 10f, 10f, 200f, 50f, 0f, 3, 0)))),
            manifest = LegacyCanvasData(uuid = "u-lens", backgroundStyle = LegacyBackground.Grid(spacing = 227f), tagIds = listOf("t1"),
                tagDefinitions = listOf(LegacyTag("t1", "Math", 0xFF112233.toInt()))),
            extra = mapOf("images/p.png" to png))
        note("other.notate", "u-other")
        val report = importer.importAll(scan().notes)
        assertThat(report.imported).isEqualTo(2); assertThat(report.failures).isEmpty()

        val docs = library.observeDocuments(DocQuery(scope = Scope.ALL)).first()
        assertThat(docs.map { it.title }).containsExactly("lens", "other")
        val lens = docs.first { it.title == "lens" }
        val folders = library.observeFolders().first()
        val optics = folders.first { it.name == "Optics" }
        assertThat(folders.first { it.id == optics.parentId }.name).isEqualTo("Physics")
        assertThat(lens.folderId).isEqualTo(optics.id)
        assertThat(docs.first { it.title == "other" }.folderId).isNull()
        assertThat(library.observeTags().first().map { it.name }).containsExactly("Math")
        assertThat(lens.tagIds).hasSize(1)
        assertThat(lens.modified).isEqualTo(1_700_000_000_000L)

        factory.open(File(docsRoot, lens.id.value), readOnly = true).use { store ->
            assertThat(store.readMeta().kind).isEqualTo(DocKind.NOTEBOOK)
            val page = store.readPages().single()
            val items = store.readItems(page.id)
            assertThat(items.filterIsInstance<Stroke>()).hasSize(1)
            val img = items.filterIsInstance<ImageItem>().single()
            assertThat(store.assetFile(img.asset)!!.readBytes()).isEqualTo(png)
            // The link to the other note of the same batch now points at its new document.
            val other = docs.first { it.title == "other" }.id
            assertThat((items.filterIsInstance<LinkItem>().single().target as LinkTarget.Page).doc).isEqualTo(other)
        }
        assertThat(library.getKv("legacy:u-lens")).isEqualTo(lens.id.value)
    }

    @Test fun `second run skips notes that were imported before`() = runBlocking {
        note("a.notate", "u1")
        val first = importer.importAll(scan().notes)
        assertThat(first.imported).isEqualTo(1)
        val second = importer.importAll(scan().notes)
        assertThat(second.imported).isEqualTo(0); assertThat(second.alreadyImported).isEqualTo(1)
        assertThat(library.observeDocuments(DocQuery(scope = Scope.ALL)).first()).hasSize(1)
        assertThat(importer.existing("u1")).isNotNull()
    }

    @Test fun `a broken note is reported and leaves no files behind`() = runBlocking {
        note("good.notate", "u1")
        File(source, "bad.notate").also { f ->
            ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("manifest.bin")); z.write(byteArrayOf(0x0A, 0x7F, 0x01)); z.closeEntry() }
        }
        val entries = scan().notes + LegacyEntry("bad", emptyList(), 0, 0, null, { File(source, "bad.notate").inputStream() })
        val report = importer.importAll(entries)
        assertThat(report.imported).isEqualTo(1)
        assertThat(report.failures.map { it.name }).containsExactly("bad")
        assertThat(docsRoot.list()!!.size).isEqualTo(1)
    }

    @Test fun `stop request ends the run after the current note`() = runBlocking {
        note("a.notate", "u1"); note("b.notate", "u2"); note("c.notate", "u3")
        var seen = 0
        val report = importer.importAll(scan().notes, onProgress = { _, _, _ -> seen++ }, stopRequested = { seen >= 1 })
        assertThat(report.cancelled).isTrue(); assertThat(report.imported).isEqualTo(1)
    }

    @Test fun `local migrator imports projects and web documents once`() = runBlocking {
        val files = tmp.newFolder("filesDir")
        File(files, "projects/Course").mkdirs()
        val z = File(files, "projects/Course/n.notate")
        ZipOutputStream(z.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.bin")); out.write(ProtoBuf.encodeToByteArray(LegacyCanvasData.serializer(), LegacyCanvasData(uuid = "m1"))); out.closeEntry()
        }
        val web = File(files, "webdocs/w1").also { File(it, "content").mkdirs() }
        File(web, "meta.json").writeText("""{"id":"w1","title":"Book","kind":"HTML","pages":[{"href":"index.html","title":"Home"}]}""")
        File(web, "content/index.html").writeText("<html><body>hi</body></html>")
        val migrator = LegacyLocalMigrator(importer, { library }, clock)
        val r = migrator.migrate(files)!!
        assertThat(r.failures).isEmpty(); assertThat(r.imported).isEqualTo(2)
        val docs = library.observeDocuments(DocQuery(scope = Scope.ALL)).first()
        assertThat(docs.map { it.kind }).containsExactly(DocKind.NOTEBOOK, DocKind.WEB)
        assertThat(docs.first { it.kind == DocKind.NOTEBOOK }.folderId).isNotNull()
        assertThat(migrator.migrate(files)).isNull()
        assertThat(migrator.isDone()).isTrue()
    }
}
