package com.alexdremov.notate.doc

import kotlinx.coroutines.flow.StateFlow

/**
 * One open document, whichever kind: the thing tabs, panes, undo buttons and the save machinery talk to.
 * Process-wide there is one session per document, however many panes show it.
 */
interface DocumentSession : Saveable {
    val capabilities: Set<Capability>
    val undo: UndoController
    val saveState: StateFlow<SaveState>

    /** Flushes and releases the session's resources. */
    suspend fun close()
}
