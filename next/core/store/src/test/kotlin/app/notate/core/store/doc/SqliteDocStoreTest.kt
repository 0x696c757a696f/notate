package app.notate.core.store.doc

import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.Background
import app.notate.core.model.ImageItem
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.edit.Edit
import app.notate.core.store.AssetKind
import app.notate.core.store.DocStore
import app.notate.core.testing.FakeClock
import app.notate.core.testing.JdbcSqlDriverFactory
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SqliteDocStoreTest {
    private val clock = FakeClock(1000)
    private val factory = SqliteDocStoreFactory(JdbcSqlDriverFactory(), { Dispatchers.IO }, clock)
    private fun tmp(): File = Files.createTempDirectory("docstore").toFile()
    private fun newStore(dir: File = tmp()): DocStore = runBlocking { factory.create(dir, meta(), listOf(page(1), page(2))) }
    private val r = Rect(0f, 0f, 10f, 10f)

    @Test fun createThenReadMetaAndPages() = runBlocking<Unit> {
        val s = newStore()
        assertThat(s.readMeta()).isEqualTo(meta())
        assertThat(s.readPages().map { it.id.value }).containsExactly(1L, 2L).inOrder()
        assertThat(s.readPages()[0]).isEqualTo(page(1))
        assertThat(s.maxIds().page).isEqualTo(2L)
        assertThat(s.maxIds().item).isEqualTo(0L)
        s.close()
    }

    @Test fun thousandStrokesRoundTrip() = runBlocking<Unit> {
        val s = newStore()
        val strokes = (1L..1000L).map { stroke(it, points = floatArrayOf(it.toFloat(), it * 0.5f, it + 20f, it + 3.25f)) }
        s.write(listOf(Edit.Items(PageId(1), emptyList(), strokes)))
        val back = s.readItems(PageId(1))
        assertThat(back).hasSize(1000)
        assertThat(back.map { it.id.value }).isEqualTo((1L..1000L).toList())
        assertThat(back.map { it.bounds }).isEqualTo(strokes.map { it.bounds })
        for ((a, b) in strokes.zip(back)) {
            assertThat(b.order).isEqualTo(a.order)
            assertThat(b).isEqualTo(a)   // points are exact multiples of 1/64? 0.5 and .25 are
        }
        assertThat(s.maxIds().item).isEqualTo(1000L)
        assertThat(s.maxIds().order[PageId(1)]).isEqualTo(1000L)
        s.close()
    }

    @Test fun itemsEditBumpsInkRevAndRemovesItems() = runBlocking<Unit> {
        val s = newStore()
        val a = stroke(1); val b = stroke(2)
        s.write(listOf(Edit.Items(PageId(1), emptyList(), listOf(a, b))))
        assertThat(s.readPages()[0].inkRev).isEqualTo(1L)
        assertThat(s.readPages()[1].inkRev).isEqualTo(0L)
        s.write(listOf(Edit.Items(PageId(1), listOf(a), emptyList())))
        assertThat(s.readPages()[0].inkRev).isEqualTo(2L)
        assertThat(s.readItems(PageId(1)).map { it.id.value }).containsExactly(2L)
        s.close()
    }

    @Test fun writeUpdatesModifiedAt() = runBlocking<Unit> {
        val s = newStore()
        clock.now = 5000
        s.write(listOf(Edit.Items(PageId(1), emptyList(), listOf(stroke(1)))))
        assertThat(s.readMeta().modifiedAt).isEqualTo(5000L)
        assertThat(s.readPages()[0].modifiedAt).isEqualTo(5000L)
        val m = s.readMeta()
        s.write(listOf(Edit.SetMeta(m, m.copy(title = "Renamed"))))
        assertThat(s.readMeta().title).isEqualTo("Renamed")
        s.close()
    }

    @Test fun deletePageCascadesItems() = runBlocking<Unit> {
        val s = newStore()
        val items = listOf(stroke(1), stroke(2))
        s.write(listOf(Edit.Items(PageId(2), emptyList(), items)))
        val p2 = s.readPages()[1]
        s.write(listOf(Edit.DeletePage(p2, items)))
        assertThat(s.readPages().map { it.id.value }).containsExactly(1L)
        assertThat(s.readItems(PageId(2))).isEmpty()
        // inverse (undo) restores it
        s.write(listOf(Edit.DeletePage(p2, items).inverse()))
        assertThat(s.readItems(PageId(2))).hasSize(2)
        s.close()
    }

    @Test fun movePageChangesOrder() = runBlocking<Unit> {
        val s = newStore()
        s.write(listOf(Edit.InsertPage(page(3, ord = 3.0), emptyList())))
        s.write(listOf(Edit.MovePage(PageId(3), 3.0, 0.5)))
        assertThat(s.readPages().map { it.id.value }).containsExactly(3L, 1L, 2L).inOrder()
        s.close()
    }

    @Test fun setPageChangesBackgroundAndBookmark() = runBlocking<Unit> {
        val s = newStore()
        val before = s.readPages()[0]
        val after = before.copy(background = Background.None, bookmarked = true, height = null)
        s.write(listOf(Edit.SetPage(before, after)))
        val read = s.readPages()[0]
        assertThat(read.background).isEqualTo(Background.None)
        assertThat(read.bookmarked).isTrue()
        assertThat(read.height).isNull()
        s.close()
    }

    @Test fun regionQueryReturnsOnlyIntersecting() = runBlocking<Unit> {
        val s = newStore()
        val near = stroke(1, points = floatArrayOf(10f, 10f, 20f, 20f))
        val far = stroke(2, points = floatArrayOf(500f, 500f, 520f, 520f))
        s.write(listOf(Edit.Items(PageId(1), emptyList(), listOf(near, far))))
        assertThat(s.readItems(PageId(1), Rect(0f, 0f, 100f, 100f)).map { it.id.value }).containsExactly(1L)
        assertThat(s.readItems(PageId(1), Rect(490f, 490f, 600f, 600f)).map { it.id.value }).containsExactly(2L)
        assertThat(s.readItems(PageId(1), Rect(100f, 100f, 400f, 400f))).isEmpty()
        assertThat(s.readItems(PageId(1), null)).hasSize(2)
        s.close()
    }

    @Test fun itemsAreOrderedByLayerThenOrder() = runBlocking<Unit> {
        val s = newStore()
        val img = ImageItem(ItemId(10), 1, AssetId("a"), r, 0f, 1f, false, r)
        s.write(listOf(Edit.Items(PageId(1), emptyList(), listOf(stroke(1, order = 5), stroke(2, order = 3), img))))
        assertThat(s.readItems(PageId(1)).map { it.id.value }).containsExactly(10L, 2L, 1L).inOrder()
        s.close()
    }

    @Test fun putAssetDeduplicatesByContent() = runBlocking<Unit> {
        val s = newStore()
        val f1 = File.createTempFile("aaa", ".png").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        val f2 = File.createTempFile("bbb", ".png").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        val f3 = File.createTempFile("ccc", ".png").apply { writeBytes(ByteArray(10)) }
        val a = s.putAsset(AssetKind.IMAGE, "image/png", f1, "png")
        val b = s.putAsset(AssetKind.IMAGE, "image/png", f2, "png")
        val c = s.putAsset(AssetKind.IMAGE, "image/png", f3, ".png")
        assertThat(a).isEqualTo(b)
        assertThat(c).isNotEqualTo(a)
        assertThat(a.value).hasLength(32)
        assertThat(s.assetFile(a)!!.readBytes()).isEqualTo(f1.readBytes())
        assertThat(File(s.dir, "assets").listFiles()!!.map { it.name }).containsExactly("${a.value}.png", "${c.value}.png")
        assertThat(s.assetFile(AssetId("nope"))).isNull()
        s.close()
    }

    @Test fun webAssetCopiesDirectory() = runBlocking<Unit> {
        val s = newStore()
        val src = Files.createTempDirectory("bundle").toFile()
        File(src, "OEBPS").mkdirs()
        File(src, "OEBPS/ch1.xhtml").writeText("<html/>")
        File(src, "index.html").writeText("hi")
        val id = s.putAsset(AssetKind.WEB, "application/xhtml+xml", src, "")
        assertThat(File(s.dir, "assets/web/OEBPS/ch1.xhtml").readText()).isEqualTo("<html/>")
        assertThat(s.putAsset(AssetKind.WEB, "application/xhtml+xml", src, "")).isEqualTo(id)
        File(src, "index.html").writeText("changed")
        assertThat(s.putAsset(AssetKind.WEB, "application/xhtml+xml", src, "")).isNotEqualTo(id)
        assertThat(s.assetFile(id)!!.isDirectory).isTrue()
        s.close()
    }

    @Test fun vacuumRemovesUnreferencedAssets() = runBlocking<Unit> {
        val s = newStore()
        fun file(n: Int) = File.createTempFile("xxx", ".bin").apply { writeBytes(ByteArray(50) { (it + n).toByte() }) }
        val used = s.putAsset(AssetKind.IMAGE, "image/png", file(1), "png")
        val unused = s.putAsset(AssetKind.IMAGE, "image/png", file(2), "png")
        val pdfUsed = s.putAsset(AssetKind.PDF, "application/pdf", file(3), "pdf")
        val pdfUnused = s.putAsset(AssetKind.PDF, "application/pdf", file(4), "pdf")
        s.write(listOf(Edit.Items(PageId(1), emptyList(), listOf(ImageItem(ItemId(5), 1, used, r, 0f, 1f, false, r)))))
        val p = s.readPages()[1]
        s.write(listOf(Edit.SetPage(p, p.copy(background = Background.Pdf(pdfUsed, 0)))))
        s.vacuumAssets()
        assertThat(s.assetFile(used)!!.exists()).isTrue()
        assertThat(s.assetFile(pdfUsed)!!.exists()).isTrue()
        assertThat(s.assetFile(unused)).isNull()
        assertThat(s.assetFile(pdfUnused)).isNull()
        assertThat(File(s.dir, "assets").listFiles()!!).hasLength(2)
        s.close()
    }

    @Test fun reopenKeepsData() = runBlocking<Unit> {
        val dir = tmp()
        val s = newStore(dir)
        s.write(listOf(Edit.Items(PageId(1), emptyList(), listOf(stroke(1), stroke(2)))))
        s.close()
        val again = factory.open(dir)
        assertThat(again.readItems(PageId(1))).hasSize(2)
        assertThat(again.readMeta().id).isEqualTo(meta().id)
        again.close()
        val ro = factory.open(dir, readOnly = true)
        assertThat(ro.readPages()).hasSize(2)
        assertThrows(IllegalStateException::class.java) { runBlocking { ro.write(listOf(Edit.Items(PageId(1), listOf(stroke(1)), emptyList()))) } }
        ro.close()
    }

    @Test fun failedWriteRollsBackEverything() = runBlocking<Unit> {
        val s = newStore()
        s.write(listOf(Edit.Items(PageId(1), emptyList(), listOf(stroke(1)))))
        val modifiedBefore = s.readMeta().modifiedAt
        clock.now = 9999
        val bad = listOf(
            Edit.Items(PageId(1), listOf(stroke(1)), listOf(stroke(2), stroke(3))),
            Edit.Items(PageId(777), emptyList(), listOf(stroke(4))),   // page 777 does not exist -> FK violation
        )
        assertThrows(Exception::class.java) { runBlocking { s.write(bad) } }
        assertThat(s.readItems(PageId(1)).map { it.id.value }).containsExactly(1L)
        assertThat(s.readPages()[0].inkRev).isEqualTo(1L)
        assertThat(s.readMeta().modifiedAt).isEqualTo(modifiedBefore)
        // the store is still usable afterwards
        s.write(listOf(Edit.Items(PageId(1), emptyList(), listOf(stroke(5)))))
        assertThat(s.readItems(PageId(1))).hasSize(2)
        s.close()
    }

    @Test fun outlineRoundTrip() = runBlocking<Unit> {
        val s = newStore()
        val entries = listOf(
            app.notate.core.store.OutlineEntry(1, null, 0, "Intro", PageId(1), 12f),
            app.notate.core.store.OutlineEntry(2, 1, 1, "Sub", null, null, "#sec"),
        )
        s.writeOutline(entries)
        assertThat(s.readOutline()).isEqualTo(entries)
        s.writeOutline(emptyList())
        assertThat(s.readOutline()).isEmpty()
        s.close()
    }

    @Test fun createTwiceFails() {
        val dir = tmp()
        newStore(dir).close()
        assertThrows(IllegalStateException::class.java) { runBlocking { factory.create(dir, meta(), emptyList()) } }
    }

    @Test fun boundsHelperSanity() {
        val st = stroke(1)
        assertThat(st.bounds).isEqualTo(ItemBounds.strokeBounds(st.points, st.style))
    }
}
