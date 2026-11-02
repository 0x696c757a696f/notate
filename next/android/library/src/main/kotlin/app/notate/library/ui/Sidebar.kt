package app.notate.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notate.core.doc.library.LibraryLocation
import app.notate.core.doc.library.LibraryUiState
import app.notate.library.R

/** Left navigation of the bookshelf (04 §2): scopes, folder tree, tags, trash and settings. */
@Composable
fun Sidebar(
    state: LibraryUiState,
    onOpen: (LibraryLocation) -> Unit,
    onSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var foldersOpen by remember { mutableStateOf(false) }
    var tagsOpen by remember { mutableStateOf(false) }
    val loc = state.location
    Column(
        modifier.width(220.dp).fillMaxHeight().background(Color.White).border(width = 1.dp, color = Color(0xFF9E9E9E)).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
    ) {
        Item(Icons.Outlined.Home, stringResource(R.string.nav_all), loc == LibraryLocation.Folder(null)) { onOpen(LibraryLocation.Folder(null)) }
        Item(Icons.Outlined.History, stringResource(R.string.nav_recent), loc == LibraryLocation.Recent) { onOpen(LibraryLocation.Recent) }
        Item(Icons.Outlined.PushPin, stringResource(R.string.nav_pinned), loc == LibraryLocation.Pinned) { onOpen(LibraryLocation.Pinned) }
        Item(Icons.Outlined.Folder, stringResource(R.string.nav_folders), false, expandable = true, expanded = foldersOpen) { foldersOpen = !foldersOpen }
        if (foldersOpen) {
            flattenFolders(state.allFolders).forEach { (f, depth) ->
                Item(null, f.name, loc == LibraryLocation.Folder(f.id), indent = depth + 1) { onOpen(LibraryLocation.Folder(f.id)) }
            }
        }
        Item(Icons.Outlined.Label, stringResource(R.string.nav_tags), false, expandable = true, expanded = tagsOpen) { tagsOpen = !tagsOpen }
        if (tagsOpen) {
            if (state.tags.isEmpty()) Item(null, stringResource(R.string.nav_no_tags), false, indent = 1, enabled = false) {}
            state.tags.forEach { t -> Item(null, t.name, loc == LibraryLocation.Tag(t.id), indent = 1) { onOpen(LibraryLocation.Tag(t.id)) } }
        }
        Item(Icons.Outlined.MenuBook, stringResource(R.string.nav_books), loc == LibraryLocation.Books) { onOpen(LibraryLocation.Books) }
        Item(Icons.Outlined.PictureAsPdf, stringResource(R.string.nav_pdfs), loc == LibraryLocation.Pdfs) { onOpen(LibraryLocation.Pdfs) }
        Item(Icons.Outlined.Delete, stringResource(R.string.nav_trash), loc == LibraryLocation.Trash) { onOpen(LibraryLocation.Trash) }
        Item(Icons.Outlined.Settings, stringResource(R.string.nav_settings), false, enabled = onSettings != null) { onSettings?.invoke() }
    }
}

@Composable
private fun Item(
    icon: ImageVector?,
    label: String,
    selected: Boolean,
    indent: Int = 0,
    expandable: Boolean = false,
    expanded: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val fg = when {
        !enabled -> Color(0xFF9E9E9E)
        selected -> Color.White
        else -> Color.Black
    }
    Row(
        Modifier.fillMaxWidth().background(if (selected) Color.Black else Color.White).clickable(enabled = enabled, onClick = onClick)
            .padding(start = (16 + 16 * indent).dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(24.dp), tint = fg)
        Text(label, color = fg, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (expandable) Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = fg)
    }
}
