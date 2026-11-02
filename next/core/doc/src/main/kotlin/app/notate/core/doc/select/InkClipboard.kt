package app.notate.core.doc.select

import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.geometry.Affine
import app.notate.core.geometry.Rect
import app.notate.core.geometry.Vec2
import app.notate.core.model.AssetId
import app.notate.core.model.ImageItem
import app.notate.core.model.Item
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.edit.Edit
import app.notate.core.store.AssetKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Process-wide ink clipboard (one instance in the app graph); works across documents. Main thread only. */
class InkClipboard {
    private var items: List<Item> = emptyList()
    private var source: Rect = Rect.EMPTY
    /** Asset files of copied images, captured at copy time so a paste into another document can import them. */
    private val assetFiles = HashMap<AssetId, File>()
    private val _hasContent = MutableStateFlow(false)
    val hasContent: StateFlow<Boolean> get() = _hasContent

    /** Stores [items]; images keep a pointer to their asset file looked up through [from]. */
    fun put(items: List<Item>, sourceBounds: Rect, from: DocumentEditor? = null) {
        this.items = items.toList()
        this.source = sourceBounds
        assetFiles.clear()
        if (from != null) for (i in items) if (i is ImageItem) from.assetFile(i.asset)?.let { assetFiles[i.asset] = it }
        _hasContent.value = this.items.isNotEmpty()
    }

    /**
     * Pastes onto [page] (which must be loaded) centred on [at]: new ids, top of the draw order, one [Edit.Items].
     * Image assets missing from the target document are imported first. Returns the new ids (empty when nothing to paste).
     */
    suspend fun paste(editor: DocumentEditor, page: PageId, at: Vec2): List<ItemId> {
        if (items.isEmpty()) return emptyList()
        for ((id, file) in assetFiles) {
            if (editor.assetFile(id) == null && file.exists()) {
                editor.importAsset(AssetKind.IMAGE, "image/" + file.extension.ifEmpty { "png" }, file, file.extension.ifEmpty { "png" })
            }
        }
        val c = source.center
        val shift = Affine.translate(at.x - c.x, at.y - c.y)
        val added = items.sortedWith(compareBy({ it.layer.code }, { it.order }))
            .map { ItemOps.withIdOrder(it.transformed(shift), editor.newItemId(), editor.nextOrder(page)) }
        editor.apply(Edit.Items(page, emptyList(), added))
        return added.map { it.id }
    }
}
