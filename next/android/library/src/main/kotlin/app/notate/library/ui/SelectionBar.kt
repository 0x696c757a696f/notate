package app.notate.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notate.core.doc.library.LibraryLocation
import app.notate.core.doc.library.LibraryUiState
import app.notate.library.LibraryDialog
import app.notate.library.LibraryViewModel
import app.notate.library.R

/** Bottom bar of multi-select mode: Move, Tags, Trash (Restore and Delete now inside the Trash). */
@Composable
fun SelectionBar(state: LibraryUiState, vm: LibraryViewModel) {
    val ids = state.selection
    val has = ids.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(Color.White).border(1.dp, Color(0xFF9E9E9E)).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.location == LibraryLocation.Trash) {
            IconBtn(Icons.Outlined.RestoreFromTrash, stringResource(R.string.cd_restore), enabled = has) { vm.restore(ids) }
            IconBtn(Icons.Outlined.DeleteForever, stringResource(R.string.menu_delete_now), enabled = has) { vm.show(LibraryDialog.DeleteNow(ids)) }
        } else {
            IconBtn(Icons.Outlined.FolderOpen, stringResource(R.string.cd_move), enabled = has) { vm.show(LibraryDialog.MoveTo(ids)) }
            IconBtn(Icons.Outlined.Label, stringResource(R.string.cd_tags), enabled = has) { vm.show(LibraryDialog.Tags(ids)) }
            IconBtn(Icons.Outlined.Delete, stringResource(R.string.cd_trash), enabled = has) { vm.trash(ids) }
        }
    }
}
