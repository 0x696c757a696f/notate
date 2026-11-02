package app.notate.core.doc.select

import app.notate.core.model.ImageItem
import app.notate.core.model.InkStyle
import app.notate.core.model.Item
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.LinkItem
import app.notate.core.model.Shape
import app.notate.core.model.Stroke
import app.notate.core.model.TextBox

/** Small item rewrites used by the selection and clipboard. Pure. */
internal object ItemOps {
    fun withIdOrder(i: Item, id: ItemId, order: Long): Item = when (i) {
        is Stroke -> i.copy(id = id, order = order)
        is Shape -> i.copy(id = id, order = order)
        is TextBox -> i.copy(id = id, order = order)
        is ImageItem -> i.copy(id = id, order = order)
        is LinkItem -> i.copy(id = id, order = order)
    }

    fun withOrder(i: Item, order: Long): Item = withIdOrder(i, i.id, order)

    /** Recoloured copy, or null when the item has no colour (images, links). */
    fun recolored(i: Item, color: Int): Item? = when (i) {
        is Stroke -> i.copy(style = i.style.copy(color = color))
        is Shape -> i.copy(style = i.style.copy(color = color))
        is TextBox -> i.copy(color = color)
        else -> null
    }.takeIf { it != i }

    /** Copy with line width [widthPt], or null when the item has no line width. */
    fun rewidthed(i: Item, widthPt: Float): Item? = when (i) {
        is Stroke -> {
            val st: InkStyle = i.style.copy(widthPt = widthPt)
            i.copy(style = st, bounds = ItemBounds.strokeBounds(i.points, st))
        }
        is Shape -> {
            val st = i.style.copy(widthPt = widthPt)
            i.copy(style = st, bounds = ItemBounds.shapeBounds(i.kind, i.params, st))
        }
        else -> null
    }.takeIf { it != i }
}
