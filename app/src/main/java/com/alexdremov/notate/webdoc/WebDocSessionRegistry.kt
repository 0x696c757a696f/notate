package com.alexdremov.notate.webdoc

/** The web document's session: one per document in the process, shared by every pane showing it. */
typealias WebSession = WebAnnotationStore

/**
 * Hands out the single [WebSession] of a web document (reference counted). The last [release] flushes
 * to disk and lets the in-memory copy go; the next [acquire] loads it again.
 */
object WebDocSessionRegistry {
    fun acquire(
        library: WebDocLibrary,
        docId: String,
        title: String? = null,
    ): WebSession =
        WebAnnotationStore.acquire(library, docId).also { if (title != null) it.title = title }

    fun release(session: WebSession) = WebAnnotationStore.release(session)
}
