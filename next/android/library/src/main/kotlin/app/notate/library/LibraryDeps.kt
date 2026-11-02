package app.notate.library

import app.notate.core.doc.library.LibraryDocs
import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.PageTemplate
import app.notate.core.model.log.Logger
import app.notate.core.store.library.LibraryStore
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Everything the bookshelf needs from the app (the library module cannot see `:next:app`). [store] must already be open. */
class LibraryDeps(
    val store: LibraryStore,
    val docs: LibraryDocs,
    val clock: Clock,
    val logger: Logger,
    /** `thumbs/cover.webp` of a document. */
    val coverFile: (DocId) -> File,
    /** The user's "Default template" setting (04 §9). */
    val defaultTemplate: StateFlow<PageTemplate>,
    /** Called on the main thread after a notebook was created, e.g. to render its first cover. */
    val onNotebookCreated: (DocId) -> Unit = {},
)
