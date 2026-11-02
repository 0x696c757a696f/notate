@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.notate.core.legacy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/*
 * Read-only copies of the old app's ProtoBuf DTOs (`com.alexdremov.notate.data.SerializationModels`, `model.BackgroundStyle`,
 * `model.Tag`, `model.StrokeType`). Field numbers, declaration order and enum ordinals MUST stay identical to the old app
 * because ProtoBuf numbers fields by position when there is no @ProtoNumber and enums by ordinal.
 * Fields the importer does not need (thumbnail, toolbarItems) are omitted; unknown fields are skipped by the decoder.
 */

/** Old `CanvasType` (ordinal on the wire). */
enum class LegacyCanvasType { INFINITE, FIXED_PAGES }

/** Old `LinkType` (ordinal on the wire). */
enum class LegacyLinkType { INTERNAL_NOTE, EXTERNAL_URL, LOCAL_FILE }

/** Old `StrokeType` (ordinal on the wire; JSON name in web ink files). Never reorder. */
enum class LegacyStrokeType { FOUNTAIN, BALLPOINT, FINELINER, HIGHLIGHTER, BRUSH, CHARCOAL, DASH, DOT }

/** Old `Tag`. */
@Serializable
data class LegacyTag(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val color: Int,
    @ProtoNumber(4) val order: Int = 0,
)

/** Old `BackgroundStyle` sealed class. Serial names are the old fully qualified names (ProtoBuf writes them on the wire). */
@Serializable
sealed class LegacyBackground {
    abstract val color: Int

    @Serializable @SerialName("com.alexdremov.notate.model.BackgroundStyle.Blank")
    data class Blank(
        override val color: Int = 0,
        val paddingTop: Float = 0f,
        val paddingBottom: Float = 0f,
        val paddingLeft: Float = 0f,
        val paddingRight: Float = 0f,
        val isCentered: Boolean = false,
    ) : LegacyBackground()

    @Serializable @SerialName("com.alexdremov.notate.model.BackgroundStyle.Dots")
    data class Dots(
        override val color: Int = LTGRAY,
        val spacing: Float = 50f,
        val radius: Float = 2f,
        val paddingTop: Float = 0f,
        val paddingBottom: Float = 0f,
        val paddingLeft: Float = 0f,
        val paddingRight: Float = 0f,
        val isCentered: Boolean = false,
    ) : LegacyBackground()

    @Serializable @SerialName("com.alexdremov.notate.model.BackgroundStyle.Lines")
    data class Lines(
        override val color: Int = LTGRAY,
        val spacing: Float = 50f,
        val thickness: Float = 1f,
        val paddingTop: Float = 0f,
        val paddingBottom: Float = 0f,
        val paddingLeft: Float = 0f,
        val paddingRight: Float = 0f,
        val isCentered: Boolean = false,
    ) : LegacyBackground()

    @Serializable @SerialName("com.alexdremov.notate.model.BackgroundStyle.Grid")
    data class Grid(
        override val color: Int = LTGRAY,
        val spacing: Float = 50f,
        val thickness: Float = 1f,
        val paddingTop: Float = 0f,
        val paddingBottom: Float = 0f,
        val paddingLeft: Float = 0f,
        val paddingRight: Float = 0f,
        val isCentered: Boolean = false,
    ) : LegacyBackground()

    companion object {
        /** `android.graphics.Color.LTGRAY`. */
        const val LTGRAY: Int = 0xFFCCCCCC.toInt()
    }
}

/** Old `CanvasData` (the `manifest.bin` entry). */
@Serializable
data class LegacyCanvasData(
    @ProtoNumber(2) val version: Int = 3,
    @ProtoNumber(4) val offsetX: Float = 0f,
    @ProtoNumber(5) val offsetY: Float = 0f,
    @ProtoNumber(6) val zoomLevel: Float = 1f,
    @ProtoNumber(7) val canvasType: LegacyCanvasType = LegacyCanvasType.INFINITE,
    @ProtoNumber(8) val pageWidth: Float = 0f,
    @ProtoNumber(9) val pageHeight: Float = 0f,
    @ProtoNumber(10) val backgroundStyle: LegacyBackground = LegacyBackground.Blank(),
    @ProtoNumber(13) val tagIds: List<String> = emptyList(),
    @ProtoNumber(14) val tagDefinitions: List<LegacyTag> = emptyList(),
    @ProtoNumber(15) val regionSize: Float = 4096f,
    @ProtoNumber(16) val nextStrokeOrder: Long = 0,
    @ProtoNumber(17) val uuid: String? = null,
)

