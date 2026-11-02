package app.notate.library.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notate.core.doc.library.LibraryLocation
import app.notate.core.doc.library.LibraryUiState
import app.notate.core.store.library.Sort
import app.notate.library.R

/** 48 dp icon button; inverts while pressed (e-ink has no ripple). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconBtn(icon: ImageVector, description: String, enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier.size(48.dp).background(if (pressed) Color.Black else Color.White)
            .combinedClickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(28.dp), tint = if (!enabled) Color(0xFF9E9E9E) else if (pressed) Color.White else Color.Black)
    }
}

private val SORTS = listOf(
    Sort.MODIFIED to R.string.sort_modified, Sort.CREATED to R.string.sort_created, Sort.NAME to R.string.sort_name, Sort.OPENED to R.string.sort_opened,
)

/** Top bar (04 §2): ☰, search, ⇅, ☑, ＋ — or the selection bar while multi-select is on. */
@Composable
fun LibraryTopBar(
    state: LibraryUiState,
    onSidebar: () -> Unit,
    onSidebarLongClick: () -> Unit,
    onFilter: (String) -> Unit,
    onSort: (Sort) -> Unit,
    onStartSelecting: () -> Unit,
    onNewNotebook: () -> Unit,
    onNewFolder: () -> Unit,
    onUp: () -> Unit,
    onSelectAll: () -> Unit,
    onDoneSelecting: () -> Unit,
    extraAdd: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val menuOffset = IntOffset(0, with(LocalDensity.current) { 48.dp.roundToPx() })
    var sortOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp).background(Color.White), verticalAlignment = Alignment.CenterVertically) {
        if (state.selecting) {
            IconBtn(Icons.Outlined.Close, stringResource(R.string.cd_done), onClick = onDoneSelecting)
            Text(stringResource(R.string.selected_count, state.selection.size), fontSize = 18.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
            IconBtn(Icons.Outlined.DoneAll, stringResource(R.string.cd_select_all), onClick = onSelectAll)
            return@Row
        }
        IconBtn(Icons.Outlined.Menu, stringResource(R.string.cd_sidebar), onLongClick = onSidebarLongClick, onClick = onSidebar)
        if (state.canGoUp) IconBtn(Icons.Outlined.ArrowUpward, stringResource(R.string.cd_up), onClick = onUp)
        SearchField(state.filter, onFilter, Modifier.weight(1f).padding(horizontal = 4.dp))
        Box {
            IconBtn(Icons.Outlined.SwapVert, stringResource(R.string.cd_sort)) { sortOpen = true }
            if (sortOpen) MenuPopup({ sortOpen = false }, menuOffset) {
                SORTS.forEach { (s, res) ->
                    MenuItem((if (s == state.sort) "✓ " else "    ") + stringResource(res)) { sortOpen = false; onSort(s) }
                }
            }
        }
        IconBtn(Icons.Outlined.CheckBox, stringResource(R.string.cd_select), onClick = onStartSelecting)
        if (state.location != LibraryLocation.Trash) {
            Box {
                IconBtn(Icons.Outlined.Add, stringResource(R.string.cd_add)) { addOpen = true }
                if (addOpen) MenuPopup({ addOpen = false }, menuOffset) {
                    MenuItem(stringResource(R.string.add_new_notebook)) { addOpen = false; onNewNotebook() }
                    MenuItem(stringResource(R.string.add_new_folder)) { addOpen = false; onNewFolder() }
                    extraAdd.forEach { (label, action) -> MenuItem(label) { addOpen = false; action() } }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Row(modifier.height(40.dp).border(1.dp, Color(0xFF9E9E9E)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = Color(0xFF5F5F5F))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(stringResource(R.string.search_hint), color = Color(0xFF9E9E9E), fontSize = 15.sp, maxLines = 1)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true, cursorBrush = SolidColor(Color.Black),
                textStyle = TextStyle(fontSize = 16.sp, color = Color.Black), modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Outlined.Close, stringResource(R.string.cd_clear_search), Modifier.size(20.dp).clickable { onChange("") })
        }
    }
}
