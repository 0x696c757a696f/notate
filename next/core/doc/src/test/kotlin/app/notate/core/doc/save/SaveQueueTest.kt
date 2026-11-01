package app.notate.core.doc.save

import app.notate.core.model.PageId
import app.notate.core.model.edit.Edit
import app.notate.core.testing.FakeClock
import app.notate.core.testing.FakeDocStore
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SaveQueueTest {
    private fun e(id: Long) = Edit.Items(PageId(1), emptyList(), listOf(stroke(id)))

    private fun TestScope.setup(): Pair<FakeDocStore, SaveQueue> {
        val store = FakeDocStore(meta(), listOf(page(1)))
        return store to SaveQueue(store, StandardTestDispatcher(testScheduler), FakeClock())
    }

    @Test fun coalescesWithinWindow() = runTest {
        val (store, q) = setup()
        q.enqueue(e(1), 1); advanceTimeBy(50); q.enqueue(e(2), 2); advanceTimeBy(50); q.enqueue(e(3), 3)
        assertThat(q.state.value).isEqualTo(SaveState.Pending(3))
        advanceTimeBy(100); runCurrent()
        assertThat(store.writes).containsExactly(listOf(e(1), e(2), e(3)))
        assertThat(q.persistedRev.value).isEqualTo(3)
        assertThat(q.state.value).isEqualTo(SaveState.Clean)
        q.close()
    }

    @Test fun failureBacksOffThenSucceedsInOrder() = runTest {
        val (store, q) = setup()
        store.failNext = IOException("disk full")
        q.enqueue(e(1), 1)
        advanceTimeBy(201); runCurrent()
        assertThat(q.state.value).isEqualTo(SaveState.Error("disk full"))
        q.enqueue(e(2), 2)
        advanceTimeBy(998); runCurrent()
        assertThat(store.writes).hasSize(1)
        advanceTimeBy(3); runCurrent()
        advanceTimeBy(300); runCurrent()
        assertThat(store.writes).containsExactly(listOf(e(1)), listOf(e(1)), listOf(e(2))).inOrder()
        assertThat(q.state.value).isEqualTo(SaveState.Clean)
        assertThat(q.persistedRev.value).isEqualTo(2)
        q.close()
    }

    @Test fun retryNowSkipsBackoff() = runTest {
        val (store, q) = setup()
        store.failNext = IOException("x")
        q.enqueue(e(1), 1)
        advanceTimeBy(201); runCurrent()
        q.retryNow(); runCurrent()
        assertThat(q.state.value).isEqualTo(SaveState.Clean)
        assertThat(store.writes).hasSize(2)
        q.close()
    }

    @Test fun flushWaitsForWrite() = runTest {
        val (store, q) = setup()
        q.enqueue(e(1), 1)
        var done = false
        launch { q.flush(); done = true }
        runCurrent()
        // flush cuts the coalescing wait short, but nothing completes before the writer runs.
        advanceTimeBy(1); runCurrent()
        assertThat(done).isTrue()
        assertThat(store.writes).hasSize(1)
        q.close()
    }

    @Test fun flushOnCleanQueueReturnsImmediately() = runTest {
        val (_, q) = setup()
        q.flush()
        q.close()
    }

    @Test fun flushThrowsWhenFailing() = runTest {
        val (store, q) = setup()
        store.failNext = IOException("boom")
        q.enqueue(e(1), 1)
        var error: Throwable? = null
        launch { try { q.flush() } catch (t: Throwable) { error = t } }
        advanceTimeBy(300); runCurrent()
        assertThat(error).isInstanceOf(SaveFailedException::class.java)
        assertThat(error!!.message).isEqualTo("boom")
        assertThrows(SaveFailedException::class.java) { kotlinx.coroutines.runBlocking { q.flush() } }
        q.close()
    }
}
