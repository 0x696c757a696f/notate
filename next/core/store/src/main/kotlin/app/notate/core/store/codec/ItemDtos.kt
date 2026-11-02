@file:OptIn(ExperimentalSerializationApi::class)

package app.notate.core.store.codec

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked
import kotlinx.serialization.protobuf.ProtoType

/** ProtoBuf DTOs of non-stroke items. Field numbers are append-only and must never be reused. */
@Serializable
class RectDto(
    @ProtoNumber(1) val l: Float = 0f,
    @ProtoNumber(2) val t: Float = 0f,
    @ProtoNumber(3) val r: Float = 0f,
    @ProtoNumber(4) val b: Float = 0f,
)

@Serializable
class ShapeDto(
    @ProtoNumber(1) val pen: Int,
    @ProtoNumber(2) @ProtoType(ProtoIntegerType.FIXED) val color: Int,
    @ProtoNumber(3) val widthPt: Float,
    @ProtoNumber(4) val kind: Int,
    @ProtoNumber(5) @ProtoPacked val params: FloatArray,
    @ProtoNumber(6) @ProtoType(ProtoIntegerType.FIXED) val fill: Int? = null,
)

@Serializable
class TextDto(
    @ProtoNumber(1) val frame: RectDto,
    @ProtoNumber(2) val rotationDeg: Float,
    @ProtoNumber(3) val text: String,
    @ProtoNumber(4) val fontSizePt: Float,
    @ProtoNumber(5) @ProtoType(ProtoIntegerType.FIXED) val color: Int,
    @ProtoNumber(6) val bold: Boolean,
    @ProtoNumber(7) val align: Int,
)

@Serializable
class ImageDto(
    @ProtoNumber(1) val asset: String,
    @ProtoNumber(2) val frame: RectDto,
    @ProtoNumber(3) val rotationDeg: Float,
    @ProtoNumber(4) val opacity: Float,
    @ProtoNumber(5) val locked: Boolean,
)

/** [targetKind]: 0 = page link ([doc], [page]), 1 = URL ([url]). */
@Serializable
class LinkDto(
    @ProtoNumber(1) val frame: RectDto,
    @ProtoNumber(2) val label: String,
    @ProtoNumber(3) val targetKind: Int,
    @ProtoNumber(4) val doc: String? = null,
    @ProtoNumber(5) val page: Long? = null,
    @ProtoNumber(6) val url: String? = null,
)
