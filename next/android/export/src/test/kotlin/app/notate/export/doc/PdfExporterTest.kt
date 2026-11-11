package app.notate.export.doc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.doc.registry.DocumentOpener
import app.notate.core.doc.registry.DocumentRegistry
import app.notate.core.doc.registry.Opened
import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.Background
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.InkStyle
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.Item
import app.notate.core.model.LinkItem
import app.notate.core.model.LinkTarget
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PaperPattern
import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.ShapeKind
import app.notate.core.model.TextAlign
import app.notate.core.model.TextBox
import app.notate.core.model.edit.Edit
import app.notate.core.store.AssetKind
import app.notate.core.store.MaxIds
import app.notate.core.store.OutlineEntry
import app.notate.core.testing.FakeClock
import app.notate.core.testing.FakeDocStore
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PdfExporterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val id = DocId("01TESTDOC000000000000000A")
    private val exporter = PdfExporter(Dispatchers.Unconfined)

    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>()) }

    private fun handle(store: FakeDocStore): DocumentHandle = runBlocking {
        val registry = DocumentRegistry(
            object : DocumentOpener {
                override suspend fun open(id: DocId) = Opened(store, store.readMeta(), store.readPages(), MaxIds(1, 100, emptyMap()))
            },
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), FakeClock(), Dispatchers.Unconfined, Dispatchers.Unconfined,
        )
        registry.acquire(id)
    }

    private fun ops(page: PDPage): List<Any> = PDFStreamParser(page).also { it.parse() }.tokens

    private fun operators(page: PDPage): List<String> = ops(page).filterIsInstance<Operator>().map { it.name }

    private fun add(store: FakeDocStore, page: Long, vararg items: Item) = runBlocking {
        store.write(listOf(Edit.Items(PageId(page), emptyList(), items.toList())))
    }

    private fun text(order: Long, s: String): TextBox {
        val frame = Rect(50f, 300f, 300f, 340f)
        return TextBox(ItemId(order), order, frame, 0f, s, 14f, 0xFF000000.toInt(), false, TextAlign.LEFT, ItemBounds.frameBounds(frame, 0f))
    }

    private fun link(order: Long, target: LinkTarget): LinkItem {
        val frame = Rect(400f, 50f, 440f, 70f)
        return LinkItem(ItemId(order), order, frame, "go", target, ItemBounds.frameBounds(frame, 0f))
    }

    private fun notebook(): FakeDocStore {
        val store = FakeDocStore(meta(id.value, DocKind.NOTEBOOK, "My notes"), listOf(page(1), page(2), page(3)))
        val rectParams = floatArrayOf(100f, 400f, 200f, 400f, 200f, 480f, 100f, 480f)
        val style = InkStyle(PenType.BALLPOINT, 0xFF000000.toInt(), 1.2f)
        add(
            store, 1,
            stroke(1), stroke(2, pen = PenType.FOUNTAIN), stroke(3, pen = PenType.HIGHLIGHTER, color = 0x80FFEB3B.toInt(), widthPt = 10f),
            Shape(ItemId(4), 4, style, ShapeKind.RECT, rectParams, 0xFFE0E0E0.toInt(), ItemBounds.shapeBounds(ShapeKind.RECT, rectParams, style)),
            text(5, "Hello world"), text(6, "你好，世界"),
            link(7, LinkTarget.Page(id, PageId(2))), link(8, LinkTarget.Url("https://example.com")),
        )
        add(store, 2, stroke(9))
        runBlocking {
            store.writeOutline(listOf(
                OutlineEntry(1, null, 0, "One", PageId(1), null),
                OutlineEntry(2, null, 1, "Two", PageId(2), 100f),
                OutlineEntry(3, null, 2, "Three", PageId(3), null),
            ))
        }
        return store
    }

    private fun exportAll(): File = runBlocking {
        val out = File(tmp.root, "out.pdf")
        exporter.export(handle(notebook()), ExportOptions(), out) { _, _ -> }
        out
    }

    private fun withDoc(block: (PDDocument) -> Unit) { PDDocument.load(exportAll()).use(block) }

    @Test fun pageCountAndSize() {
        withDoc { doc ->
        assertThat(doc.numberOfPages).isEqualTo(3)
        assertThat(doc.getPage(0).mediaBox.width).isWithin(0.01f).of(595.28f)
        }
    }

    @Test fun inkIsFilledPaths() { withDoc { doc -> assertThat(operators(doc.getPage(0))).contains("f") } }
    @Test fun templateIsStrokedLines() { withDoc { doc -> assertThat(operators(doc.getPage(0))).contains("S") } }
    @Test fun latinTextStaysText() { withDoc { doc -> assertThat(operators(doc.getPage(0))).contains("Tj") } }
    @Test fun cjkTextBecomesAPicture() { withDoc { doc -> assertThat(operators(doc.getPage(0))).contains("Do") } }

    @Test fun linksBecomeAnnotations() {
        withDoc { doc ->
        val links = doc.getPage(0).annotations.filterIsInstance<PDAnnotationLink>()
        assertThat(links).hasSize(2)
        assertThat(links.mapNotNull { (it.action as? PDActionURI)?.uri }).containsExactly("https://example.com")
        }
    }

    @Test fun outlineAndInfo() {
        withDoc { doc ->
        assertThat(doc.documentCatalog.documentOutline.children().count()).isEqualTo(3)
        assertThat(doc.documentInformation.title).isEqualTo("My notes")
        assertThat(doc.documentInformation.creator).isEqualTo("Notate")
        }
    }

    @Test fun pageRangeLimitsPagesLinksAndOutline() = runBlocking {
        val h = handle(notebook())
        val out = File(tmp.root, "range.pdf")
        exporter.export(h, ExportOptions(range = PageSelection.Pages("1-2")), out) { _, _ -> }
        PDDocument.load(out).use { doc ->
            assertThat(doc.numberOfPages).isEqualTo(2)
            assertThat(doc.documentCatalog.documentOutline.children().count()).isEqualTo(2)
        }
        val single = File(tmp.root, "single.pdf")
        exporter.export(h, ExportOptions(range = PageSelection.Current(PageId(3))), single) { _, _ -> }
        PDDocument.load(single).use { assertThat(it.numberOfPages).isEqualTo(1) }
    }

    @Test fun templateCanBeLeftOut() = runBlocking {
        val h = handle(notebook())
        val out = File(tmp.root, "plain.pdf")
        exporter.export(h, ExportOptions(includeTemplate = false, range = PageSelection.Current(PageId(3))), out) { _, _ -> }
        PDDocument.load(out).use { assertThat(operators(it.getPage(0))).doesNotContain("S") }
    }

    @Test fun invalidRangeIsReported() = runBlocking {
        val h = handle(notebook())
        val e = runCatching { exporter.export(h, ExportOptions(range = PageSelection.Pages("x")), File(tmp.root, "x.pdf")) { _, _ -> } }.exceptionOrNull()
        assertThat(e).isInstanceOf(ExportRangeException::class.java)
        assertThat(e!!.message).isEqualTo("Use page numbers like 1-3, 5")
        assertThat(File(tmp.root, "x.pdf").exists()).isFalse()
    }

    @Test fun infiniteCanvasIsCroppedToItsContent() = runBlocking {
        val store = FakeDocStore(meta(id.value, DocKind.NOTEBOOK, "Board"), listOf(page(1, height = null)))
        val s = stroke(1, points = floatArrayOf(1000f, 1000f, 1200f, 1100f))
        add(store, 1, s)
        val out = File(tmp.root, "board.pdf")
        exporter.export(handle(store), ExportOptions(), out) { _, _ -> }
        PDDocument.load(out).use { doc ->
            val box = doc.getPage(0).mediaBox
            assertWithMessage("box " + box).that(box.width).isWithin(0.5f).of(s.bounds.width + 72f)
            assertWithMessage("box " + box).that(box.height).isWithin(0.5f).of(s.bounds.height + 72f)
        }
    }

    @Test fun sourcePdfPageIsImportedAndInkAppendedWithTheRotationMatrix() = runBlocking {
        val src = File(tmp.root, "src.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage(PDRectangle(200f, 400f)).also { it.rotation = 90 })
            d.save(src)
        }
        val store = FakeDocStore(meta(id.value, DocKind.PDF, "Book"), emptyList())
        val asset: AssetId = store.putAsset(AssetKind.PDF, "application/pdf", src, "pdf")
        val info = PageInfo(PageId(1), 1.0, 400f, 200f, Background.Pdf(asset, 0), 0, 0)
        runBlocking { store.write(listOf(Edit.InsertPage(info, listOf(stroke(1))))) }
        val out = File(tmp.root, "annotated.pdf")
        exporter.export(handle(store), ExportOptions(), out) { _, _ -> }
        PDDocument.load(out).use { doc ->
            val p = doc.getPage(0)
            assertThat(p.rotation).isEqualTo(90)
            val cm = ops(p).let { tokens -> tokens.indexOfFirst { it is Operator && it.name == "cm" }.let { tokens.subList(it - 6, it) } }
            assertThat(cm.map { (it as COSNumber).floatValue() }).containsExactly(0f, 1f, 1f, 0f, 0f, 0f).inOrder()
            assertThat(operators(p)).contains("f")
        }
    }

    @Test fun pageMapsPlaceDisplayedPointsOnTheUnrotatedPage() {
        fun map(rot: Int) = PdfExporter.sourcePageMap(PDPage(PDRectangle(200f, 400f)).also { it.rotation = rot }, Rect(0f, 0f, 1f, 1f))
        // Page 200 x 400 as stored. Displayed point (x, y), top-left origin → user space (u, v), bottom-left origin.
        map(0).let { assertThat(it.u(30f, 40f)).isEqualTo(30f); assertThat(it.v(30f, 40f)).isEqualTo(360f) }
        map(90).let { assertThat(it.u(30f, 40f)).isEqualTo(40f); assertThat(it.v(30f, 40f)).isEqualTo(30f) }
        map(180).let { assertThat(it.u(30f, 40f)).isEqualTo(170f); assertThat(it.v(30f, 40f)).isEqualTo(40f) }
        map(270).let { assertThat(it.u(30f, 40f)).isEqualTo(160f); assertThat(it.v(30f, 40f)).isEqualTo(370f) }
    }
}
