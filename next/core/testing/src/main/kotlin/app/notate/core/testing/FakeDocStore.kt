package app.notate.core.testing

import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.DocumentMeta
import app.notate.core.model.Item
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.edit.Edit
import app.notate.core.store.AssetKind
import app.notate.core.store.DocStore
import app.notate.core.store.MaxIds
import app.notate.core.store.OutlineEntry
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** In-memory [DocStore] for tests: records every `write()` call and can fail the next one. */
class FakeDocStore(
    meta: DocumentMeta,
    pages: List<PageInfo> = emptyList(),
    override val dir: File = Files.createTempDirectory("fake-doc").toFile(),
) : DocStore {
    private var meta: DocumentMeta = meta
    private val pageMap = LinkedHashMap<PageId, PageInfo>().apply { pages.forEach { put(it.id, it) } }
    private val itemMap = HashMap<PageId, LinkedHashMap<Long, Item>>()
    private val assets = HashMap<AssetId, File>()
    private var outline: List<OutlineEntry> = emptyList()

    /** Every successful or failed `write()` call's edits, in order. */
    val writes: MutableList<List<Edit>> = ArrayList()
    /** When non-null, the next `write()` throws it once (after recording) and nothing is applied. */
    var failNext: Throwable? = null
    var closed: Boolean = false
        private set

    override suspend fun readMeta(): DocumentMeta = meta
    override suspend fun readPages(): List<PageInfo> = pageMap.values.sortedBy { it.ord }

    override suspend fun readItems(page: PageId, region: Rect?): List<Item> =
        (itemMap[page]?.values ?: emptyList<Item>())
            .filter { region == null || it.bounds.intersects(region) }
            .sortedWith(compareBy({ it.layer.code }, { it.order }))

    override suspend fun maxIds(): MaxIds {
        val order = HashMap<PageId, Long>()
        var maxItem = 0L
        for ((p, m) in itemMap) {
            for (it in m.values) { maxItem = maxOf(maxItem, it.id.value); order[p] = maxOf(order[p] ?: 0L, it.order) }
        }
        return MaxIds(pageMap.keys.maxOfOrNull { it.value } ?: 0L, maxItem, order)
    }

    override suspend fun write(edits: List<Edit>) {
        writes.add(edits.toList())
        failNext?.let { failNext = null; throw it }
        edits.forEach(::apply)
    }

    private fun apply(e: Edit) {
        when (e) {
            is Edit.Items -> {
                val m = itemMap.getOrPut(e.page) { LinkedHashMap() }
                e.removed.forEach { m.remove(it.id.value) }
                e.added.forEach { m[it.id.value] = it }
            }
            is Edit.InsertPage -> {
                pageMap[e.page.id] = e.page
                itemMap[e.page.id] = LinkedHashMap<Long, Item>().apply { e.items.forEach { put(it.id.value, it) } }
            }
            is Edit.DeletePage -> { pageMap.remove(e.page.id); itemMap.remove(e.page.id) }
            is Edit.MovePage -> pageMap[e.page]?.let { pageMap[e.page] = it.copy(ord = e.toOrd) }
            is Edit.SetPage -> pageMap[e.after.id] = e.after
            is Edit.SetMeta -> meta = e.after
            is Edit.Batch -> e.edits.forEach(::apply)
        }
    }

    override suspend fun putAsset(kind: AssetKind, mime: String, source: File, ext: String): AssetId {
        val md = MessageDigest.getInstance("SHA-256")
        val hex = md.digest(source.readBytes()).joinToString("") { "%02x".format(it) }.take(32)
        val id = AssetId(hex)
        if (id !in assets) {
            val target = File(File(dir, "assets").apply { mkdirs() }, "$hex.$ext")
            source.copyTo(target, overwrite = true)
            assets[id] = target
        }
        return id
    }

    override fun assetFile(id: AssetId): File? = assets[id]
    override suspend fun readOutline(): List<OutlineEntry> = outline
    override suspend fun writeOutline(entries: List<OutlineEntry>) { outline = entries.toList() }
    override fun close() { closed = true }
}
