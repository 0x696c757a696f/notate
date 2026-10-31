package com.alexdremov.notate.doc

import kotlinx.coroutines.flow.StateFlow

/** Undo/redo of the document as the user sees it (the focused page for a book). */
interface UndoController {
    val canUndo: Boolean
    val canRedo: Boolean

    /** Increases whenever [canUndo] / [canRedo] may have changed, for UI that observes them. */
    val changes: StateFlow<Int>

    /** True if something was undone. */
    suspend fun undo(): Boolean

    suspend fun redo(): Boolean
}
