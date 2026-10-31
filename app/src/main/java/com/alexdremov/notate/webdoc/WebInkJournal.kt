package com.alexdremov.notate.webdoc

import com.alexdremov.notate.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Append-only JSON Lines log of ink edits since the last checkpoint (`ink.journal`).
 *
 * Every [append] reaches the OS immediately, so killing the process loses nothing. `fsync` (which
 * protects against power loss) is throttled to once per [syncIntervalMs], so a crash costs at most
 * that much recent ink, and a burst of strokes does not hammer the flash.
 */
internal class WebInkJournal(
    private val file: File,
    private val syncIntervalMs: Long = 250,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "WebInkJournal"

        private val scheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "web-journal-sync").apply { isDaemon = true } }
    }

    private var out: FileOutputStream? = null
    private var lastSyncAt = 0L
    private var syncScheduled = false

    /** Highest sequence number appended since the last reset (0 if none). */
    @get:Synchronized
    var lastSeq = 0L
        private set

    /** Number of fsyncs performed; for tests and diagnostics. */
    @get:Synchronized
    var syncCount = 0
        private set

    @Synchronized
    fun append(
        seq: Long,
        line: String,
    ) {
        file.parentFile?.mkdirs()
        val stream = out ?: FileOutputStream(file, true).also { out = it }
        stream.write((line + "\n").toByteArray(Charsets.UTF_8))
        if (seq > lastSeq) lastSeq = seq
        val since = clock() - lastSyncAt
        if (since >= syncIntervalMs) {
            syncLocked()
        } else if (!syncScheduled) {
            syncScheduled = true
            scheduler.schedule({ syncFromTimer() }, syncIntervalMs - since, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    private fun syncFromTimer() {
        syncScheduled = false
        syncLocked()
    }

    private fun syncLocked() {
        try {
            out?.fd?.sync()
            syncCount++
        } catch (e: Exception) {
            Logger.w(TAG, "fsync failed", e)
        }
        lastSyncAt = clock()
    }

    /** Forces pending bytes to disk now. */
    @Synchronized
    fun sync() = syncLocked()

    /** Empties the journal if everything in it is covered by a checkpoint at [seq]. */
    @Synchronized
    fun resetIfCovered(seq: Long) {
        if (lastSeq > seq) return
        resetLocked()
    }

    @Synchronized
    fun reset() = resetLocked()

    private fun resetLocked() {
        try {
            out?.close()
        } catch (_: Exception) {
        }
        out = null
        lastSeq = 0L
        if (file.exists()) FileOutputStream(file, false).close()
    }

    @Synchronized
    fun close() {
        syncLocked()
        try {
            out?.close()
        } catch (_: Exception) {
        }
        out = null
    }
}
