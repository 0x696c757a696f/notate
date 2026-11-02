package app.notate.core.store.doc

import app.notate.core.model.DocumentMeta
import app.notate.core.model.Item
import app.notate.core.model.PageInfo
import app.notate.core.model.edit.Edit
import app.notate.core.store.codec.ItemCodec
import app.notate.core.store.codec.JsonCodecs
import app.notate.core.store.sql.SqlDriver

/** Translates [Edit]s into SQL inside the caller's transaction. */
internal class EditApplier(private val driver: SqlDriver, private val now: Long) {
    /** Meta set by a `SetMeta` edit in this batch, if any. */
    var meta: DocumentMeta? = null
        private set

    fun apply(e: Edit) {
        when (e) {
            is Edit.Items -> {
                if (e.removed.isEmpty() && e.added.isEmpty()) return
                for (item in e.removed) driver.execute("DELETE FROM item WHERE id = ? AND page_id = ?", item.id.value, e.page.value)
                for (item in e.added) insertItem(e.page.value, item)
                driver.execute("UPDATE page SET ink_rev = ink_rev + 1, modified = ? WHERE id = ?", now, e.page.value)
            }
            is Edit.InsertPage -> {
                insertPage(e.page)
                e.items.forEach { insertItem(e.page.id.value, it) }
            }
            is Edit.DeletePage -> driver.execute("DELETE FROM page WHERE id = ?", e.page.id.value)
            is Edit.MovePage -> driver.execute("UPDATE page SET ord = ?, modified = ? WHERE id = ?", e.toOrd, now, e.page.value)
            is Edit.SetPage -> driver.execute(
                "UPDATE page SET ord = ?, width = ?, height = ?, background = ?, flags = ?, modified = ? WHERE id = ?",
                e.after.ord, e.after.width, e.after.height, JsonCodecs.encodeBackground(e.after.background),
                if (e.after.bookmarked) 1 else 0, now, e.after.id.value,
            )
            is Edit.SetMeta -> meta = e.after
            is Edit.Batch -> e.edits.forEach(::apply)
        }
    }

    private fun insertPage(p: PageInfo) = driver.execute(
        "INSERT INTO page(id, ord, width, height, background, ink_rev, flags, modified) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        p.id.value, p.ord, p.width, p.height, JsonCodecs.encodeBackground(p.background), p.inkRev,
        if (p.bookmarked) 1 else 0, p.modifiedAt,
    )

    private fun insertItem(pageId: Long, item: Item) = driver.execute(
        "INSERT OR REPLACE INTO item(id, page_id, kind, layer, ord, l, t, r, b, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        item.id.value, pageId, ItemCodec.kind(item), item.layer.code, item.order,
        item.bounds.l, item.bounds.t, item.bounds.r, item.bounds.b, ItemCodec.encode(item),
    )
}
