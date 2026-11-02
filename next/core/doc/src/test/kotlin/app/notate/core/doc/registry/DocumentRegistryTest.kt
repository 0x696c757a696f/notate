package app.notate.core.doc.registry

import app.notate.core.model.DocId
import app.notate.core.model.PageId
import app.notate.core.model.edit.Edit
import app.notate.core.store.MaxIds
import app.notate.core.testing.FakeClock
import app.notate.core.testing.FakeDocStore
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentRegistryTest {
    private class Opener : DocumentOpener {
        var opens = 0
        val stores = HashMap<DocId, FakeDocStore>()
        override suspend fun open(id: DocId): Opened {
            opens++
            val s = stores.getOrPut(id) { FakeDocStore(meta(id.value), listOf(page(1))) }
            return Opened(s, s.readMeta(), s.readPages(), MaxIds(1, 0, emptyMap()))
        }
    }

    private fun TestScope.registry(o: Opener): DocumentRegistry {
        val d = StandardTestDispatcher(testScheduler)
        return DocumentRegistry(o, backgroundScope, FakeClock(), d, d)
    }

    private val idA = DocId("01TESTDOC000000000000000A")
    private val idB = DocId("01TESTDOC000000000000000B")

    @Test fun concurrentAcquireOpensOnce() = runTest {
        val o = Opener(); val reg = registry(o)
        val a = async { reg.acquire(idA) }; val b = async { reg.acquire(idA) }
        runCurrent()
        assertThat(a.await()).isSameInstanceAs(b.await())
        assertThat(o.opens).isEqualTo(1)
    }

    @Test fun closesOnlyWhenLastReleased() = runTest {
        val o = Opener(); val reg = registry(o)
        val h1 = reg.acquire(idA); val h2 = reg.acquire(idA)
        reg.release(h1); runCurrent()
        assertThat(o.stores.getValue(idA).closed).isFalse()
        reg.release(h2); runCurrent()
        assertThat(o.stores.getValue(idA).closed).isTrue()
        val again = reg.acquire(idA)
        assertThat(again).isNotSameInstanceAs(h1)
        assertThat(o.opens).isEqualTo(2)
    }

    @Test fun flushesBeforeClose() = runTest {
        val o = Opener(); val reg = registry(o)
        val h = reg.acquire(idA)
        h.editor.ensureLoaded(PageId(1))
        h.editor.apply(Edit.Items(PageId(1), emptyList(), listOf(stroke(1))))
        reg.release(h)
        advanceTimeBy(1); runCurrent()
        val s = o.stores.getValue(idA)
        assertThat(s.writes).hasSize(1)
        assertThat(s.closed).isTrue()
    }

    @Test fun reacquireDuringCloseCancelsIt() = runTest {
        val o = Opener(); val reg = registry(o)
        val h = reg.acquire(idA)
        h.editor.ensureLoaded(PageId(1))
        h.editor.apply(Edit.Items(PageId(1), emptyList(), listOf(stroke(1))))
        reg.release(h)
        val h2 = reg.acquire(idA)          // before the close job gets to run
        advanceTimeBy(500); runCurrent()
        assertThat(h2).isSameInstanceAs(h)
        assertThat(o.stores.getValue(idA).closed).isFalse()
        assertThat(o.opens).isEqualTo(1)
    }

    @Test fun flushAllReportsFailuresWithoutStoppingOthers() = runTest {
        val o = Opener(); val reg = registry(o)
        val a = reg.acquire(idA); val b = reg.acquire(idB)
        for (h in listOf(a, b)) {
            h.editor.ensureLoaded(PageId(1))
            h.editor.apply(Edit.Items(PageId(1), emptyList(), listOf(stroke(1))))
        }
        o.stores.getValue(idA).failNext = IOException("nope")
        val r = async { reg.flushAll() }
        runCurrent(); advanceTimeBy(300); runCurrent()
        val failures = r.await()
        assertThat(failures.map { it.id }).containsExactly(idA)
        assertThat(b.save.persistedRev.value).isEqualTo(1)
    }

    @Test fun failedFlushKeepsDocumentOpen() = runTest {
        val o = Opener(); val reg = registry(o)
        val h = reg.acquire(idA)
        h.editor.ensureLoaded(PageId(1))
        h.editor.apply(Edit.Items(PageId(1), emptyList(), listOf(stroke(1))))
        o.stores.getValue(idA).failNext = IOException("nope")
        reg.release(h)
        advanceTimeBy(300); runCurrent()
        assertThat(o.stores.getValue(idA).closed).isFalse()
        assertThat(reg.acquire(idA)).isSameInstanceAs(h)
    }
}
