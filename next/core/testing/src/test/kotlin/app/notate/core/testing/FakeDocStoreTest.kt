package app.notate.core.testing

import app.notate.core.geometry.Rect
import app.notate.core.model.PageId
import app.notate.core.model.edit.Edit
import app.notate.core.store.AssetKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class FakeDocStoreTest {
    private fun store() = FakeDocStore(meta(), listOf(page(1), page(2, ord = 0.5)))

    @Test fun writeAppliesAndRecordsEdits() = runTest {
        val s = store()
        val st = stroke(1)
        val e = Edit.Items(PageId(1), emptyList(), listOf(st))
        s.write(listOf(e))
        assertThat(s.writes).containsExactly(listOf(e))
        assertThat(s.readItems(PageId(1))).containsExactly(st)
        s.write(listOf(e.inverse()))
        assertThat(s.readItems(PageId(1))).isEmpty()
    }

    @Test fun pagesOrderedByOrd() = runTest {
        assertThat(store().readPages().map { it.id.value }).containsExactly(2L, 1L).inOrder()
    }

    @Test fun regionFiltersItems() = runTest {
        val s = store()
        s.write(listOf(Edit.Items(PageId(1), emptyList(), listOf(stroke(1), stroke(2, points = floatArrayOf(500f, 500f, 510f, 510f))))))
        assertThat(s.readItems(PageId(1), Rect(0f, 0f, 100f, 100f)).map { it.id.value }).containsExactly(1L)
        assertThat(s.maxIds().item).isEqualTo(2L)
        assertThat(s.maxIds().order[PageId(1)]).isEqualTo(2L)
        assertThat(s.maxIds().page).isEqualTo(2L)
    }

    @Test fun failNextThrowsOnceWithoutApplying() = runTest {
        val s = store()
        s.failNext = IOException("disk full")
        val e = Edit.Items(PageId(1), emptyList(), listOf(stroke(1)))
        try { s.write(listOf(e)); error("expected failure") } catch (_: IOException) { }
        assertThat(s.readItems(PageId(1))).isEmpty()
        s.write(listOf(e))
        assertThat(s.readItems(PageId(1))).hasSize(1)
        assertThat(s.writes).hasSize(2)
    }

    @Test fun assetsDeduplicateByHash() = runTest {
        val s = store()
        val f = java.io.File.createTempFile("asset", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val a = s.putAsset(AssetKind.IMAGE, "image/png", f, "png")
        val b = s.putAsset(AssetKind.IMAGE, "image/png", f, "png")
        assertThat(a).isEqualTo(b)
        assertThat(a.value).hasLength(32)
        assertThat(s.assetFile(a)!!.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test fun fakeClockAdvances() {
        val c = FakeClock(5)
        c.advance(10)
        assertThat(c.now()).isEqualTo(15L)
    }
}
