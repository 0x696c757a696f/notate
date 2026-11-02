package app.notate.core.store

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
import java.nio.file.Files
import java.util.Random

/** Prints timings only; no timing assertions (CI is noisy). */
class StoreBenchmark {
    @Test fun writeAndReadTenThousandStrokes() = runBlocking<Unit> {
        val store = SqliteDocStoreFactory(JdbcSqlDriverFactory(), { Dispatchers.IO }, FakeClock())
            .create(Files.createTempDirectory("bench").toFile(), meta(), listOf(page(1)))
        val rnd = Random(3)
        val strokes = (1L..10_000L).map { id ->
            stroke(id, points = FloatArray(60) { rnd.nextFloat() * 500f })
        }
        val t0 = System.nanoTime()
        store.write(listOf(Edit.Items(PageId(1), emptyList(), strokes)))
        val t1 = System.nanoTime()
        val back = store.readItems(PageId(1))
        val t2 = System.nanoTime()
        println("StoreBenchmark: write 10k strokes ${(t1 - t0) / 1_000_000} ms, read ${(t2 - t1) / 1_000_000} ms")
        assertThat(back).hasSize(10_000)
        store.close()
    }
}
