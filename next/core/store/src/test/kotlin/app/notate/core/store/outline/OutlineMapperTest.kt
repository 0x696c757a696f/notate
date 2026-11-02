package app.notate.core.store.outline

import app.notate.core.model.PageId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutlineMapperTest {
    private val pages = (1L..5L).map { PageId(it) }

    @Test fun mapsTreeDepthFirstWithPageIds() {
        val tree = listOf(
            RawOutlineNode("Chapter 1", 0, 700f, listOf(RawOutlineNode("1.1", 1), RawOutlineNode("1.2", 2, 10f))),
            RawOutlineNode("Chapter 2", 3),
        )
        val rows = OutlineMapper.map(tree, pages)
        assertThat(rows.map { it.title }).containsExactly("Chapter 1", "1.1", "1.2", "Chapter 2").inOrder()
        assertThat(rows.map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()
        assertThat(rows.map { it.parentId }).containsExactly(null, 1L, 1L, null).inOrder()
        assertThat(rows.map { it.ord }).containsExactly(0, 0, 1, 1).inOrder()
        assertThat(rows.map { it.page }).containsExactly(PageId(1), PageId(2), PageId(3), PageId(4)).inOrder()
        assertThat(rows[0].yPt).isEqualTo(700f)
        assertThat(rows[1].yPt).isNull()
    }

    @Test fun skipsUnresolvableEntriesAndTheirSubtrees() {
        val tree = listOf(
            RawOutlineNode("Dangling", null, children = listOf(RawOutlineNode("Child", 0))),
            RawOutlineNode("Out of range", 9),
            RawOutlineNode("   ", 1),
            RawOutlineNode("Kept", 4),
        )
        val rows = OutlineMapper.map(tree, pages)
        assertThat(rows.map { it.title }).containsExactly("Kept")
        assertThat(rows.single().ord).isEqualTo(0)
    }

    @Test fun dropsNegativeOrNonFiniteY() {
        val rows = OutlineMapper.map(listOf(RawOutlineNode("a", 0, -1f), RawOutlineNode("b", 0, Float.NaN)), pages)
        assertThat(rows.map { it.yPt }).containsExactly(null, null)
    }

    @Test fun limitsDepthAndCount() {
        var deep = RawOutlineNode("leaf", 0)
        repeat(100) { deep = RawOutlineNode("n$it", 0, children = listOf(deep)) }
        assertThat(OutlineMapper.map(listOf(deep), pages)).hasSize(OutlineMapper.MAX_DEPTH)
        val wide = (0 until OutlineMapper.MAX_ENTRIES + 10).map { RawOutlineNode("t$it", 0) }
        assertThat(OutlineMapper.map(wide, pages)).hasSize(OutlineMapper.MAX_ENTRIES)
    }
}
