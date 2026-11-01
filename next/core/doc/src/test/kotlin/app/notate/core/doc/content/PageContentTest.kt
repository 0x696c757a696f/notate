package app.notate.core.doc.content

import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.PenType
import app.notate.core.testing.rect
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageContentTest {
    private val p = PageId(1)
    private fun ids(l: List<app.notate.core.model.Item>) = l.map { it.id.value }

    @Test fun singleItemQueryHitAndMiss() {
        val c = PageContent.of(p, listOf(stroke(1, points = floatArrayOf(10f, 10f, 50f, 50f))), 1)
        assertThat(ids(c.query(rect(0f, 0f, 100f, 100f)))).containsExactly(1L)
        assertThat(c.query(rect(200f, 200f, 300f, 300f))).isEmpty()
        assertThat(c[ItemId(1)]).isNotNull()
    }

    @Test fun itemSpanningCellsIsReturnedOnce() {
        val s = stroke(1, points = floatArrayOf(100f, 100f, 1500f, 100f))
        val c = PageContent.of(p, listOf(s), 1)
        assertThat(ids(c.query(rect(0f, 0f, 2000f, 200f)))).containsExactly(1L)
        assertThat(c.cells.size).isGreaterThan(2)
    }

    @Test fun hugeItemGoesToLargeList() {
        val s = stroke(1, points = floatArrayOf(0f, 0f, 5000f, 5000f))
        val c = PageContent.of(p, listOf(s), 1)
        assertThat(c.large).hasSize(1)
        assertThat(c.cells).isEmpty()
        assertThat(ids(c.query(rect(2000f, 2000f, 2100f, 2100f)))).containsExactly(1L)
    }

    @Test fun withRemovesAndAdds() {
        val a = stroke(1, points = floatArrayOf(10f, 10f, 20f, 20f))
        val b = stroke(2, points = floatArrayOf(10f, 10f, 30f, 30f))
        val c = PageContent.of(p, listOf(a), 1).with(listOf(a), listOf(b), 2)
        assertThat(c.rev).isEqualTo(2)
        assertThat(ids(c.all().toList())).containsExactly(2L)
        assertThat(ids(c.query(rect(0f, 0f, 100f, 100f)))).containsExactly(2L)
        val empty = c.with(listOf(b), emptyList(), 3)
        assertThat(empty.cells).isEmpty()
    }

    @Test fun addedIdReplacesExisting() {
        val a = stroke(1, points = floatArrayOf(10f, 10f, 20f, 20f))
        val moved = stroke(1, points = floatArrayOf(900f, 900f, 910f, 910f))
        val c = PageContent.of(p, listOf(a), 1).with(emptyList(), listOf(moved), 2)
        assertThat(c.query(rect(0f, 0f, 100f, 100f))).isEmpty()
        assertThat(ids(c.query(rect(800f, 800f, 1000f, 1000f)))).containsExactly(1L)
    }

    @Test fun sortedByLayerThenOrder() {
        val hl = stroke(1, order = 9, pen = PenType.HIGHLIGHTER)
        val x = stroke(2, order = 5)
        val y = stroke(3, order = 2)
        val c = PageContent.of(p, listOf(x, hl, y), 1)
        assertThat(ids(c.query(rect(0f, 0f, 200f, 200f)))).containsExactly(1L, 3L, 2L).inOrder()
    }

    @Test fun untouchedCellsAreShared() {
        val far = stroke(1, points = floatArrayOf(2000f, 2000f, 2010f, 2010f))
        val near = stroke(2, points = floatArrayOf(10f, 10f, 20f, 20f))
        val c1 = PageContent.of(p, listOf(far), 1)
        val c2 = c1.with(emptyList(), listOf(near), 2)
        val key = cellKey(3, 3)
        assertThat(c2.cells[key]).isSameInstanceAs(c1.cells[key])
        assertThat(c1.cells.containsKey(cellKey(0, 0))).isFalse()
    }

    @Test fun mergeKeepsMemoryVersionAndExtendsRegion() {
        val mem = stroke(1, points = floatArrayOf(10f, 10f, 20f, 20f))
        val stale = stroke(1, points = floatArrayOf(10f, 10f, 99f, 99f))
        val other = stroke(2, points = floatArrayOf(700f, 10f, 710f, 20f))
        val c = PageContent.of(p, listOf(mem), 5, rect(0f, 0f, 512f, 512f))
            .merge(rect(512f, 0f, 1024f, 512f), listOf(stale, other))
        assertThat(c[ItemId(1)]).isSameInstanceAs(mem)
        assertThat(c[ItemId(2)]).isNotNull()
        assertThat(c.loadedRegion).isEqualTo(rect(0f, 0f, 1024f, 512f))
        assertThat(c.rev).isEqualTo(5)
    }

    @Test fun benchmarkTenThousandItems() {
        val items = (1L..10_000L).map {
            val x = (it % 100) * 60f; val y = (it / 100) * 60f
            stroke(it, points = floatArrayOf(x, y, x + 30f, y + 30f))
        }
        val c = PageContent.of(p, items, 1)
        val t0 = System.nanoTime()
        var n = 0
        repeat(100) { n += c.query(rect(1000f, 1000f, 1512f, 1512f)).size }
        println("query 512pt over 10k items: ${(System.nanoTime() - t0) / 100 / 1000} us/query, ${n / 100} hits")
        assertThat(n).isGreaterThan(0)
    }
}
