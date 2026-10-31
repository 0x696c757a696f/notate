package com.alexdremov.notate.doc

import kotlinx.coroutines.flow.StateFlow

/**
 * One open document, whichever kind: the thing tabs, panes, undo buttons and the save machinery talk to.
 * Process-wide there is one session per document, however many panes show it.
 */
interface DocumentSession {
    val ref: DocRef
    val capabilities: Set<Capability>
    val undo: UndoController
    val saveState: StateFlow<SaveState>

    /** Writes the in-memory edits to disk. Idempotent; may be called concurrently. */
    suspend fun flush()

    /** Flushes and releases the session's resources. */
    suspend fun close()
}
