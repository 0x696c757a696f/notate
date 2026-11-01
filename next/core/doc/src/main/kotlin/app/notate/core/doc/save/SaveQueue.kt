package app.notate.core.doc.save

import app.notate.core.model.Clock
import app.notate.core.model.edit.Edit
import app.notate.core.store.DocStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

sealed interface SaveState {
    data object Clean : SaveState
    data class Pending(val count: Int) : SaveState
    data object Writing : SaveState
    data class Error(val message: String) : SaveState
}

/** Thrown by [SaveQueue.flush] when edits could not be written. */
class SaveFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Write-behind queue: edits are coalesced for [coalesceMs] and written in one [DocStore.write] transaction on [io].
 * [enqueue] is O(1) and thread safe. A failed batch is retried unchanged with backoff, so batches are never lost or reordered.
 */
class SaveQueue(
    private val store: DocStore,
    io: CoroutineDispatcher,
    @Suppress("unused") private val clock: Clock,
    private val coalesceMs: Long = 200,
) {
    private class Pending(val edit: Edit, val rev: Long)

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val channel = Channel<Pending>(Channel.UNLIMITED)
    private val kick = Channel<Unit>(Channel.CONFLATED)       // flush: stop coalescing now
    private val retrySignal = Channel<Unit>(Channel.CONFLATED) // retryNow: skip the backoff wait
    private val pendingCount = AtomicInteger()
    private val enqueuedRev = AtomicLong()

    private val _state = MutableStateFlow<SaveState>(SaveState.Clean)
    private val _persisted = MutableStateFlow(0L)
    val state: StateFlow<SaveState> get() = _state
    /** Largest rev known to be on disk. */
    val persistedRev: StateFlow<Long> get() = _persisted

    init { scope.launch { writerLoop() } }

    /** Queues [edit] (made at editor revision [rev]). Revs must be non-decreasing. */
    fun enqueue(edit: Edit, rev: Long) {
        enqueuedRev.set(rev)
        val n = pendingCount.incrementAndGet()
        _state.update { if (it is SaveState.Writing || it is SaveState.Error) it else SaveState.Pending(n) }
        channel.trySend(Pending(edit, rev))
    }

    /** Retries a failed write right away instead of waiting for the backoff. */
    fun retryNow() { retrySignal.trySend(Unit) }

    /**
     * Suspends until everything enqueued before this call is on disk.
     * @throws SaveFailedException when the queue is in the error state.
     */
    suspend fun flush() {
        val target = enqueuedRev.get()
        if (_persisted.value >= target) return
        kick.trySend(Unit)
        val (p, s) = combine(_persisted, _state) { p, s -> p to s }.first { (p, s) -> p >= target || s is SaveState.Error }
        if (p < target) throw SaveFailedException((s as SaveState.Error).message)
    }

    /** Stops the writer; call after a final [flush]. */
    fun close() { scope.cancel(); channel.close() }

    private suspend fun writerLoop() {
        for (first in channel) {
            val batch = ArrayList<Pending>()
            batch.add(first)
            withTimeoutOrNull(coalesceMs) { kick.receive() }
            while (true) batch.add(channel.tryReceive().getOrNull() ?: break)
            writeWithRetry(batch)
        }
    }

    private suspend fun writeWithRetry(batch: List<Pending>) {
        var attempt = 0
        while (true) {
            _state.value = SaveState.Writing
            try {
                store.write(batch.map { it.edit })
                _persisted.value = maxOf(_persisted.value, batch.maxOf { it.rev })
                val left = pendingCount.addAndGet(-batch.size)
                _state.value = if (left > 0) SaveState.Pending(left) else SaveState.Clean
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SaveState.Error(e.message ?: e.javaClass.simpleName)
                val wait = BACKOFF_MS[minOf(attempt, BACKOFF_MS.size - 1)]
                attempt++
                withTimeoutOrNull(wait) { retrySignal.receive() }
            }
        }
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
    }
}
