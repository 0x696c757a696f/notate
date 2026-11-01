package app.notate.core.model.edit

import app.notate.core.model.DocumentMeta
import app.notate.core.model.Item
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo

/** A reversible document mutation carrying full before/after data, so it can invert itself without reading the model. */
sealed interface Edit {
    /** Adds/removes items on one page (draw, erase, split, transform and recolour are all this). */
    data class Items(val page: PageId, val removed: List<Item>, val added: List<Item>) : Edit
    data class InsertPage(val page: PageInfo, val items: List<Item>) : Edit
    data class DeletePage(val page: PageInfo, val items: List<Item>) : Edit
    data class MovePage(val page: PageId, val fromOrd: Double, val toOrd: Double) : Edit
    /** Background or size change. */
    data class SetPage(val before: PageInfo, val after: PageInfo) : Edit
    data class SetMeta(val before: DocumentMeta, val after: DocumentMeta) : Edit
    data class Batch(val edits: List<Edit>) : Edit

    /** The edit that undoes this one; `inverse().inverse() == this`. */
    fun inverse(): Edit = when (this) {
        is Items -> Items(page, removed = added, added = removed)
        is InsertPage -> DeletePage(page, items)
        is DeletePage -> InsertPage(page, items)
        is MovePage -> MovePage(this.page, toOrd, fromOrd)
        is SetPage -> SetPage(after, before)
        is SetMeta -> SetMeta(after, before)
        is Batch -> Batch(edits.asReversed().map { it.inverse() })
    }
}
