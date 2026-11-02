package app.notate.library

import app.notate.core.model.DocId
import app.notate.core.store.library.Folder

/** The single dialog the bookshelf shows at a time (04 §7). */
sealed interface LibraryDialog {
    data object NewNotebook : LibraryDialog
    data object NewFolder : LibraryDialog
    data class RenameDocument(val id: DocId, val current: String) : LibraryDialog
    data class RenameFolder(val folder: Folder) : LibraryDialog
    data class MoveTo(val ids: Set<DocId>) : LibraryDialog
    data class Tags(val ids: Set<DocId>) : LibraryDialog
    data class DeleteFolder(val folder: Folder) : LibraryDialog
    data class DeleteNow(val ids: Set<DocId>) : LibraryDialog
    data object EmptyTrash : LibraryDialog
}