/** Old `RegionProto` (one `r_<x>_<y>.bin` entry). */
@Serializable
data class LegacyRegionProto(
    @ProtoNumber(1) val idX: Int,
    @ProtoNumber(2) val idY: Int,
    @ProtoNumber(3) val strokes: List<LegacyStrokeData> = emptyList(),
    @ProtoNumber(4) val images: List<LegacyImageData> = emptyList(),
    @ProtoNumber(5) val texts: List<LegacyTextData> = emptyList(),
    @ProtoNumber(6) val links: List<LegacyLinkData> = emptyList(),
)

/** Old `StrokeData`; [pointsPacked] has stride 6: x, y, pressure, size, tiltX, tiltY. */
@Serializable
class LegacyStrokeData(
    @ProtoNumber(2) val pointsPacked: FloatArray? = null,
    @ProtoNumber(3) val timestampsPacked: LongArray? = null,
    @ProtoNumber(4) val color: Int,
    @ProtoNumber(5) val width: Float,
    @ProtoNumber(6) val style: LegacyStrokeType,
    @ProtoNumber(7) val strokeOrder: Long = 0,
    @ProtoNumber(8) val zIndex: Float = 0f,
)

/** Old `CanvasImageData`; [uri] is a container-relative path, an absolute path, `file:` or `content:`. */
@Serializable
data class LegacyImageData(
    @ProtoNumber(1) val uri: String,
    @ProtoNumber(2) val x: Float,
    @ProtoNumber(3) val y: Float,
    @ProtoNumber(4) val width: Float,
    @ProtoNumber(5) val height: Float,
    @ProtoNumber(6) val zIndex: Float,
    @ProtoNumber(7) val order: Long,
    @ProtoNumber(8) val rotation: Float = 0f,
    @ProtoNumber(9) val opacity: Float = 1.0f,
    @ProtoNumber(10) val locked: Boolean = false,
)

/** Old `TextItemData`; [alignment] 0 = normal, 1 = opposite, 2 = center. */
@Serializable
data class LegacyTextData(
    @ProtoNumber(1) val text: String,
    @ProtoNumber(2) val x: Float,
    @ProtoNumber(3) val y: Float,
    @ProtoNumber(4) val width: Float,
    @ProtoNumber(5) val height: Float,
    @ProtoNumber(6) val fontSize: Float,
    @ProtoNumber(7) val color: Int,
    @ProtoNumber(8) val zIndex: Float,
    @ProtoNumber(9) val order: Long,
    @ProtoNumber(10) val rotation: Float = 0f,
    @ProtoNumber(11) val opacity: Float = 1.0f,
    @ProtoNumber(12) val alignment: Int = 0,
    @ProtoNumber(13) val backgroundColor: Int = 0,
)

/** Old `LinkItemData`; [target] is a note UUID for INTERNAL_NOTE, otherwise a URI. */
@Serializable
data class LegacyLinkData(
    @ProtoNumber(1) val label: String,
    @ProtoNumber(2) val target: String,
    @ProtoNumber(3) val x: Float,
    @ProtoNumber(4) val y: Float,
    @ProtoNumber(5) val width: Float,
    @ProtoNumber(6) val height: Float,
    @ProtoNumber(7) val zIndex: Float,
    @ProtoNumber(8) val order: Long,
    @ProtoNumber(9) val color: Int,
    @ProtoNumber(10) val rotation: Float = 0f,
    @ProtoNumber(11) val type: LegacyLinkType = LegacyLinkType.INTERNAL_NOTE,
    @ProtoNumber(12) val fontSize: Float = 24f,
)
