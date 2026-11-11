package app.notate.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.notate.core.doc.library.LibraryController
import app.notate.core.doc.library.LibraryLocation
import app.notate.core.doc.library.LibraryUiState
import app.notate.core.model.DocId
import app.notate.core.model.PageTemplate
import app.notate.core.store.library.Folder
import app.notate.core.store.library.Sort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Bookshelf state holder; the logic lives in [LibraryController] (pure Kotlin, tested on the JVM). Main thread only. */
class LibraryViewModel(private val deps: LibraryDeps) : ViewModel() {
    private val controller = LibraryController(viewModelScope, deps.store, deps.docs, deps.clock)

    val state: StateFlow<LibraryUiState> = controller.state
    val defaultTemplate: StateFlow<PageTemplate> = deps.defaultTemplate

    private val _dialog = MutableStateFlow<LibraryDialog?>(null)
    val dialog: StateFlow<LibraryDialog?> = _dialog.asStateFlow()

    /** Set when an action failed; the screen shows it and calls [dismissError]. */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    fun show(dialog: LibraryDialog) { _dialog.value = dialog }
    fun dismissDialog() { _dialog.value = null }
    fun dismissError() { _failed.value = false }

    fun open(location: LibraryLocation) = controller.open(location)
    fun openFolder(id: String?) = controller.openFolder(id)
    fun up() = controller.up()
    fun setSort(sort: Sort) = controller.setSort(sort)
    fun setFilter(text: String) = controller.setFilter(text)
    fun toggleSelect(id: DocId) = controller.toggleSelect(id)
    fun selectAll() = controller.selectAll()
    fun clearSelection() = controller.clearSelection()
    fun startSelecting() = controller.startSelecting()

    /** Free default title for the New notebook dialog. */
    suspend fun suggestTitle(): String = controller.suggestNotebookTitle()

    /** Creates a notebook and reports its id on the main thread; failures show the error banner. */
    fun createNotebook(title: String, template: PageTemplate, onCreated: (DocId) -> Unit) = run("create notebook") {
        val id = controller.createNotebook(title, template)
        deps.onNotebookCreated(id)
        onCreated(id)
    }

    fun createFolder(name: String = LibraryController.DEFAULT_FOLDER) = run("create folder") { controller.createFolder(name) }

    /** Creates a folder under [parent] and hands it to [onCreated] (used by "Move to…"). */
    fun createFolderIn(name: String, parent: String?, onCreated: (Folder) -> Unit) = run("create folder") {
        onCreated(controller.createFolder(name, parent))
    }

    fun rename(id: DocId, title: String) = run("rename") { controller.rename(id, title) }
    fun renameFolder(id: String, name: String) = run("rename folder") { controller.renameFolder(id, name) }
    fun move(ids: Collection<DocId>, folder: String?) = run("move") { controller.move(ids, folder) }
    fun deleteFolder(id: String) = run("delete folder") { controller.deleteFolder(id) }
    fun setTags(id: DocId, tagIds: Set<String>) = run("set tags") { controller.setTags(id, tagIds) }
    fun addTags(ids: Collection<DocId>, tagIds: Set<String>) = run("add tags") { tagIds.forEach { controller.addTag(ids, it) } }
    fun createTag(name: String, color: Int, onCreated: (String) -> Unit) = run("create tag") { onCreated(controller.createTag(name, color).id) }
    fun duplicate(ids: Collection<DocId>) = run("duplicate") { controller.duplicate(ids) }
    fun trash(ids: Collection<DocId>) = run("trash") { controller.trash(ids) }
    fun restore(ids: Collection<DocId>) = run("restore") { controller.restore(ids) }
    fun purge(ids: Collection<DocId>) = run("delete") { controller.purge(ids) }
    fun emptyTrash() = run("empty trash") { controller.emptyTrash() }
    fun togglePin(id: DocId) = run("pin") { controller.togglePin(id) }
    fun markOpened(id: DocId) = run("mark opened") { controller.markOpened(id) }

    private fun run(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.logger.e(TAG, "Library action failed: $what", e)
                _failed.value = true
            }
        }
    }

    class Factory(private val deps: LibraryDeps) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(deps) as T
    }

    private companion object { const val TAG = "LibraryViewModel" }
}
