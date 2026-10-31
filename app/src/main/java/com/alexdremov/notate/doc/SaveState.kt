package com.alexdremov.notate.doc

/** Where a document's edits are on their way to disk. */
sealed interface SaveState {
    /** Memory and disk agree. */
    data object Clean : SaveState

    /** Edits in memory not yet written. */
    data object Dirty : SaveState

    data object Saving : SaveState

    /** The last write failed; the edits are still in memory and will be retried. */
    data class Error(
        val message: String,
    ) : SaveState
}
