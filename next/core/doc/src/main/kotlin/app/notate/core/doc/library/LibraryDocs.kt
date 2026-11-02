package app.notate.core.doc.library

import app.notate.core.model.DocId
import app.notate.core.model.PageTemplate
import app.notate.core.store.library.DocumentEntry

/**
 * File-level document operations the library needs; implemented by the app on top of the document stores. The
 * controller owns the library index rows, so none of these touch `library.db`.
 */
interface LibraryDocs {
    /** Creates a notebook package on disk and returns its index row (no folder, not trashed). */
    suspend fun createNotebook(title: String, template: PageTemplate): DocumentEntry

    /** Copies the document package (flushing it first when it is open) under a new id and [title]; returns its index row. */
    suspend fun duplicate(id: DocId, title: String): DocumentEntry

    /** Writes the new title into the document itself so a rebuilt library keeps it. */
    suspend fun rename(id: DocId, title: String)

    /** Deletes the document's directory. Succeeds when it is already gone. */
    suspend fun deleteFiles(id: DocId)
}
