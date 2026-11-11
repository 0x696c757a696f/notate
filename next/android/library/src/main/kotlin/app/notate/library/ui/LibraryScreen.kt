package app.notate.library.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notate.core.doc.library.LibraryLocation
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.library.LibraryDialog
import app.notate.library.LibraryViewModel
import app.notate.library.R

/**
 * The bookshelf (04 §2). [onOpenDocument] starts the editor; [extraAdd] are developer entries appended to the ＋ menu and
 * [onSidebarLongClick] is the hidden debug-HUD toggle.
 */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    covers: CoverLoader,
    onOpenDocument: (DocId) -> Unit,
    onSettings: (() -> Unit)? = null,
    onSidebarLongClick: () -> Unit = {},
    extraAdd: List<Pair<String, () -> Unit>> = emptyList(),
    onExportNotate: ((id: DocId, title: String) -> Unit)? = null,
    onExport: ((id: DocId, title: String) -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var sidebar by remember(landscape) { mutableStateOf(landscape) }
    val atRoot = state.location == LibraryLocation.Folder(null)

    BackHandler(enabled = state.selecting || !atRoot || state.filter.isNotEmpty()) {
        when {
            state.selecting -> vm.clearSelection()
            state.filter.isNotEmpty() -> vm.setFilter("")
            state.canGoUp -> vm.up()
            else -> vm.openFolder(null)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        LibraryTopBar(
            state = state,
            onSidebar = { sidebar = !sidebar },
            onSidebarLongClick = onSidebarLongClick,
            onFilter = vm::setFilter,
            onSort = vm::setSort,
            onStartSelecting = vm::startSelecting,
            onNewNotebook = { vm.show(LibraryDialog.NewNotebook) },
            onNewFolder = { vm.show(LibraryDialog.NewFolder) },
            onUp = vm::up,
            onSelectAll = vm::selectAll,
            onDoneSelecting = vm::clearSelection,
            extraAdd = extraAdd,
        )
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (sidebar) Sidebar(state, vm::open, onSettings)
            Column(Modifier.weight(1f)) {
                if (state.location == LibraryLocation.Trash) TrashBanner(state.documents.isNotEmpty()) { vm.show(LibraryDialog.EmptyTrash) }
                Box(Modifier.weight(1f)) { Shelf(vm, covers, state, onOpenDocument, onExportNotate, onExport) }
                if (state.selecting) SelectionBar(state, vm)
            }
        }
        if (failed) FailedBanner(vm::dismissError)
    }
    LibraryDialogs(vm, dialog, state, onOpenDocument)
}

@Composable
private fun TrashBanner(canEmpty: Boolean, onEmpty: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.trash_notice), color = Color(0xFF5F5F5F), fontSize = 14.sp, modifier = Modifier.weight(1f))
        DialogButton(stringResource(R.string.trash_empty_action), enabled = canEmpty, onClick = onEmpty)
    }
}

@Composable
private fun FailedBanner(onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.library_failed), color = Color.White, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.dialog_ok), color = Color.White, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
    }
}

@Composable
private fun Shelf(
    vm: LibraryViewModel, covers: CoverLoader, state: app.notate.core.doc.library.LibraryUiState, onOpen: (DocId) -> Unit,
    onExportNotate: ((DocId, String) -> Unit)?,
    onExport: ((DocId, String) -> Unit)? = null,
) {
    val now = remember(state.documents) { System.currentTimeMillis() }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val trash = state.location == LibraryLocation.Trash
    if (state.loaded && state.documents.isEmpty() && state.folders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(
                    when {
                        trash -> R.string.library_empty_trash
                        state.canGoUp -> R.string.library_empty_folder
                        else -> R.string.library_empty
                    },
                ),
                color = Color(0xFF5F5F5F), fontSize = 16.sp,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp), modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        items(state.folders, key = { "f" + it.id }) { f ->
            FolderTile(
                f, onClick = { vm.openFolder(f.id) }, onLongClick = { menuFor = "f" + f.id },
                menu = {
                    if (menuFor == "f" + f.id) MenuPopup({ menuFor = null }) {
                        MenuItem(stringResource(R.string.menu_rename)) { menuFor = null; vm.show(LibraryDialog.RenameFolder(f)) }
                        MenuItem(stringResource(R.string.menu_delete_folder)) { menuFor = null; vm.show(LibraryDialog.DeleteFolder(f)) }
                    }
                },
            )
        }
        items(state.documents, key = { it.id.value }) { d ->
            val selected = d.id in state.selection
            DocumentTile(
                d, now, selected, covers,
                onClick = { if (state.selecting) vm.toggleSelect(d.id) else if (!trash) onOpen(d.id) },
                onLongClick = { if (state.selecting) vm.toggleSelect(d.id) else menuFor = d.id.value },
                menu = {
                    if (menuFor == d.id.value) MenuPopup({ menuFor = null }) {
                        fun close(action: () -> Unit) = { menuFor = null; action() }
                        if (trash) {
                            MenuItem(stringResource(R.string.menu_restore), onClick = close { vm.restore(listOf(d.id)) })
                            MenuItem(stringResource(R.string.menu_delete_now), onClick = close { vm.show(LibraryDialog.DeleteNow(setOf(d.id))) })
                        } else {
                            MenuItem(stringResource(R.string.menu_open), onClick = close { onOpen(d.id) })
                            MenuItem(stringResource(R.string.menu_rename), onClick = close { vm.show(LibraryDialog.RenameDocument(d.id, d.title)) })
                            MenuItem(stringResource(R.string.menu_move), onClick = close { vm.show(LibraryDialog.MoveTo(setOf(d.id))) })
                            MenuItem(stringResource(R.string.menu_tags), onClick = close { vm.show(LibraryDialog.Tags(setOf(d.id))) })
                            MenuItem(stringResource(R.string.menu_duplicate), onClick = close { vm.duplicate(listOf(d.id)) })
                            if (onExport != null && d.kind != DocKind.WEB) MenuItem(stringResource(R.string.menu_export_pdf_png), onClick = close { onExport(d.id, d.title) })
                            if (onExportNotate != null) MenuItem(stringResource(R.string.menu_export_notate), onClick = close { onExportNotate(d.id, d.title) })
                            MenuItem(stringResource(if (d.pinned) R.string.menu_unpin else R.string.menu_pin), onClick = close { vm.togglePin(d.id) })
                            MenuItem(stringResource(R.string.menu_trash), onClick = close { vm.trash(listOf(d.id)) })
                        }
                    }
                },
            )
        }
    }
}
