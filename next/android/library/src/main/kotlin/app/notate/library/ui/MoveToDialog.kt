package app.notate.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notate.core.store.library.Folder
import app.notate.library.R

/** Folders in tree order with their nesting depth. */
fun flattenFolders(all: List<Folder>): List<Pair<Folder, Int>> {
    val byParent = all.groupBy { it.parentId }
    val out = ArrayList<Pair<Folder, Int>>()
    fun walk(parent: String?, depth: Int) {
        for (f in byParent[parent].orEmpty()) {
            if (out.size > all.size) return
            out += f to depth
            walk(f.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}

/** Folder picker with "New folder" (created inside the highlighted folder). [onNewFolder] returns the created folder through its callback. */
@Composable
fun MoveToDialog(
    folders: List<Folder>,
    onMove: (String?) -> Unit,
    onNewFolder: (name: String, parent: String?, created: (Folder) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var target by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    val rows = remember(folders) { flattenFolders(folders) }
    if (naming) {
        RenameDialog(
            stringResource(R.string.move_new_folder), "New Folder", stringResource(R.string.dialog_create),
            onConfirm = { name -> naming = false; onNewFolder(name, target) { target = it.id } },
            onDismiss = { naming = false },
        )
    }
    NotateDialog(
        title = stringResource(R.string.move_title), onDismiss = onDismiss,
        buttons = {
            DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss)
            DialogButton(stringResource(R.string.move_here), filled = true) { onMove(target) }
        },
    ) {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            PickRow(stringResource(R.string.move_top_level), 0, target == null) { target = null }
            rows.forEach { (f, depth) -> PickRow(f.name, depth + 1, target == f.id) { target = f.id } }
        }
        Row(Modifier.clickable { naming = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(24.dp))
            Text(stringResource(R.string.move_new_folder), fontSize = 16.sp)
        }
    }
}

@Composable
private fun PickRow(name: String, depth: Int, selected: Boolean, onClick: () -> Unit) {
    val fg = if (selected) Color.White else Color.Black
    Row(
        Modifier.fillMaxWidth().background(if (selected) Color.Black else Color.White).clickable(onClick = onClick)
            .padding(start = (12 + 20 * depth).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Folder, null, Modifier.size(22.dp), tint = fg)
        Text(name, color = fg, fontSize = 16.sp, maxLines = 1)
    }
}
