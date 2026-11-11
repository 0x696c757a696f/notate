package app.notate.core.store.doc

import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.Background
import app.notate.core.model.Clock
import app.notate.core.model.DocumentMeta
import app.notate.core.model.Item
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.edit.Edit
import app.notate.core.store.AssetKind
import app.notate.core.store.DocStore
import app.notate.core.store.MaxIds
import app.notate.core.store.OutlineEntry
import app.notate.core.store.codec.ItemCodec
import app.notate.core.store.codec.JsonCodecs
import app.notate.core.store.sql.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [DocStore] over one `doc.db`. Every method runs on [io], which must be a single-threaded dispatcher owned by the caller
 * (the SQLite connection is used by one coroutine at a time).
 */
class SqliteDocStore(
    private val driver: SqlDriver,
    override val dir: File,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val readOnly: Boolean = false,
) : DocStore {
    private val assets = AssetStorage(driver, dir)
    private var metaCache: DocumentMeta? = null

    override suspend fun readMeta(): DocumentMeta = withContext(io) { loadMeta() }

    private fun loadMeta(): DocumentMeta = metaCache ?: driver.query("SELECT value FROM meta WHERE key = 'doc'") { it.string(0)!! }
        .single().let { JsonCodecs.decodeMeta(it) }.also { metaCache = it }

    override suspend fun readPages(): List<PageInfo> = withContext(io) {
        driver.query("SELECT id, ord, width, height, background, ink_rev, flags, modified FROM page ORDER BY ord") { r ->
            PageInfo(
                PageId(r.long(0)), r.double(1), r.double(2).toFloat(), if (r.isNull(3)) null else r.double(3).toFloat(),
                JsonCodecs.decodeBackground(r.string(4)!!), r.long(5), r.long(7), bookmarked = r.long(6) and 1L != 0L,
            )
        }
    }

    override suspend fun readItems(page: PageId, region: Rect?): List<Item> = withContext(io) {
        val base = "SELECT id, kind, layer, ord, l, t, r, b, data FROM item WHERE page_id = ?"
        val order = " ORDER BY layer, ord"
        val map = { r: app.notate.core.store.sql.SqlRow ->
            ItemCodec.decode(
                r.long(1).toInt(), ItemId(r.long(0)), r.long(2).toInt(), r.long(3),
                Rect(r.double(4).toFloat(), r.double(5).toFloat(), r.double(6).toFloat(), r.double(7).toFloat()), r.blob(8)!!,
            )
        }
        if (region == null) driver.query(base + order, page.value, map = map)
        else driver.query("$base AND t < ? AND b > ? AND l < ? AND r > ?$order", page.value, region.b, region.t, region.r, region.l, map = map)
    }

    override suspend fun pageBounds(page: PageId): Rect? = withContext(io) {
        driver.query("SELECT MIN(l), MIN(t), MAX(r), MAX(b) FROM item WHERE page_id = ?", page.value) { r ->
            if (r.isNull(0)) null else Rect(r.double(0).toFloat(), r.double(1).toFloat(), r.double(2).toFloat(), r.double(3).toFloat())
        }.single()?.takeIf { !it.isEmpty }
    }

    override suspend fun maxIds(): MaxIds = withContext(io) {
        val page = driver.query("SELECT MAX(id) FROM page") { if (it.isNull(0)) 0L else it.long(0) }.single()
        val item = driver.query("SELECT MAX(id) FROM item") { if (it.isNull(0)) 0L else it.long(0) }.single()
        val order = driver.query("SELECT page_id, MAX(ord) FROM item GROUP BY page_id") { PageId(it.long(0)) to it.long(1) }.toMap()
        MaxIds(page, item, order)
    }

    override suspend fun write(edits: List<Edit>): Unit = withContext(io) {
        if (edits.isEmpty()) return@withContext
        check(!readOnly) { "Document opened read-only" }
        val now = clock.now()
        var newMeta: DocumentMeta? = null
        driver.transaction {
            val applier = EditApplier(driver, now)
            edits.forEach(applier::apply)
            val m = (applier.meta ?: loadMeta()).copy(modifiedAt = now)
            driver.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('doc', ?)", JsonCodecs.encodeMeta(m))
            newMeta = m
        }
        metaCache = newMeta
    }

    override suspend fun putAsset(kind: AssetKind, mime: String, source: File, ext: String): AssetId =
        withContext(io) { assets.put(kind, mime, source, ext) }

    override fun assetFile(id: AssetId): File? = assets.file(id)

    override suspend fun readOutline(): List<OutlineEntry> = withContext(io) {
        driver.query("SELECT id, parent_id, ord, title, page_id, y_pt, anchor FROM outline ORDER BY ord, id") { r ->
            OutlineEntry(
                r.long(0), if (r.isNull(1)) null else r.long(1), r.long(2).toInt(), r.string(3)!!,
                if (r.isNull(4)) null else PageId(r.long(4)), if (r.isNull(5)) null else r.double(5).toFloat(), r.string(6),
            )
        }
    }

    override suspend fun writeOutline(entries: List<OutlineEntry>) = withContext(io) {
        driver.transaction {
            driver.execute("DELETE FROM outline")
            for (e in entries) {
                driver.execute(
                    "INSERT INTO outline(id, parent_id, ord, title, page_id, y_pt, anchor) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    e.id, e.parentId, e.ord, e.title, e.page?.value, e.yPt, e.anchor,
                )
            }
        }
    }

    /** Deletes assets no page background or image item refers to. Audio assets are never removed here. */
    override suspend fun vacuumAssets() = withContext(io) {
        val backgrounds = driver.query("SELECT background FROM page") { JsonCodecs.decodeBackground(it.string(0)!!) }
        val used = HashSet<String>()
        var hasWeb = false
        for (b in backgrounds) when (b) {
            is Background.Pdf -> used.add(b.asset.value)
            is Background.Web -> hasWeb = true
            else -> Unit
        }
        driver.query("SELECT data FROM item WHERE kind = ${ItemCodec.KIND_IMAGE}") { ItemCodec.imageAsset(it.blob(0)!!).value }
            .forEach(used::add)
        assets.removeUnused(used, keepWeb = hasWeb)
    }

    override fun close() {
        try {
            if (!readOnly) driver.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        } finally {
            driver.close()
        }
    }
}
