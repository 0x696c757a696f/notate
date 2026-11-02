package app.notate.platform

import app.notate.core.geometry.Rect
import app.notate.core.model.edit.Edit
import app.notate.core.model.PageId
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.testing.FakeClock
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.core.testing.stroke
import app.notate.platform.sql.AndroidSqlDriverFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class AndroidSqlDriverTest {
    private val factory = AndroidSqlDriverFactory()
    private fun tmp(): File = Files.createTempDirectory("androidsql").toFile()

    @Test fun typesRoundTripAndPragmasWork() {
        val d = factory.openMemory()
        d.execute("PRAGMA foreign_keys = ON")
        d.execute("CREATE TABLE t(a INTEGER, b REAL, c TEXT, d BLOB, e INTEGER)")
        d.execute("INSERT INTO t VALUES (?, ?, ?, ?, ?)", 7, 1.5f, "x", byteArrayOf(1, 2, 3), null)
        d.execute("INSERT INTO t VALUES (?, ?, ?, ?, ?)", true, 2.0, "y", byteArrayOf(), 5L)
        val rows = d.query("SELECT a, b, c, d, e FROM t ORDER BY a DESC") { listOf(it.long(0), it.double(1), it.string(2), it.blob(3)?.toList(), it.isNull(4)) }
        assertThat(rows).containsExactly(
            listOf(7L, 1.5, "x", listOf<Byte>(1, 2, 3), true),
            listOf(1L, 2.0, "y", listOf<Byte>(), false),
        ).inOrder()
        assertThat(d.query("SELECT c FROM t WHERE a = ? AND c = ?", 7, "x") { it.string(0) }).containsExactly("x")
        d.userVersion = 4
        assertThat(d.userVersion).isEqualTo(4)
        d.close()
    }

    @Test fun transactionRollsBackOutermostAndNestedJoins() {
        val d = factory.openMemory()
        d.execute("CREATE TABLE t(a INTEGER)")
        assertThrows(IllegalStateException::class.java) {
            d.transaction {
                d.execute("INSERT INTO t VALUES (1)")
                d.transaction { d.execute("INSERT INTO t VALUES (2)") }
                error("boom")
            }
        }
        assertThat(d.query("SELECT COUNT(*) FROM t") { it.long(0) }).containsExactly(0L)
        d.transaction { d.transaction { d.execute("INSERT INTO t VALUES (3)") } }
        assertThat(d.query("SELECT a FROM t") { it.long(0) }).containsExactly(3L)
        d.close()
    }

    @Test fun docStoreRoundTripOnAndroidSqlite() = runBlocking<Unit> {
        val clock = FakeClock(1000)
        val dir = tmp()
        val docs = SqliteDocStoreFactory(factory, { Dispatchers.IO }, clock)
        val s = docs.create(dir, meta(), listOf(page(1), page(2)))
        val strokes = (1L..100L).map { stroke(it, points = floatArrayOf(it.toFloat(), it * 0.5f, it + 20f, it + 3.25f)) }
        s.write(listOf(Edit.Items(PageId(1), emptyList(), strokes)))
        assertThat(s.readItems(PageId(1))).isEqualTo(strokes)
        assertThat(s.readItems(PageId(1), Rect(0f, 0f, 10f, 10f)).map { it.id.value }).isNotEmpty()
        // Rollback: a failing write leaves nothing behind.
        runCatching { s.write(listOf(Edit.Items(PageId(2), emptyList(), listOf(strokes[0])), Edit.Items(PageId(99), emptyList(), listOf(stroke(500))))) }
        s.close()
        val reopened = docs.open(dir, readOnly = true)
        assertThat(reopened.readMeta().id).isEqualTo(meta().id) // modifiedAt moves on write
        assertThat(reopened.readItems(PageId(1))).hasSize(100)
        reopened.close()
    }
}
