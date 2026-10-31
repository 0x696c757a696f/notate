package com.alexdremov.notate.doc

/** Anything [SaveCoordinator] can save: a [DocumentSession], or a plain wrapper around an existing save routine. */
interface Saveable {
    val ref: DocRef

    /** Writes the in-memory edits to disk. Idempotent; may be called concurrently. */
    suspend fun flush()
}

/** A [Saveable] that runs [save]; for screens that still own their save routine. */
class LambdaSaveable(
    override val ref: DocRef,
    private val save: suspend () -> Unit,
) : Saveable {
    override suspend fun flush() = save()
}
