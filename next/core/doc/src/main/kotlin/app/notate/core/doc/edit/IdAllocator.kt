package app.notate.core.doc.edit

import app.notate.core.model.Item
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.store.MaxIds

/** Hands out page ids, item ids and per-page draw order. Main thread only. */
class IdAllocator(max: MaxIds) {
    private var page = max.page
    private var item = max.item
    private val order = HashMap(max.order)

    fun nextPage(): PageId = PageId(++page)
    fun nextItem(): ItemId = ItemId(++item)

    /** Next draw order on [page]: current maximum + 1. */
    fun nextOrder(page: PageId): Long {
        val n = (order[page] ?: 0L) + 1
        order[page] = n
        return n
    }

    /** Folds loaded or externally created items into the maxima so later ids and orders never collide. */
    fun observe(page: PageId, items: Collection<Item>) {
        for (it in items) {
            if (it.id.value > item) item = it.id.value
            if (it.order > (order[page] ?: 0L)) order[page] = it.order
        }
    }

    /** Notes a page id created elsewhere. */
    fun observePage(id: PageId) { if (id.value > page) page = id.value }
}
