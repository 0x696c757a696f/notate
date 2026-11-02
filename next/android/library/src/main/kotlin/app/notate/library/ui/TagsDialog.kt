package app.notate.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
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
import app.notate.core.store.library.Tag
import app.notate.library.R

/** The 6 grey levels offered for tags (e-ink has no colour). */
val TAG_COLORS: List<Int> = listOf(0xFF000000, 0xFF333333, 0xFF666666, 0xFF888888, 0xFFAAAAAA, 0xFFCCCCCC).map { it.toInt() }

/**
 * Check tags for the documents being edited; [initial] is the set already on a single document (empty for several).
 * [onCreateTag] makes a tag and returns its id through the callback so it can be checked immediately.
 */
@Composable
fun TagsDialog(
    tags: List<Tag>,
    initial: Set<String>,
    onCreateTag: (name: String, color: Int, created: (String) -> Unit) -> Unit,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by remember { mutableStateOf(initial) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(TAG_COLORS[0]) }
    var fieldKey by remember { mutableStateOf(0) }
    NotateDialog(
        title = stringResource(R.string.tags_title), onDismiss = onDismiss,
        buttons = {
            DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss)
            DialogButton(stringResource(R.string.tags_apply), filled = true) { onApply(checked) }
        },
    ) {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            tags.forEach { t ->
                val on = t.id in checked
                Row(
                    Modifier.fillMaxWidth().clickable { checked = if (on) checked - t.id else checked + t.id }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(24.dp))
                    Box(Modifier.size(14.dp).background(Color(t.color)).border(BorderStroke(1.dp, Color.Black)))
                    Text(t.name, fontSize = 16.sp, maxLines = 1)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.runtime.key(fieldKey) { NameField("", stringResource(R.string.tags_new), { newName = it }) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TAG_COLORS.forEach { c ->
                    Box(
                        Modifier.size(32.dp).background(Color(c)).border(BorderStroke(if (c == newColor) 3.dp else 1.dp, Color.Black)).clickable { newColor = c },
                        contentAlignment = Alignment.Center,
                    ) { if (c == newColor && c != TAG_COLORS.last()) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
                Box(Modifier.weight(1f))
                DialogButton(stringResource(R.string.tags_add), enabled = newName.isNotBlank()) {
                    onCreateTag(newName, newColor) { id -> checked = checked + id }
                    newName = ""
                    fieldKey++
                }
            }
        }
    }
}
