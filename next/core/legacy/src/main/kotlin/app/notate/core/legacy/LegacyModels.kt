package app.notate.core.legacy

import app.notate.core.model.AssetId
import app.notate.core.model.DocId
import app.notate.core.model.DocumentMeta
import app.notate.core.model.Item
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import java.io.File
import java.io.InputStream

/** Thrown when a file is not a readable old-format container at all (no/invalid manifest). */
class LegacyFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** An image file referenced by a legacy note; [uri] is the exact string stored in the old data. */
data class LegacyAssetRef(val uri: String, val assetId: AssetId, val extension: String, val size: Long)

/**
 * Everything read from one old `.notate` container. [openAsset] streams the bytes of a resolved [assets] entry and is only valid
 * while the source (the `ZipFile`) passed to the reader is still open.
 */
class LegacyCanvas(
    val data: LegacyCanvasData,
    val regions: List<LegacyRegionProto>,
    /** Resolved images by [LegacyAssetRef.uri]. Images that could not be found (e.g. `content:`) are absent. */
    val assets: Map<String, LegacyAssetRef>,
    val warnings: List<String>,
    private val opener: (String) -> InputStream?,
) {
    fun openAsset(uri: String): InputStream? = opener(uri)
}

/** Hands out ids for one imported document batch. Page and item ids restart at 1 in every document. */
fun interface DocIdSource { fun next(): DocId }

/** Result of mapping one legacy note or web document into the new model. */
class MappedDocument(
    val meta: DocumentMeta,
    val pages: List<PageInfo>,
    val items: Map<PageId, List<Item>>,
    /** Images actually referenced by [items]; empty for web documents. */
    val assets: List<LegacyAssetRef>,
    /** Tag names (colours in [tagColors]). */
    val tags: List<String>,
    val tagColors: Map<String, Int>,
    val warnings: List<String>,
    /** Old `CanvasData.uuid` (notes) or old web document id; used for `kv["legacy:<uuid>"]` and link fixing. */
    val legacyUuid: String?,
    /** Old web document `content/` folder to copy into `assets/web/`; null for notebooks. */
    val webContent: File? = null,
)
