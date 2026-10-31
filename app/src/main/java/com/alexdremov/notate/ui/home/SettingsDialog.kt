package com.alexdremov.notate.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexdremov.notate.ui.theme.InkMuted
import com.alexdremov.notate.BuildConfig
import com.alexdremov.notate.R
import com.alexdremov.notate.data.FingerMode
import com.alexdremov.notate.data.ScreenOrientationMode
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.ui.input.Handedness
import com.alexdremov.notate.ui.settings.InputSettingsPanel
import com.alexdremov.notate.ui.settings.InputSettingsState
import com.alexdremov.notate.ui.settings.InterfaceSettingsPanel
import com.alexdremov.notate.ui.settings.InterfaceSettingsState
import com.alexdremov.notate.ui.settings.PdfSettingsPanel
import com.alexdremov.notate.ui.settings.PdfSettingsState
import com.alexdremov.notate.ui.settings.SettingsToggle
import kotlinx.coroutines.launch

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onOpenSync: () -> Unit,
) {
    var currentScreen by remember { mutableStateOf(SettingsScreen.MAIN) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)), // Keep border for E-Ink visibility
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentScreen != SettingsScreen.MAIN) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        modifier =
                            Modifier
                                .clickable { currentScreen = SettingsScreen.MAIN }
                                .padding(end = 8.dp),
                    )
                }
                Text(
                    text =
                        when (currentScreen) {
                            SettingsScreen.MAIN -> "Settings"
                            SettingsScreen.INPUT -> "Writing & gestures"
                            SettingsScreen.INTERFACE -> "Toolbar"
                            SettingsScreen.ORIENTATION -> "Screen orientation"
                            SettingsScreen.STARTUP -> "Startup"
                            SettingsScreen.PDF -> "Export"
                            SettingsScreen.OCR -> "Text recognition & search"
                            SettingsScreen.ABOUT -> "About"
                        },
                )
            }
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                when (currentScreen) {
                    // Ordered by how often people change them: writing first, accounts and info last.
                    SettingsScreen.MAIN -> {
                        SettingsMenuItem(
                            title = "Writing & gestures",
                            subtitle = "Palm rejection, eraser, shapes, snapping, finger and pen buttons",
                            icon = Icons.Default.Brush,
                            onClick = { currentScreen = SettingsScreen.INPUT },
                        )
                        SettingsMenuItem(
                            title = "Toolbar",
                            subtitle = "Auto collapse",
                            icon = Icons.Default.ViewQuilt,
                            onClick = { currentScreen = SettingsScreen.INTERFACE },
                        )
                        SettingsMenuItem(
                            title = "Screen orientation",
                            subtitle = "Auto-rotate, portrait or landscape",
                            icon = Icons.Default.ScreenRotation,
                            onClick = { currentScreen = SettingsScreen.ORIENTATION },
                        )
                        SettingsMenuItem(
                            title = "Startup",
                            subtitle = "What opens when the app starts",
                            icon = Icons.Default.Today,
                            onClick = { currentScreen = SettingsScreen.STARTUP },
                        )
                        SettingsMenuItem(
                            title = "Text recognition & search",
                            subtitle = "Offline handwriting model and search index",
                            icon = Icons.Default.Search,
                            onClick = { currentScreen = SettingsScreen.OCR },
                        )
                        SettingsMenuItem(
                            title = "Export",
                            subtitle = "PDF quality, scale and the format used for sync",
                            icon = Icons.Default.PictureAsPdf,
                            onClick = { currentScreen = SettingsScreen.PDF },
                        )
                        SettingsMenuItem(
                            title = "Synchronization",
                            subtitle = "Cloud storage and sync options",
                            icon = Icons.Default.CloudSync,
                            onClick = onOpenSync,
                        )
                        SettingsMenuItem(
                            title = "About",
                            subtitle = "Version and app info",
                            icon = Icons.Default.Info,
                            onClick = { currentScreen = SettingsScreen.ABOUT },
                        )
                    }

                    SettingsScreen.INPUT -> {
                        var scribbleEnabled by remember { mutableStateOf(PreferencesManager.isScribbleToEraseEnabled(context)) }
                        var shapeEnabled by remember { mutableStateOf(PreferencesManager.isShapePerfectionEnabled(context)) }
                        var angleSnapping by remember { mutableStateOf(PreferencesManager.isAngleSnappingEnabled(context)) }
                        var axisLocking by remember { mutableStateOf(PreferencesManager.isAxisLockingEnabled(context)) }
                        var shapeDelay by remember { mutableFloatStateOf(PreferencesManager.getShapePerfectionDelay(context).toFloat()) }
                        var fingerOff by remember { mutableStateOf(PreferencesManager.isFingerOff(context)) }
                        var handedness by remember { mutableStateOf(PreferencesManager.getHandedness(context)) }
                        var twoFingerTapAction by remember { mutableStateOf(PreferencesManager.getTwoFingerTapAction(context)) }
                        var stylusButtonAction by remember { mutableStateOf(PreferencesManager.getStylusButtonAction(context)) }

                        InputSettingsPanel(
                            state =
                                InputSettingsState(
                                    scribbleEnabled,
                                    shapeEnabled,
                                    angleSnapping,
                                    axisLocking,
                                    shapeDelay,
                                    fingerOff,
                                    handedness,
                                    twoFingerTapAction,
                                    stylusButtonAction,
                                ),
                            onScribbleChange = {
                                scribbleEnabled = it
                                PreferencesManager.setScribbleToEraseEnabled(context, it)
                            },
                            onFingerOffChange = {
                                fingerOff = it
                                PreferencesManager.setFingerMode(context, if (it) FingerMode.OFF else FingerMode.SCROLL)
                            },
                            onHandednessChange = {
                                handedness = it
                                PreferencesManager.setHandedness(context, it)
                            },
                            onShapeChange = {
                                shapeEnabled = it
                                PreferencesManager.setShapePerfectionEnabled(context, it)
                            },
                            onAngleChange = {
                                angleSnapping = it
                                PreferencesManager.setAngleSnappingEnabled(context, it)
                            },
                            onAxisChange = {
                                axisLocking = it
                                PreferencesManager.setAxisLockingEnabled(context, it)
                            },
                            onShapeDelayChange = { shapeDelay = it },
                            onShapeDelayFinished = {
                                PreferencesManager.setShapePerfectionDelay(context, shapeDelay.toLong())
                            },
                            onTwoFingerTapChange = {
                                twoFingerTapAction = it
                                PreferencesManager.setTwoFingerTapAction(context, it)
                            },
                            onStylusButtonChange = {
                                stylusButtonAction = it
                                PreferencesManager.setStylusButtonAction(context, it)
                            },
                        )
                    }

                    SettingsScreen.ORIENTATION -> {
                        var mode by remember { mutableStateOf(PreferencesManager.getScreenOrientation(context)) }
                        listOf(
                            ScreenOrientationMode.AUTO to "Auto (follow sensor)",
                            ScreenOrientationMode.PORTRAIT to "Portrait",
                            ScreenOrientationMode.LANDSCAPE to "Landscape",
                        ).forEach { (m, label) ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            mode = m
                                            PreferencesManager.setScreenOrientation(context, m)
                                            (context as? android.app.Activity)?.requestedOrientation =
                                                when (m) {
                                                    ScreenOrientationMode.AUTO -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                                                    ScreenOrientationMode.PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                                    ScreenOrientationMode.LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                                }
                                        }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = mode == m, onClick = null)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(text = label, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }

                    SettingsScreen.INTERFACE -> {
                        var collapsibleToolbar by remember { mutableStateOf(PreferencesManager.isCollapsibleToolbarEnabled(context)) }
                        var collapseTimeout by remember {
                            mutableFloatStateOf(
                                PreferencesManager.getToolbarCollapseTimeout(context).toFloat(),
                            )
                        }

                        InterfaceSettingsPanel(
                            state = InterfaceSettingsState(collapsibleToolbar, collapseTimeout),
                            onCollapsibleChange = {
                                collapsibleToolbar = it
                                PreferencesManager.setCollapsibleToolbarEnabled(context, it)
                            },
                            onTimeoutChange = { collapseTimeout = it },
                            onTimeoutFinished = {
                                PreferencesManager.setToolbarCollapseTimeout(context, collapseTimeout.toLong())
                            },
                        )
                    }

                    SettingsScreen.STARTUP -> {
                        var openDailyOnStart by remember { mutableStateOf(PreferencesManager.isOpenDailyOnStartEnabled(context)) }
                        SettingsToggle(
                            title = "Open today's note on startup",
                            checked = openDailyOnStart,
                            onCheckedChange = {
                                openDailyOnStart = it
                                PreferencesManager.setOpenDailyOnStartEnabled(context, it)
                            },
                        )
                    }

                    SettingsScreen.PDF -> {
                        var exportScale by remember { mutableFloatStateOf(PreferencesManager.getPdfExportScale(context)) }
                        var syncPdfType by remember { mutableStateOf(PreferencesManager.getSyncPdfType(context)) }

                        PdfSettingsPanel(
                            state = PdfSettingsState(exportScale, syncPdfType),
                            onScaleChange = { exportScale = it },
                            onScaleFinished = {
                                PreferencesManager.setPdfExportScale(context, exportScale)
                            },
                            onSyncTypeChange = {
                                syncPdfType = it
                                PreferencesManager.setSyncPdfType(context, it)
                            },
                            showSyncSettings = true,
                        )
                    }

                    SettingsScreen.OCR -> {
                        var enabled by remember { mutableStateOf(PreferencesManager.isBackgroundOcrIndexingEnabled(context)) }
                        val repository = remember { com.alexdremov.notate.ocr.index.OcrSearchRepository.get(context) }
                        val indexedCount by repository.indexedDocumentCount.collectAsState(initial = 0)
                        val indexingCount by repository.indexingDocumentCount.collectAsState(initial = 0)
                        val staleCount by repository.staleDocumentCount.collectAsState(initial = 0)
                        val model = remember { com.alexdremov.notate.ocr.OcrModelInfo() }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Background text indexing", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Runs after save; older notes run only while charging.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkMuted,
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = {
                                    enabled = it
                                    PreferencesManager.setBackgroundOcrIndexingEnabled(context, it)
                                    if (it) com.alexdremov.notate.data.worker.OcrBackfillScheduler.schedule(context)
                                },
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        Text("Model: ${model.id}", fontWeight = FontWeight.SemiBold)
                        Text("Offline ARM64 · Chinese + English · four CPU threads", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Index: $indexedCount notes" +
                                (if (indexingCount > 0) " · $indexingCount active" else "") +
                                (if (staleCount > 0) " · $staleCount stale" else ""),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    repository.clear()
                                    com.alexdremov.notate.data.worker.OcrBackfillScheduler.schedule(context, replace = true)
                                }
                            },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Rebuild text index")
                        }
                        Text(
                            "The index stays on this device. Converted text items remain normal synced note content.",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkMuted,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }

                    SettingsScreen.ABOUT -> {
                        AboutSettings()
                    }
                }
            }
        },
    )
}

enum class SettingsScreen {
    MAIN,
    INPUT,
    INTERFACE,
    ORIENTATION,
    STARTUP,
    PDF,
    OCR,
    ABOUT,
}

@Composable
private fun SettingsMenuItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = InkMuted,
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = InkMuted,
        )
    }
}

@Composable
private fun AboutSettings() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            color = InkMuted,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Designed for Onyx Boox devices.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
