package app.notate.core.store.doc

import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.DocumentMeta
import app.notate.core.model.PageInfo
import app.notate.core.store.DocStore
import app.notate.core.store.DocStoreFactory
import app.notate.core.store.codec.JsonCodecs
import app.notate.core.store.schema.DocSchema
import app.notate.core.store.schema.Migrations
import app.notate.core.store.sql.SqlDriver
import app.notate.core.store.sql.SqlDriverFactory
import app.notate.core.store.sql.SqlPragmas
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Creates and opens `doc.db` packages; [io] picks the (single-threaded) dispatcher for each document. */
class SqliteDocStoreFactory(
    private val drivers: SqlDriverFactory,
    private val io: (DocId) -> CoroutineDispatcher,
    private val clock: Clock,
) : DocStoreFactory {
    override suspend fun create(dir: File, meta: DocumentMeta, pages: List<PageInfo>): DocStore = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = File(dir, DB_NAME)
        check(!file.exists()) { "Document already exists: $dir" }
        val driver = drivers.open(file, readOnly = false)
        try {
            SqlPragmas.apply(driver, readOnly = false)
            driver.transaction {
                Migrations.apply(driver, DocSchema.VERSION, DocSchema.MIGRATIONS)
                driver.execute("INSERT INTO meta(key, value) VALUES ('format', ?)", DocSchema.VERSION.toString())
                driver.execute("INSERT INTO meta(key, value) VALUES ('doc', ?)", JsonCodecs.encodeMeta(meta))
                pages.forEach { insertPage(driver, it) }
            }
        } catch (t: Throwable) {
            driver.close(); file.delete()
            throw t
        }
        SqliteDocStore(driver, dir, io(meta.id), clock)
    }

    override suspend fun open(dir: File, readOnly: Boolean): DocStore = withContext(Dispatchers.IO) {
        val driver = drivers.open(File(dir, DB_NAME), readOnly)
        try {
            SqlPragmas.apply(driver, readOnly)
            if (readOnly) Migrations.requireNotNewer(driver, DocSchema.VERSION)
            else Migrations.apply(driver, DocSchema.VERSION, DocSchema.MIGRATIONS)
            val id = driver.query("SELECT value FROM meta WHERE key = 'doc'") { JsonCodecs.decodeMeta(it.string(0)!!).id }.single()
            SqliteDocStore(driver, dir, io(id), clock, readOnly)
        } catch (t: Throwable) {
            driver.close()
            throw t
        }
    }

    private fun insertPage(driver: SqlDriver, p: PageInfo) = driver.execute(
        "INSERT INTO page(id, ord, width, height, background, ink_rev, flags, modified) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        p.id.value, p.ord, p.width, p.height, JsonCodecs.encodeBackground(p.background), p.inkRev,
        if (p.bookmarked) 1 else 0, p.modifiedAt,
    )

    companion object { const val DB_NAME = "doc.db" }
}
