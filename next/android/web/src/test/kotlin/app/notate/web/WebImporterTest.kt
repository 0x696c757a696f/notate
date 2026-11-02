package app.notate.web

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.notate.core.doc.settings.Settings
import app.notate.core.model.Background
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.store.library.DocQuery
import app.notate.core.store.library.Scope
import app.notate.core.store.library.SqliteLibraryStore
import app.notate.core.testing.FakeClock
import app.notate.core.testing.InMemoryKeyValueStore
import app.notate.core.web.WebImportException
import app.notate.platform.sql.AndroidSqlDriverFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WebImporterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private val drivers = AndroidSqlDriverFactory()
    private lateinit var docsRoot: File
    private lateinit var library: SqliteLibraryStore
    private lateinit var importer: WebImporter

    @Before fun setUp() {
        docsRoot = tmp.newFolder("docs")
        val factory = SqliteDocStoreFactory(drivers, { Dispatchers.IO.limitedParallelism(1) }, clock)
        library = SqliteLibraryStore.open(drivers.open(File(tmp.root, "library.db"), false), Dispatchers.IO.limitedParallelism(1), clock)
        importer = WebImporter(
            context, tmp.newFolder("cache"), { File(docsRoot, it.value) }, factory, { library }, Settings(InMemoryKeyValueStore()),
            clock, Dispatchers.IO, app.notate.core.model.log.Logger.NONE, paneWidthDp = { 600f },
        )
    }

    private fun zip(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun file(name: String, bytes: ByteArray) = Uri.fromFile(File(tmp.root, name).also { it.writeBytes(bytes) })

    private val epubEntries = linkedMapOf(
        "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
        "OEBPS/content.opf" to """<package version="3.0"><metadata xmlns:dc="x"><dc:title>Physics</dc:title><dc:creator>Lin</dc:creator></metadata>
            <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
            <item id="n" href="n.xhtml" media-type="application/xhtml+xml"/></manifest>
            <spine><itemref idref="c1"/><itemref idref="n" linear="no"/><itemref idref="c2"/></spine></package>""",
        "OEBPS/nav.xhtml" to """<html xmlns:epub="x"><body><nav epub:type="toc"><ol>
            <li><a href="c1.xhtml">One</a><ol><li><a href="c1.xhtml#a">One A</a></li></ol></li><li><a href="c2.xhtml">Two</a></li></ol></nav></body></html>""",
        "OEBPS/c1.xhtml" to "<html><body>1</body></html>",
        "OEBPS/c2.xhtml" to "<html><body>2</body></html>",
        "OEBPS/n.xhtml" to "<html><body>n</body></html>",
    )

    @Test fun `epub import creates a web document with pages, outline and library row`() = runBlocking {
        val id = importer.importUri(file("physics.epub", zip(epubEntries)))
        val store = SqliteDocStoreFactory(drivers, { Dispatchers.IO.limitedParallelism(1) }, clock).open(File(docsRoot, id.value), readOnly = true)
        store.use {
            val meta = it.readMeta()
            assertThat(meta.kind).isEqualTo(DocKind.WEB)
            assertThat(meta.title).isEqualTo("Physics")
            assertThat(meta.web?.sourceHash).isNotEmpty()
            val pages = it.readPages()
            assertThat(pages.map { p -> (p.background as Background.Web).href }).containsExactly("OEBPS/c1.xhtml", "OEBPS/c2.xhtml").inOrder()
            assertThat(pages.all { p -> p.height == null && p.width == 450f }).isTrue()
            assertThat((pages[0].background as Background.Web).layoutWidthCss).isEqualTo(600f)
            val outline = it.readOutline()
            assertThat(outline.map { o -> o.title }).containsExactly("One", "One A", "Two").inOrder()
            assertThat(outline[1].parentId).isEqualTo(outline[0].id)
            assertThat(outline[1].anchor).isEqualTo("a")
            assertThat(outline[1].page).isEqualTo(pages[0].id)
            assertThat(outline[2].page).isEqualTo(pages[1].id)
            val web = File(File(docsRoot, id.value), "assets/web/OEBPS/c1.xhtml")
            assertThat(web.isFile).isTrue()
        }
        val books = library.observeDocuments(DocQuery(scope = Scope.BOOKS)).first()
        assertThat(books.map { it.id }).containsExactly(id)
        assertThat(books.single().pageCount).isEqualTo(2)
        Unit
    }

    @Test fun `importing the same file again returns the same document`() = runBlocking {
        val uri = file("a.epub", zip(epubEntries))
        val first = importer.importUri(uri)
        val second = importer.importUri(uri)
        assertThat(second).isEqualTo(first)
        assertThat(library.observeDocuments(DocQuery(scope = Scope.ALL)).first()).hasSize(1)
        // Lost key-value index: falls back to scanning the stored hashes.
        val hash = SqliteDocStoreFactory(drivers, { Dispatchers.IO.limitedParallelism(1) }, clock)
            .open(File(docsRoot, first.value), readOnly = true).use { it.readMeta().web!!.sourceHash!! }
        library.putKv(WebImporter.KV_PREFIX + hash, null)
        assertThat(importer.importUri(uri)).isEqualTo(first)
        assertThat(library.getKv(WebImporter.KV_PREFIX + hash)).isEqualTo(first.value)
    }

    @Test fun `html file import makes one page titled from the title element`() = runBlocking {
        val id = importer.importUri(file("note.html", "<html><head><title>My Page</title></head><body/></html>".toByteArray()))
        SqliteDocStoreFactory(drivers, { Dispatchers.IO.limitedParallelism(1) }, clock).open(File(docsRoot, id.value), true).use {
            assertThat(it.readMeta().title).isEqualTo("My Page")
            assertThat(it.readPages()).hasSize(1)
            assertThat(it.readOutline()).isEmpty()
        }
        Unit
    }

    @Test fun `zip bundle with an html entry imports`() = runBlocking {
        val id = importer.importUri(file("site.zip", zip(mapOf("site/index.html" to "<title>S</title>", "site/app.js" to "1"))))
        SqliteDocStoreFactory(drivers, { Dispatchers.IO.limitedParallelism(1) }, clock).open(File(docsRoot, id.value), true).use {
            assertThat((it.readPages().single().background as Background.Web).href).isEqualTo("site/index.html")
        }
        Unit
    }

    @Test fun `unsupported files fail cleanly and leave nothing behind`() {
        val e = assertThrows(WebImportException::class.java) { runBlocking { importer.importUri(file("x.bin", byteArrayOf(1, 2, 3, 4))) } }
        assertThat(e.reason).isEqualTo(WebImportException.Reason.NOT_SUPPORTED)
        val slip = assertThrows(WebImportException::class.java) { runBlocking { importer.importUri(file("s.zip", zip(mapOf("../x.html" to "<html/>")))) } }
        assertThat(slip.reason).isEqualTo(WebImportException.Reason.UNSAFE)
        assertThat(docsRoot.listFiles().orEmpty()).isEmpty()
        assertThat(File(tmp.root, "cache").listFiles().orEmpty()).isEmpty()
        assertThat(runBlocking { library.observeDocuments(DocQuery(scope = Scope.ALL)).first() }).isEmpty()
    }
}
