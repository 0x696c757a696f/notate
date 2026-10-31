package com.alexdremov.notate.doc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SaveCoordinatorTest {
    private fun coordinator() =
        SaveCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            debounceMs = 60,
            retryBaseMs = 40,
            retryMaxMs = 80,
            prepareContext = Dispatchers.Unconfined,
        )

    private fun doc(
        id: String,
        save: suspend () -> Unit,
    ) = LambdaSaveable(DocRef(DocKind.WEB, id), save)

    private suspend fun waitFor(
        ms: Long = 2000,
        cond: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < end) delay(5)
    }

    @Test
    fun `a burst of changes becomes one save`() =
        runBlocking {
            val c = coordinator()
            val saves = AtomicInteger()
            val d = doc("a") { saves.incrementAndGet() }
            repeat(20) {
                c.markDirty(d)
                delay(5)
            }
            assertEquals(SaveState.Dirty, c.states.value["a"])
            waitFor { c.states.value["a"] is SaveState.Clean }
            assertEquals(1, saves.get())
        }

    @Test
    fun `saves of one document never overlap`() =
        runBlocking {
            val c = coordinator()
            val running = AtomicInteger()
            var overlap = false
            val d =
                doc("a") {
                    if (running.incrementAndGet() > 1) overlap = true
                    delay(80)
                    running.decrementAndGet()
                }
            c.markDirty(d)
            delay(90) // save in progress
            c.markDirty(d)
            c.flush(d)
            waitFor { c.states.value["a"] is SaveState.Clean }
            assertFalse(overlap)
        }

    @Test
    fun `an edit during a save leaves the document dirty and saves again`() =
        runBlocking {
            val c = coordinator()
            val saves = AtomicInteger()
            val d =
                doc("a") {
                    saves.incrementAndGet()
                    delay(100)
                }
            c.markDirty(d)
            delay(90)
            c.markDirty(d)
            waitFor(3000) { saves.get() >= 2 && c.states.value["a"] is SaveState.Clean }
            assertEquals(2, saves.get())
        }

    @Test
    fun `a failing save shows an error then retries until it works`() =
        runBlocking {
            val c = coordinator()
            val attempts = AtomicInteger()
            val d =
                doc("a") {
                    if (attempts.incrementAndGet() < 3) throw java.io.IOException("disk full")
                }
            c.markDirty(d)
            waitFor { c.states.value["a"] is SaveState.Error }
            assertTrue(c.states.value["a"] is SaveState.Error)
            waitFor { c.states.value["a"] is SaveState.Clean }
            assertEquals(SaveState.Clean, c.states.value["a"])
            assertEquals(3, attempts.get())
        }

    @Test
    fun `flushAll waits for every document and prepare runs first`() =
        runBlocking {
            val c = coordinator()
            val order = java.util.Collections.synchronizedList(mutableListOf<String>())
            val a = doc("a") { delay(50); order += "save-a" }
            val b = doc("b") { delay(30); order += "save-b" }
            c.markDirty(a) { order += "prep-a" }
            c.markDirty(b)
            c.flushAll()
            assertEquals(SaveState.Clean, c.states.value["a"])
            assertEquals(SaveState.Clean, c.states.value["b"])
            assertTrue(order.indexOf("prep-a") < order.indexOf("save-a"))
            assertEquals(3, order.size)
        }

    @Test
    fun `flushWithin gives up on a slow save and reports false`() =
        runBlocking {
            val c = coordinator()
            val d = doc("a") { delay(1000) }
            c.markDirty(d)
            assertFalse(c.flushWithin(d, 100))
        }

    @Test
    fun `unregister forgets the document`() =
        runBlocking {
            val c = coordinator()
            val d = doc("a") {}
            c.markDirty(d)
            c.flush(d)
            c.unregister("a")
            assertNull(c.states.value["a"])
        }
}
