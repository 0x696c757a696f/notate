package app.notate.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.notate.library.R

/** Black-bordered dialog card without animation: [title], scrolling-free [content] and a right-aligned button row. */
@Composable
fun NotateDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    buttons: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier.widthIn(max = 560.dp).fillMaxWidth(0.9f).background(Color.White).border(BorderStroke(2.dp, Color.Black)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { buttons() }
        }
    }
}

/** Bordered text button; [filled] draws it inverted (primary action). */
@Composable
fun DialogButton(text: String, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    val fg = if (!enabled) Color(0xFF9E9E9E) else if (filled) Color.White else Color.Black
    Text(
        text, color = fg, fontSize = 16.sp,
        modifier = Modifier.background(if (filled && enabled) Color.Black else Color.White)
            .border(BorderStroke(1.dp, if (enabled) Color.Black else Color(0xFF9E9E9E)))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** Single-line text input with a black outline; selects all text on first composition and focuses itself. */
@Composable
fun NameField(initial: String, label: String, onValue: (String) -> Unit, onDone: () -> Unit = {}, modifier: Modifier = Modifier) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    Column(modifier) {
        Text(label, fontSize = 13.sp, color = Color(0xFF5F5F5F))
        BasicTextField(
            value = value,
            onValueChange = { value = it; onValue(it.text) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 17.sp, color = Color.Black),
            cursorBrush = SolidColor(Color.Black),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).border(BorderStroke(1.dp, Color.Black)).padding(10.dp).focusRequester(focus),
        )
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** One-field dialog used for "New folder", "Rename" and "Rename folder". */
@Composable
fun RenameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val ok = text.isNotBlank()
    NotateDialog(
        title = title, onDismiss = onDismiss,
        buttons = {
            DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss)
            DialogButton(confirmLabel, enabled = ok, filled = true) { onConfirm(text) }
        },
    ) {
        NameField(initial, stringResource(R.string.dialog_name), { text = it }, onDone = { if (ok) onConfirm(text) })
    }
}

/** Yes/no question; [confirmLabel] names the destructive action. */
@Composable
fun ConfirmDialog(message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    NotateDialog(
        title = message, onDismiss = onDismiss,
        buttons = {
            DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss)
            DialogButton(confirmLabel, filled = true, onClick = onConfirm)
        },
        content = {},
    )
}

/** Selectable chip: black when [selected]. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.White else Color.Black, fontSize = 15.sp,
        modifier = Modifier.background(if (selected) Color.Black else Color.White).border(BorderStroke(1.dp, Color.Black))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
