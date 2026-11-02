package app.notate.core.store.codec

import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.CorruptDataException
import app.notate.core.model.DocId
import app.notate.core.model.ImageItem
import app.notate.core.model.InkStyle
import app.notate.core.model.Item
import app.notate.core.model.ItemId
import app.notate.core.model.LinkItem
import app.notate.core.model.LinkTarget
import app.notate.core.model.PageId
import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.ShapeKind
import app.notate.core.model.Stroke
import app.notate.core.model.TextAlign
import app.notate.core.model.TextBox
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf

/** Encodes items to `item.data` blobs: first byte = codecVersion, then StrokeCodec bytes or a ProtoBuf DTO. */
@OptIn(ExperimentalSerializationApi::class)
object ItemCodec {
    const val VERSION = 1
    const val KIND_STROKE = 0
    const val KIND_SHAPE = 1
    const val KIND_TEXT = 2
    const val KIND_IMAGE = 3
    const val KIND_LINK = 4

    private val proto = ProtoBuf

    fun kind(item: Item): Int = when (item) {
        is Stroke -> KIND_STROKE
        is Shape -> KIND_SHAPE
        is TextBox -> KIND_TEXT
        is ImageItem -> KIND_IMAGE
        is LinkItem -> KIND_LINK
    }

    fun encode(item: Item): ByteArray = when (item) {
        is Stroke -> StrokeCodec.encode(item)
        is Shape -> wrap(proto.encodeToByteArray(ShapeDto.serializer(),
            ShapeDto(item.style.pen.code, item.style.color, item.style.widthPt, item.kind.code, item.params, item.fill)))
        is TextBox -> wrap(proto.encodeToByteArray(TextDto.serializer(),
            TextDto(item.frame.dto(), item.rotationDeg, item.text, item.fontSizePt, item.color, item.bold, item.align.ordinal)))
        is ImageItem -> wrap(proto.encodeToByteArray(ImageDto.serializer(),
            ImageDto(item.asset.value, item.frame.dto(), item.rotationDeg, item.opacity, item.locked)))
        is LinkItem -> wrap(proto.encodeToByteArray(LinkDto.serializer(), linkDto(item)))
    }

    /** Decodes an item row. [layer] is validated but derived from the item type. Throws [CorruptDataException] on bad data. */
    fun decode(kind: Int, id: ItemId, layer: Int, order: Long, bounds: Rect, bytes: ByteArray): Item {
        app.notate.core.model.Layer.fromCode(layer)
        if (kind == KIND_STROKE) return StrokeCodec.decode(id, order, bytes, bounds)
        if (bytes.isEmpty() || bytes[0].toInt() != VERSION) {
            throw CorruptDataException("Unsupported item codec version ${bytes.firstOrNull()}")
        }
        val body = bytes.copyOfRange(1, bytes.size)
        try {
            return when (kind) {
                KIND_SHAPE -> proto.decodeFromByteArray(ShapeDto.serializer(), body).let {
                    Shape(id, order, InkStyle(PenType.fromCode(it.pen), it.color, it.widthPt), ShapeKind.fromCode(it.kind),
                        it.params, it.fill, bounds)
                }
                KIND_TEXT -> proto.decodeFromByteArray(TextDto.serializer(), body).let {
                    val align = TextAlign.entries.getOrNull(it.align) ?: throw CorruptDataException("Bad align ${it.align}")
                    TextBox(id, order, it.frame.rect(), it.rotationDeg, it.text, it.fontSizePt, it.color, it.bold, align, bounds)
                }
                KIND_IMAGE -> proto.decodeFromByteArray(ImageDto.serializer(), body).let {
                    ImageItem(id, order, AssetId(it.asset), it.frame.rect(), it.rotationDeg, it.opacity, it.locked, bounds)
                }
                KIND_LINK -> proto.decodeFromByteArray(LinkDto.serializer(), body).let {
                    val target = when (it.targetKind) {
                        0 -> LinkTarget.Page(DocId(it.doc ?: throw CorruptDataException("Link without doc")),
                            PageId(it.page ?: throw CorruptDataException("Link without page")))
                        1 -> LinkTarget.Url(it.url ?: throw CorruptDataException("Link without url"))
                        else -> throw CorruptDataException("Bad link target ${it.targetKind}")
                    }
                    LinkItem(id, order, it.frame.rect(), it.label, target, bounds)
                }
                else -> throw CorruptDataException("Unknown item kind $kind")
            }
        } catch (e: SerializationException) {
            throw CorruptDataException("Corrupt item data: ${e.message}")
        }
    }

    /** Asset referenced by an image blob, without building the item (used by asset vacuum). */
    fun imageAsset(bytes: ByteArray): AssetId {
        if (bytes.isEmpty() || bytes[0].toInt() != VERSION) throw CorruptDataException("Unsupported item codec version")
        try {
            return AssetId(proto.decodeFromByteArray(ImageDto.serializer(), bytes.copyOfRange(1, bytes.size)).asset)
        } catch (e: SerializationException) {
            throw CorruptDataException("Corrupt image data: ${e.message}")
        }
    }

    private fun linkDto(item: LinkItem): LinkDto = when (val t = item.target) {
        is LinkTarget.Page -> LinkDto(item.frame.dto(), item.label, 0, doc = t.doc.value, page = t.page.value)
        is LinkTarget.Url -> LinkDto(item.frame.dto(), item.label, 1, url = t.url)
    }

    private fun wrap(body: ByteArray): ByteArray {
        val out = ByteArray(body.size + 1)
        out[0] = VERSION.toByte()
        System.arraycopy(body, 0, out, 1, body.size)
        return out
    }

    private fun Rect.dto() = RectDto(l, t, r, b)
    private fun RectDto.rect() = Rect(l, t, r, b)
}
