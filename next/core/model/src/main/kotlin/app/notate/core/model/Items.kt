package app.notate.core.model

import app.notate.core.geometry.Affine
import app.notate.core.geometry.Rect
import kotlinx.serialization.Serializable

/**
 * Immutable page content object. Every change produces a new object. Items are persisted through dedicated codecs
 * (not kotlinx-serialization) because stroke points use a compact binary form.
 */
sealed interface Item {
    val id: ItemId
    val order: Long
    val layer: Layer
    /** Axis-aligned bounds in page coordinates, including stroke width. */
    val bounds: Rect
    /** Returns the item moved/scaled/rotated by [a] with bounds recomputed. */
    fun transformed(a: Affine): Item
}

data class Stroke(
    override val id: ItemId,
    override val order: Long,
    val style: InkStyle,
    val points: StrokePoints,
    override val bounds: Rect,
) : Item {
    override val layer: Layer get() = if (style.pen == PenType.HIGHLIGHTER) Layer.HIGHLIGHT else Layer.INK
    override fun transformed(a: Affine): Item = transformStroke(this, a)
}

/** Geometric shape; [params] layout depends on [kind] (see 03-data-model §2). */
class Shape(
    override val id: ItemId,
    override val order: Long,
    val style: InkStyle,
    val kind: ShapeKind,
    val params: FloatArray,
    val fill: Int? = null,
    override val bounds: Rect,
) : Item {
    override val layer: Layer get() = Layer.INK
    override fun transformed(a: Affine): Item = transformShape(this, a)

    fun copy(
        id: ItemId = this.id, order: Long = this.order, style: InkStyle = this.style, kind: ShapeKind = this.kind,
        params: FloatArray = this.params, fill: Int? = this.fill, bounds: Rect = this.bounds,
    ) = Shape(id, order, style, kind, params, fill, bounds)

    override fun equals(other: Any?): Boolean =
        other is Shape && id == other.id && order == other.order && style == other.style && kind == other.kind &&
            params.contentEquals(other.params) && fill == other.fill && bounds == other.bounds

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + order.hashCode()
        h = 31 * h + style.hashCode()
        h = 31 * h + kind.hashCode()
        h = 31 * h + params.contentHashCode()
        h = 31 * h + (fill ?: 0)
        return h
    }

    override fun toString(): String = "Shape(id=$id, kind=$kind)"
}

data class TextBox(
    override val id: ItemId,
    override val order: Long,
    val frame: Rect,
    val rotationDeg: Float,
    val text: String,
    val fontSizePt: Float,
    val color: Int,
    val bold: Boolean,
    val align: TextAlign,
    override val bounds: Rect,
) : Item {
    override val layer: Layer get() = Layer.OVERLAY
    override fun transformed(a: Affine): Item = transformTextBox(this, a)
}

data class ImageItem(
    override val id: ItemId,
    override val order: Long,
    val asset: AssetId,
    val frame: Rect,
    val rotationDeg: Float,
    val opacity: Float,
    val locked: Boolean,
    override val bounds: Rect,
) : Item {
    override val layer: Layer get() = Layer.IMAGE
    override fun transformed(a: Affine): Item = transformImage(this, a)
}

data class LinkItem(
    override val id: ItemId,
    override val order: Long,
    val frame: Rect,
    val label: String,
    val target: LinkTarget,
    override val bounds: Rect,
) : Item {
    override val layer: Layer get() = Layer.OVERLAY
    override fun transformed(a: Affine): Item = transformLink(this, a)
}

@Serializable
sealed interface LinkTarget {
    @Serializable data class Page(val doc: DocId, val page: PageId) : LinkTarget
    @Serializable data class Url(val url: String) : LinkTarget
}
