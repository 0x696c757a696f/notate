package app.notate.core.store

import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.DocumentMeta
import app.notate.core.model.Item
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.edit.Edit
import java.io.File

/** Highest ids in use, so the editor's id allocator can continue after them. */
data class MaxIds(val page: Long, val item: Long, val order: Map<PageId, Long>)

enum class AssetKind(val code: Int) { IMAGE(0), PDF(1), WEB(2), AUDIO(3) }

data class OutlineEntry(
    val id: Long, val parentId: Long?, val ord: Int, val title: String,
    val page: PageId?, val yPt: Float?, val anchor: String? = null,
)

/** Persistent storage for one document. All calls suspend and run on an IO dispatcher chosen by the caller. */
interface DocStore : java.io.Closeable {
    val dir: File
    suspend fun readMeta(): DocumentMeta
    suspend fun readPages(): List<PageInfo>
    /** Items of [page] ordered by layer then order; with [region] only items whose bounds intersect it. */
    suspend fun readItems(page: PageId, region: Rect? = null): List<Item>
    suspend fun maxIds(): MaxIds
    /** Applies [edits] in one transaction. */
    suspend fun write(edits: List<Edit>)
    /** Copies [source] into the document's asset store, de-duplicated by content hash. */
    suspend fun putAsset(kind: AssetKind, mime: String, source: File, ext: String): AssetId
    fun assetFile(id: AssetId): File?
    suspend fun readOutline(): List<OutlineEntry>
    suspend fun writeOutline(entries: List<OutlineEntry>)
    /** Deletes assets that no page or item refers to; call when the document closes. Default: nothing to clean. */
    suspend fun vacuumAssets() {}
    /** Writes a consistent single-file copy of `doc.db` (no WAL) to [target]; used by `.notate` packages and backups. */
    suspend fun snapshot(target: File): Unit = throw UnsupportedOperationException("snapshot")
    /** Paths (relative to [dir]) of every file or folder recorded in the asset table, e.g. `assets/ab12.pdf`, `assets/web/`. */
    suspend fun assetPaths(): List<String> = emptyList()
}

interface DocStoreFactory {
    suspend fun create(dir: File, meta: DocumentMeta, pages: List<PageInfo>): DocStore
    suspend fun open(dir: File, readOnly: Boolean = false): DocStore
}

/** Adapter used by renderers: loads page items without exposing the whole store. */
interface PageLoader { suspend fun load(page: PageId, region: Rect?): List<Item> }
