package app.notate.core.store

import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.store.schema.DocSchema
import app.notate.core.store.schema.LibrarySchema
import app.notate.core.store.schema.Migration
import app.notate.core.store.schema.Migrations
import app.notate.core.store.schema.NewerFormatException
import app.notate.core.testing.FakeClock
import app.notate.core.testing.JdbcSqlDriverFactory
import app.notate.core.testing.meta
import app.notate.core.testing.page
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class MigrationsTest {
    private val drivers = JdbcSqlDriverFactory()

    @Test fun freshDocDatabaseIsVersionOne() {
        val d = drivers.openMemory()
        Migrations.apply(d, DocSchema.VERSION, DocSchema.MIGRATIONS)
        assertThat(d.userVersion).isEqualTo(1)
        assertThat(d.query("SELECT name FROM sqlite_master WHERE type='table'") { it.string(0) }).containsAtLeast("meta", "page", "item", "asset", "outline", "ocr")
    }

    @Test fun freshLibraryDatabaseIsVersionOne() {
        val d = drivers.openMemory()
        Migrations.apply(d, LibrarySchema.VERSION, LibrarySchema.MIGRATIONS)
        assertThat(d.userVersion).isEqualTo(1)
    }

    @Test fun newerVersionThrows() {
        val d = drivers.openMemory()
        d.userVersion = 99
        val e = assertThrows(NewerFormatException::class.java) { Migrations.apply(d, 1, DocSchema.MIGRATIONS) }
        assertThat(e.message).isEqualTo("This notebook was created by a newer version of Notate.")
    }

    @Test fun reopeningFileWithVersion99Throws() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("mig").toFile()
        val factory = SqliteDocStoreFactory(drivers, { Dispatchers.IO }, FakeClock())
        factory.create(dir, meta(), listOf(page(1))).close()
        drivers.open(java.io.File(dir, "doc.db"), false).use { it.userVersion = 99 }
        assertThrows(NewerFormatException::class.java) { runBlocking { factory.open(dir) } }
        assertThrows(NewerFormatException::class.java) { runBlocking { factory.open(dir, readOnly = true) } }
        Unit
    }

    @Test fun stepsRunInOrderAndAreTransactional() {
        val d = drivers.openMemory()
        val steps = listOf(
            Migration(1) { it.execute("CREATE TABLE a (x INTEGER)") },
            Migration(2) { it.execute("ALTER TABLE a ADD COLUMN y INTEGER") },
            Migration(3) { it.execute("CREATE TABLE b (x INTEGER)"); error("boom") },
        )
        assertThrows(IllegalStateException::class.java) { Migrations.apply(d, 3, steps) }
        assertThat(d.userVersion).isEqualTo(0)
        assertThat(d.query("SELECT name FROM sqlite_master WHERE type='table'") { it.string(0) }).isEmpty()
        Migrations.apply(d, 2, steps)
        assertThat(d.userVersion).isEqualTo(2)
    }
}
