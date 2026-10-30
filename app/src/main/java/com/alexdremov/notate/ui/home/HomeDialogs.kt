package com.alexdremov.notate.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.ui.theme.InkMuted
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.config.Orientation
import com.alexdremov.notate.config.PaperSize
import com.alexdremov.notate.data.CanvasType

enum class DialogType {
    ADD_PROJECT,
    CREATE_FOLDER,
    CREATE_CANVAS,
    MANAGE_TAGS,
}

@Composable
fun TextInputDialog(
    title: String,
    initialValue: String = "",
    confirmText: String = "Create",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            OutlinedButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/** Where a new project keeps its files. */
enum class ProjectLocation {
    /** Inside the app: no system file picker, works out of the box. */
    ON_DEVICE,

    /** A folder the user picks with the system file picker (shared with other apps). */
    CUSTOM_FOLDER,
}

/**
 * New project: a name and, optionally, a storage location. Storing on the device is the
 * default so creating a project never drops the user into the system file manager unless
 * they explicitly ask for a custom folder.
 */
@Composable
fun NewProjectDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, ProjectLocation) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf(ProjectLocation.ON_DEVICE) }

    AlertDialog(
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text(text = "New project") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("Save to", style = MaterialTheme.typography.titleSmall)

                ProjectLocationOption(
                    title = "This device",
                    description = "Recommended. Kept inside Notate; back it up with Sync.",
                    selected = location == ProjectLocation.ON_DEVICE,
                    onSelect = { location = ProjectLocation.ON_DEVICE },
                )
                ProjectLocationOption(
                    title = "A folder I choose",
                    description = "Opens the system file picker next, to use a folder shared with other apps.",
                    selected = location == ProjectLocation.CUSTOM_FOLDER,
                    onSelect = { location = ProjectLocation.CUSTOM_FOLDER },
                )
            }
        },
        confirmButton = {
            OutlinedButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), location) },
            ) {
                Text(if (location == ProjectLocation.CUSTOM_FOLDER) "Choose folder" else "Create")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ProjectLocationOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = InkMuted,
            )
        }
    }
}

@Composable
fun CreateCanvasDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, CanvasType, Float, Float) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(CanvasType.INFINITE) }

    // Fixed Page Options
    var selectedSize by remember { mutableStateOf(PaperSize.A4) }
    var selectedOrientation by remember { mutableStateOf(Orientation.PORTRAIT) }

    val context = LocalContext.current
    val displayMetrics = context.resources.displayMetrics

    AlertDialog(
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text(text = "New canvas") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("Canvas type", style = MaterialTheme.typography.titleSmall)

                // Canvas Type Selection
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .selectable(
                            selected = (selectedType == CanvasType.INFINITE),
                            onClick = { selectedType = CanvasType.INFINITE },
                            role = Role.RadioButton,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = (selectedType == CanvasType.INFINITE),
                        onClick = null,
                    )
                    Text(
                        text = "Infinite canvas",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .selectable(
                            selected = (selectedType == CanvasType.FIXED_PAGES),
                            onClick = { selectedType = CanvasType.FIXED_PAGES },
                            role = Role.RadioButton,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = (selectedType == CanvasType.FIXED_PAGES),
                        onClick = null,
                    )
                    Text(
                        text = "Fixed pages",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                if (selectedType == CanvasType.FIXED_PAGES) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Paper Size Selection
                    Text("Paper size", style = MaterialTheme.typography.titleSmall)
                    PaperSize.values().forEach { size ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .selectable(
                                    selected = (selectedSize == size),
                                    onClick = { selectedSize = size },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (selectedSize == size),
                                onClick = null,
                            )
                            Text(
                                text = size.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Orientation Selection
                    Text("Orientation", style = MaterialTheme.typography.titleSmall)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .selectable(
                                    selected = (selectedOrientation == Orientation.PORTRAIT),
                                    onClick = { selectedOrientation = Orientation.PORTRAIT },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (selectedOrientation == Orientation.PORTRAIT),
                                onClick = null,
                            )
                            Text(
                                text = "Portrait",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }

                        Row(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .selectable(
                                    selected = (selectedOrientation == Orientation.LANDSCAPE),
                                    onClick = { selectedOrientation = Orientation.LANDSCAPE },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (selectedOrientation == Orientation.LANDSCAPE),
                                onClick = null,
                            )
                            Text(
                                text = "Landscape",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = {
                    if (name.isNotBlank()) {
                        var width = 0f
                        var height = 0f

                        if (selectedType == CanvasType.FIXED_PAGES) {
                            val dim =
                                selectedSize.getDimensions(
                                    selectedOrientation,
                                    displayMetrics.widthPixels.toFloat(),
                                    displayMetrics.heightPixels.toFloat(),
                                )
                            width = dim.first
                            height = dim.second
                        }

                        onConfirm(name, selectedType, width, height)
                    }
                },
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
