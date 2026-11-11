package app.notate.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.notate.core.model.Background
import app.notate.core.model.DocKind
import app.notate.core.model.PageId
import app.notate.core.model.log.Logger
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.store.library.SqliteLibraryStore
import app.notate.core.store.outline.RawOutlineNode
import app.notate.core.testing.FakeClock
import app.notate.platform.sql.AndroidSqlDriverFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PdfImporterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private val drivers = AndroidSqlDriverFactory()
    private lateinit var docsRoot: File
    private lateinit var library: SqliteLibraryStore
    private lateinit var factory: SqliteDocStoreFactory
    private lateinit var pdf: File

    private class FakeSource(val sizes: List<Pair<Float, Float>>) : PdfPageSource {
        var closed = false
        override val pageCount get() = sizes.size
        override fun sizeOf(index: Int) = sizes[index]
        override fun renderCover(index: Int, out: Bitmap) = Unit
        override fun close() { closed = true }
    }

    private var sizes = listOf(612f to 792f, 792f to 612f, 595f to 842f)
    private var outline: () -> List<RawOutlineNode> = { emptyList() }
    private var openError: PdfOpenException? = null

    @Before fun setUp() {
        docsRoot = tmp.newFolder("docs")
        factory = SqliteDocStoreFactory(drivers, { Dispatchers.IO.limitedParallelism(1) }, clock)
        library = SqliteLibraryStore.open(drivers.open(File(tmp.root, "library.db"), false), Dispatchers.IO.limitedParallelism(1), clock)
        pdf = tmp.newFile("Lecture 1.pdf").also { it.writeText("%PDF-1.4 fake") }
    }

    private fun importer() = PdfImporter(
        context, tmp.newFolder(), { File(docsRoot, it.value) }, factory, { library }, clock, Dispatchers.IO, Logger.NONE,
        sources = { openError?.let { e -> throw e }; FakeSource(sizes) },
        outlines = { outline() },
    )

    @Test fun importsOnePagePerPdfPage() = runBlocking {
        val id = importer().importFile(pdf, "Lecture 1")
        factory.open(File(docsRoot, id.value), readOnly = true).use { store ->
            val meta = store.readMeta()
            assertThat(meta.kind).isEqualTo(DocKind.PDF)
            assertThat(meta.title).isEqualTo("Lecture 1")
            assertThat(meta.newPageTemplate.landscape).isFalse()
            val pages = store.readPages()
            assertThat(pages.map { it.width to it.height }).containsExactly(612f to 792f, 792f to 612f, 595f to 842f).inOrder()
            val backgrounds = pages.map { it.background as Background.Pdf }
            assertThat(backgrounds.map { it.pageIndex }).containsExactly(0, 1, 2).inOrder()
            assertThat(backgrounds.map { it.asset }.toSet()).hasSize(1)
            val asset = store.assetFile(backgrounds[0].asset)
            assertThat(asset).isNotNull()
            assertThat(asset!!.readText()).isEqualTo("%PDF-1.4 fake")
        }
        val entry = library.get(id)!!
        assertThat(entry.kind).isEqualTo(DocKind.PDF)
        assertThat(entry.pageCount).isEqualTo(3)
    }

    @Test fun landscapeFirstPageMakesALandscapeNewPageTemplate() = runBlocking {
        sizes = listOf(792f to 612f)
        val id = importer().importFile(pdf, "Slides")
        factory.open(File(docsRoot, id.value), readOnly = true).use { assertThat(it.readMeta().newPageTemplate.landscape).isTrue() }
    }

    @Test fun writesTheOutlineWithPageIds() = runBlocking {
        outline = { listOf(RawOutlineNode("Intro", 0, children = listOf(RawOutlineNode("Setup", 2, 100f))), RawOutlineNode("Broken", null)) }
        val id = importer().importFile(pdf, "Book")
        factory.open(File(docsRoot, id.value), readOnly = true).use { store ->
            val rows = store.readOutline()
            assertThat(rows.map { it.title }).containsExactly("Intro", "Setup").inOrder()
            assertThat(rows[1].page).isEqualTo(PageId(3))
            assertThat(rows[1].parentId).isEqualTo(rows[0].id)
            assertThat(rows[1].yPt).isEqualTo(100f)
        }
    }

    @Test fun unreadableOutlineDoesNotFailTheImport() = runBlocking {
        outline = { error("broken outline") }
        val id = importer().importFile(pdf, "Book")
        factory.open(File(docsRoot, id.value), readOnly = true).use { assertThat(it.readOutline()).isEmpty() }
    }

    @Test fun passwordProtectedFileIsRefusedWithoutLeavingADocument() {
        openError = PdfOpenException(PdfOpenException.Reason.PASSWORD_PROTECTED, "locked")
        val e = assertThrows(PdfImportException::class.java) { runBlocking { importer().importFile(pdf, "Locked") } }
        assertThat(e.reason).isEqualTo(PdfImportException.Reason.PASSWORD_PROTECTED)
        assertThat(docsRoot.listFiles()).isEmpty()
    }

    @Test fun notAPdfIsUnreadable() {
        openError = PdfOpenException(PdfOpenException.Reason.NOT_A_PDF, "nope")
        val e = assertThrows(PdfImportException::class.java) { runBlocking { importer().importFile(pdf, "x") } }
        assertThat(e.reason).isEqualTo(PdfImportException.Reason.UNREADABLE)
    }

    @Test fun cancellingRemovesTheHalfMadeDocument() {
        assertThrows(CancellationException::class.java) {
            runBlocking { importer().importFile(pdf, "Cancelled") { done, _ -> if (done == 2) throw CancellationException("cancel") } }
        }
        assertThat(docsRoot.listFiles()).isEmpty()
    }

    @Test fun importsFromAFileUriAndNamesTheDocumentAfterTheFile() = runBlocking {
        val id = importer().importUri(Uri.fromFile(pdf))
        factory.open(File(docsRoot, id.value), readOnly = true).use { assertThat(it.readMeta().title).isEqualTo("Lecture 1") }
    }
}
