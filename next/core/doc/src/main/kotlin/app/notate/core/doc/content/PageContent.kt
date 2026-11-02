package app.notate.core.doc.content

import app.notate.core.geometry.Rect
import app.notate.core.model.Item
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.math.floor

/** Side length of an index cell in pt. */
const val CELL = 512f

/** Items covering more cells than this are kept in the `large` list instead of being copied into every cell. */
private const val MAX_CELLS_PER_ITEM = 16

/** Packs cell coordinates into one map key. */
fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

private val ITEM_ORDER: Comparator<Item> = compareBy<Item>({ it.layer.code }, { it.order })

/**
 * Immutable snapshot of one page's items, indexed by 512 pt cells. Safe to read from any thread. Updates ([with], [merge])
 * copy only the touched cells and share everything else with the previous snapshot.
 */
class PageContent internal constructor(
    val page: PageId,
    val rev: Long,
    internal val cells: PersistentMap<Long, PersistentList<Item>>,
    internal val large: PersistentList<Item>,
    internal val byId: PersistentMap<ItemId, Item>,
    /** Whiteboard pages: region whose items are loaded. Fixed-size pages: null (everything is loaded). */
    val loadedRegion: Rect?,
) {
    val size: Int get() = byId.size

    /** Items whose bounds intersect [r], without duplicates, sorted by (layer, order). */
    fun query(r: Rect): List<Item> {
        if (r.isEmpty) return emptyList()
        val seen = HashSet<ItemId>()
        val out = ArrayList<Item>()
        fun take(item: Item) { if (item.bounds.intersects(r) && seen.add(item.id)) out.add(item) }
        val (cx0, cx1) = cellRange(r.l, r.r)
        val (cy0, cy1) = cellRange(r.t, r.b)
        for (cx in cx0..cx1) for (cy in cy0..cy1) cells[cellKey(cx, cy)]?.forEach(::take)
        large.forEach(::take)
        out.sortWith(ITEM_ORDER)
        return out
    }

    operator fun get(id: ItemId): Item? = byId[id]

    fun all(): Sequence<Item> = byId.values.asSequence()

    /** Snapshot with [removed] taken out and [added] put in (an added id replaces any existing item with that id). */
    fun with(removed: Collection<Item>, added: Collection<Item>, newRev: Long): PageContent =
        update(removed, added, newRev, loadedRegion)

    /**
     * Adds items loaded for [region] (whiteboard pages). Items whose id already exists keep their in-memory version, as do
     * ids in [skipIds]. [region] null means everything is now loaded. Rev is unchanged.
     */
    fun merge(region: Rect?, items: Collection<Item>, skipIds: Set<Long> = emptySet()): PageContent {
        val fresh = items.filter { byId[it.id] == null && it.id.value !in skipIds }
        val newRegion = when {
            region == null || loadedRegion == null -> null
            else -> loadedRegion.union(region)
        }
        return update(emptyList(), fresh, rev, newRegion)
    }

    private fun update(removed: Collection<Item>, added: Collection<Item>, newRev: Long, region: Rect?): PageContent {
        if (removed.isEmpty() && added.isEmpty()) return PageContent(page, newRev, cells, large, byId, region)
        // Resolve what is actually leaving: the stored version (bounds may differ from the caller's copy).
        val leaving = LinkedHashMap<ItemId, Item>()
        for (r in removed) byId[r.id]?.let { leaving[r.id] = it }
        for (a in added) byId[a.id]?.let { leaving[a.id] = it }
        val newById = byId.builder()
        leaving.keys.forEach { newById.remove(it) }
        added.forEach { newById[it.id] = it }

        val dropByCell = HashMap<Long, MutableSet<ItemId>>()
        val addByCell = HashMap<Long, MutableList<Item>>()
        val dropLarge = HashSet<ItemId>()
        val addLarge = ArrayList<Item>()
        for (item in leaving.values) {
            if (isLarge(item)) dropLarge.add(item.id) else forEachCell(item) { dropByCell.getOrPut(it) { HashSet() }.add(item.id) }
        }
        for (item in added) {
            if (isLarge(item)) addLarge.add(item) else forEachCell(item) { addByCell.getOrPut(it) { ArrayList() }.add(item) }
        }
        val newCells = cells.builder()
        for (key in dropByCell.keys + addByCell.keys) {
            val ids = dropByCell[key]
            var list = cells[key] ?: persistentListOf()
            if (ids != null) list = list.removeAll { it.id in ids }
            addByCell[key]?.let { list = list.addAll(it) }
            if (list.isEmpty()) newCells.remove(key) else newCells[key] = list
        }
        var newLarge = large
        if (dropLarge.isNotEmpty()) newLarge = newLarge.removeAll { it.id in dropLarge }
        if (addLarge.isNotEmpty()) newLarge = newLarge.addAll(addLarge)
        return PageContent(page, newRev, newCells.build(), newLarge, newById.build(), region)
    }

    companion object {
        fun empty(page: PageId): PageContent = PageContent(page, 0, persistentMapOf(), persistentListOf(), persistentMapOf(), null)

        fun of(page: PageId, items: Collection<Item>, rev: Long, loadedRegion: Rect? = null): PageContent =
            PageContent(page, rev, persistentMapOf(), persistentListOf(), persistentMapOf(), loadedRegion)
                .update(emptyList(), items, rev, loadedRegion)

        private fun cell(v: Float): Int = floor(v / CELL).toInt()

        /** Inclusive cell index range covering [lo, hi]; an upper edge exactly on a boundary does not spill into the next cell. */
        private fun cellRange(lo: Float, hi: Float): Pair<Int, Int> {
            val a = cell(lo)
            var b = cell(hi)
            if (b > a && hi == b * CELL) b -= 1
            return a to b
        }

        private fun isLarge(item: Item): Boolean {
            val b = item.bounds
            val (cx0, cx1) = cellRange(b.l, b.r)
            val (cy0, cy1) = cellRange(b.t, b.b)
            return (cx1 - cx0 + 1).toLong() * (cy1 - cy0 + 1) > MAX_CELLS_PER_ITEM
        }

        private inline fun forEachCell(item: Item, f: (Long) -> Unit) {
            val b = item.bounds
            val (cx0, cx1) = cellRange(b.l, b.r)
            val (cy0, cy1) = cellRange(b.t, b.b)
            for (cx in cx0..cx1) for (cy in cy0..cy1) f(cellKey(cx, cy))
        }
    }
}
