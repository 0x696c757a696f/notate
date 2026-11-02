package app.notate.library.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notate.core.doc.library.LibraryUiState
import app.notate.core.model.DocId
import app.notate.library.LibraryDialog
import app.notate.library.LibraryViewModel
import app.notate.library.R

/** Renders whichever [LibraryDialog] is active and wires its result to [vm]. */
@Composable
fun LibraryDialogs(vm: LibraryViewModel, dialog: LibraryDialog?, state: LibraryUiState, onOpenDocument: (DocId) -> Unit) {
    val close = vm::dismissDialog
    when (dialog) {
        null -> Unit
        LibraryDialog.NewNotebook -> {
            val title by produceState<String?>(null) { value = vm.suggestTitle() }
            val t = title
            if (t != null) {
                val initial by vm.defaultTemplate.collectAsStateWithLifecycle()
                NewNotebookDialog(
                    suggestedTitle = t, initial = initial, onDismiss = close,
                    onCreate = { name, template -> close(); vm.createNotebook(name, template) { id -> onOpenDocument(id) } },
                )
            }
        }
        LibraryDialog.NewFolder -> RenameDialog(
            stringResource(R.string.dialog_new_folder_title), "New Folder", stringResource(R.string.dialog_create),
            onConfirm = { close(); vm.createFolder(it) }, onDismiss = close,
        )
        is LibraryDialog.RenameDocument -> RenameDialog(
            stringResource(R.string.dialog_rename_title), dialog.current, stringResource(R.string.dialog_ok),
            onConfirm = { close(); vm.rename(dialog.id, it) }, onDismiss = close,
        )
        is LibraryDialog.RenameFolder -> RenameDialog(
            stringResource(R.string.dialog_rename_folder_title), dialog.folder.name, stringResource(R.string.dialog_ok),
            onConfirm = { close(); vm.renameFolder(dialog.folder.id, it) }, onDismiss = close,
        )
        is LibraryDialog.MoveTo -> MoveToDialog(
            folders = state.allFolders,
            onMove = { close(); vm.move(dialog.ids, it) },
            onNewFolder = { name, parent, created -> vm.createFolderIn(name, parent, created) },
            onDismiss = close,
        )
        is LibraryDialog.Tags -> {
            val single = dialog.ids.singleOrNull()?.let { id -> state.documents.firstOrNull { it.id == id } }
            TagsDialog(
                tags = state.tags, initial = single?.tagIds.orEmpty(),
                onCreateTag = { name, color, created -> vm.createTag(name, color, created) },
                onApply = { checked ->
                    close()
                    if (single != null) vm.setTags(single.id, checked) else vm.addTags(dialog.ids, checked)
                },
                onDismiss = close,
            )
        }
        is LibraryDialog.DeleteFolder -> ConfirmDialog(
            stringResource(R.string.confirm_delete_folder, dialog.folder.name), stringResource(R.string.confirm_delete),
            onConfirm = { close(); vm.deleteFolder(dialog.folder.id) }, onDismiss = close,
        )
        is LibraryDialog.DeleteNow -> ConfirmDialog(
            stringResource(R.string.confirm_delete_now), stringResource(R.string.confirm_delete),
            onConfirm = { close(); vm.purge(dialog.ids) }, onDismiss = close,
        )
        LibraryDialog.EmptyTrash -> ConfirmDialog(
            stringResource(R.string.confirm_empty_trash), stringResource(R.string.trash_empty_action),
            onConfirm = { close(); vm.emptyTrash() }, onDismiss = close,
        )
    }
}
