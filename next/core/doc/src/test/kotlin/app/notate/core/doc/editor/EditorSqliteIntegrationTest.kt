package app.notate.core.doc.editor

import app.notate.core.doc.edit.IdAllocator
import app.notate.core.doc.save.SaveQueue
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.edit.Edit
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.testing.FakeClock
import app.notate.core.testing.JdbcSqlDriverFactory
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EditorSqliteIntegrationTest {
    private val clock = FakeClock(1000)
    private val factory = SqliteDocStoreFactory(JdbcSqlDriverFactory(), { Dispatchers.IO }, clock)
    private val p1 = PageId(1)

    private suspend fun open(dir: File, create: Boolean): Pair<DocumentEditor, SaveQueue> {
        val store = if (create) factory.create(dir, meta(), listOf(page(1))) else factory.open(dir)
        val save = SaveQueue(store, Dispatchers.IO, clock)
        val editor = DocumentEditor(
            store.readMeta(), store.readPages(), store, save, IdAllocator(store.maxIds()), clock, Dispatchers.Unconfined, Dispatchers.IO,
        )
        return editor to save
    }

    @Test fun hundredStrokesSurviveReopenAndUndoPersists() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("editor-sqlite").toFile()
        val (e1, s1) = open(dir, create = true)
        e1.ensureLoaded(p1)
        val strokes = (1L..100L).map { stroke(it, points = floatArrayOf(it.toFloat(), 0f, it + 10f, 10f)) }
        strokes.forEach { e1.apply(Edit.Items(p1, emptyList(), listOf(it))) }
        s1.flush()
        s1.close()

        val (e2, s2) = open(dir, create = false)
        assertThat(e2.ensureLoaded(p1).all().map { it.id.value }.toSet()).isEqualTo((1L..100L).toSet())
        assertThat(e2.newItemId().value).isEqualTo(101L)
        assertThat(e2.content(p1)!![ItemId(7)]).isEqualTo(strokes[6])

        // Undo requires history from this session: erase one stroke, flush, reopen.
        e2.apply(Edit.Items(p1, listOf(strokes[99]), emptyList()))
        s2.flush(); s2.close()
        val (e3, s3) = open(dir, create = false)
        val ids = e3.ensureLoaded(p1).all().map { it.id.value }.toSet()
        assertThat(ids).hasSize(99)
        assertThat(ids).doesNotContain(100L)
        // Undo the last stroke in the same session as it was drawn.
        e3.apply(Edit.Items(p1, emptyList(), listOf(stroke(500))))
        e3.undo()
        s3.flush(); s3.close()
        val (e4, s4) = open(dir, create = false)
        assertThat(e4.ensureLoaded(p1).get(ItemId(500))).isNull()
        assertThat(e4.content(p1)!!.size).isEqualTo(99)
        s4.close()
    }
}
