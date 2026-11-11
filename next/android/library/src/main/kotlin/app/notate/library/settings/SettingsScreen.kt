package app.notate.library.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import android.text.format.DateUtils
import app.notate.core.doc.settings.BackupFreq
import app.notate.core.doc.settings.FingerMode
import app.notate.core.doc.settings.Handedness
import app.notate.core.doc.settings.PenButton
import app.notate.core.doc.settings.Pref
import app.notate.core.doc.settings.Settings
import app.notate.core.doc.settings.Tap2Action
import app.notate.core.doc.settings.Tap3Action
import app.notate.core.doc.settings.ToolbarPos
import app.notate.core.doc.settings.ToolbarSize
import app.notate.core.doc.settings.ToolbarTool
import app.notate.core.model.PageLayoutMode
import app.notate.library.R
import app.notate.library.ui.DefaultTemplateDialog
import app.notate.library.ui.DialogButton
import app.notate.library.ui.NotateDialog
import app.notate.library.ui.templateSummary

/** What the settings screen needs from the app. */
interface SettingsHost {
    val versionName: String

    /** Debug builds only: shows the Debug group. */
    val isDebugBuild: Boolean
    fun shareLogs()

    /** Opens the flow that copies notes from the old Notate app into this library. */
    fun importFromOldNotate()

    /** Name of the chosen backup folder; null when none is set. */
    val backupFolder: StateFlow<String?>
    val backupStatus: StateFlow<BackupStatus>

    /** Opens the system folder picker for the backup folder. */
    fun pickBackupFolder()

    /** Starts a backup now (keeps running when the screen is left). */
    fun backUpNow()

    /** Opens the restore flow (pick a backup folder, choose notebooks). */
    fun restoreFromBackup()

    /** Lines for the full-screen ink diagnostics page (device facts and recent log lines). */
    fun diagnosticLines(): List<String>
}

private enum class Page { MAIN, LICENSES, DIAGNOSTICS }

/** Open-source components shown on the licenses page: name to license. */
internal val LICENSES = listOf(
    "Onyx SDK (onyxsdk-pen, onyxsdk-base)" to "Onyx proprietary SDK license",
    "pdfbox-android" to "Apache License 2.0",
    "Paddle Lite" to "Apache License 2.0",
    "PaddleOCR" to "Apache License 2.0",
    "OpenCV" to "Apache License 2.0",
    "AndroidX (Compose, Lifecycle, Activity, WorkManager)" to "Apache License 2.0",
    "Kotlin and kotlinx (coroutines, serialization)" to "Apache License 2.0",
)

/** 04 §9 settings, e-ink styled (black on white, no animation). Writes straight to [settings]. */
@Composable
fun SettingsScreen(settings: Settings, host: SettingsHost, onBack: () -> Unit) {
    var page by remember { mutableStateOf(Page.MAIN) }
    BackHandler { if (page == Page.MAIN) onBack() else page = Page.MAIN }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        TopBar(
            when (page) {
                Page.MAIN -> stringResource(R.string.settings_title)
                Page.LICENSES -> stringResource(R.string.settings_licenses)
                Page.DIAGNOSTICS -> stringResource(R.string.settings_ink_diagnostics)
            },
        ) { if (page == Page.MAIN) onBack() else page = Page.MAIN }
        when (page) {
            Page.MAIN -> Main(settings, host, { page = Page.LICENSES }, { page = Page.DIAGNOSTICS })
            Page.LICENSES -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
                LICENSES.forEach { (name, license) -> Row2(name, license) }
            }
            Page.DIAGNOSTICS -> {
                val lines = remember { host.diagnosticLines() }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (lines.isEmpty()) Text(stringResource(R.string.settings_diag_empty), fontSize = 14.sp)
                    lines.forEach { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    val back = stringResource(R.string.settings_back)
    Row(
        Modifier.fillMaxWidth().border(width = 1.dp, color = Color(0xFF9E9E9E)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack, null,
            Modifier.size(48.dp).clickable(onClick = onBack).padding(12.dp).semantics { contentDescription = back },
        )
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Main(settings: Settings, host: SettingsHost, onLicenses: () -> Unit, onDiagnostics: () -> Unit) {
    var templateDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Group(R.string.settings_group_writing)
        Choice(R.string.settings_finger, settings.fingerMode, listOf(
            FingerMode.SCROLL to stringResource(R.string.settings_finger_scroll), FingerMode.OFF to stringResource(R.string.settings_finger_off),
        ))
        Choice(R.string.settings_handedness, settings.handedness, listOf(
            Handedness.RIGHT to stringResource(R.string.settings_right), Handedness.LEFT to stringResource(R.string.settings_left),
        ))
        Choice(R.string.settings_tap2, settings.tap2, listOf(
            Tap2Action.UNDO to stringResource(R.string.settings_undo), Tap2Action.REDO to stringResource(R.string.settings_redo),
            Tap2Action.OFF to stringResource(R.string.settings_off),
        ))
        Choice(R.string.settings_tap3, settings.tap3, listOf(
            Tap3Action.REDO to stringResource(R.string.settings_redo), Tap3Action.FOCUS_MODE to stringResource(R.string.settings_focus_mode),
            Tap3Action.REFRESH to stringResource(R.string.settings_refresh_screen), Tap3Action.OFF to stringResource(R.string.settings_off),
        ))
        Choice(R.string.settings_pen_button, settings.penButton, listOf(
            PenButton.ERASER to stringResource(R.string.settings_eraser), PenButton.LASSO to stringResource(R.string.settings_lasso),
            PenButton.NONE to stringResource(R.string.settings_none),
        ))
        Choice(R.string.settings_hold_shape, settings.holdShapeMs, listOf(
            0 to stringResource(R.string.settings_off), 400 to stringResource(R.string.settings_ms, 400),
            600 to stringResource(R.string.settings_ms, 600), 900 to stringResource(R.string.settings_ms, 900),
        ))
        Toggle(R.string.settings_scribble_erase, settings.scribbleErase, R.string.settings_scribble_erase_hint)
        Toggle(R.string.settings_driver_ink, settings.driverInk, R.string.settings_driver_ink_hint)
        Toggle(R.string.settings_web_pen_surface, settings.webPenSurface, R.string.settings_web_pen_surface_hint)

        Group(R.string.settings_group_display)
        Choice(R.string.settings_page_layout, settings.pageLayout, listOf(
            PageLayoutMode.CONTINUOUS to stringResource(R.string.settings_continuous), PageLayoutMode.SINGLE to stringResource(R.string.settings_single_page),
        ))
        Choice(R.string.settings_gc_every, settings.gcEvery, listOf(
            0 to stringResource(R.string.settings_off), 5 to stringResource(R.string.settings_page_turns, 5),
            10 to stringResource(R.string.settings_page_turns, 10), 20 to stringResource(R.string.settings_page_turns, 20),
        ))
        Choice(R.string.settings_toolbar_pos, settings.toolbarPos, listOf(
            ToolbarPos.TOP to stringResource(R.string.settings_top), ToolbarPos.LEFT to stringResource(R.string.settings_left),
        ), hint = R.string.settings_toolbar_hint)
        Choice(R.string.settings_toolbar_size, settings.toolbarSize, listOf(
            ToolbarSize.REGULAR to stringResource(R.string.settings_size_regular), ToolbarSize.LARGE to stringResource(R.string.settings_size_large),
        ), hint = R.string.settings_toolbar_size_hint)
        for ((tool, pref) in settings.toolbarShow) {
            Toggle(when (tool) {
                ToolbarTool.LASSO -> R.string.settings_show_lasso
                ToolbarTool.HIGHLIGHTER -> R.string.settings_show_highlighter
                ToolbarTool.SHAPE -> R.string.settings_show_shape
                ToolbarTool.TEXT -> R.string.settings_show_text
                ToolbarTool.IMAGE -> R.string.settings_show_image
                ToolbarTool.RULER -> R.string.settings_show_ruler
                ToolbarTool.ZOOM -> R.string.settings_show_zoom
                ToolbarTool.HAND -> R.string.settings_show_hand
            }, pref)
        }

        Group(R.string.settings_group_notebooks)
        val template by settings.defaultTemplate.collectAsStateWithLifecycle()
        Row2(stringResource(R.string.settings_default_template), templateSummary(template)) { templateDialog = true }

        Group(R.string.settings_group_books)
        Toggle(R.string.settings_web_network, settings.webNetworkDefault)
        Toggle(R.string.settings_web_js, settings.webJsDefault)

        Group(R.string.settings_group_import)
        Row2(stringResource(R.string.settings_import_old), null) { host.importFromOldNotate() }

        Group(R.string.settings_group_backup)
        BackupRows(settings, host)

        Group(R.string.settings_group_search)
        Toggle(R.string.settings_ocr, settings.ocrEnabled, R.string.settings_ocr_hint)

        Group(R.string.settings_group_about)
        Row2(stringResource(R.string.settings_version), host.versionName)
        Row2(stringResource(R.string.settings_share_logs), null) { host.shareLogs() }
        Row2(stringResource(R.string.settings_licenses), null, onClick = onLicenses)

        if (host.isDebugBuild) {
            Group(R.string.settings_group_debug)
            Toggle(R.string.settings_debug_hud, settings.debugHud)
            Row2(stringResource(R.string.settings_ink_diagnostics), null, onClick = onDiagnostics)
        }
    }
    if (templateDialog) {
        DefaultTemplateDialog(
            initial = settings.defaultTemplate.value,
            onPick = { settings.defaultTemplate.set(it); templateDialog = false },
            onDismiss = { templateDialog = false },
        )
    }
}

@Composable
private fun Group(title: Int) {
    Text(
        stringResource(title), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF5F5F5F),
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** A row with a [title], an optional gray [value] line and an optional click. Tall enough for a finger (≥ 56 dp). */
@Composable
private fun Row2(title: String, value: String?, hint: String? = null, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().heightIn(min = 56.dp).border(width = 0.5.dp, color = Color(0xFFE0E0E0))
    Column(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 16.sp)
        if (value != null) Text(value, fontSize = 14.sp, color = Color(0xFF5F5F5F))
        if (hint != null) Text(hint, fontSize = 13.sp, color = Color(0xFF5F5F5F))
    }
}

/** Single choice: shows the current option as the gray line and opens a radio dialog. */
@Composable
private fun <T> Choice(title: Int, pref: Pref<T>, options: List<Pair<T, String>>, hint: Int? = null) {
    val value by pref.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val name = stringResource(title)
    Row2(name, options.firstOrNull { it.first == value }?.second ?: "", hint?.let { stringResource(it) }) { open = true }
    if (open) {
        NotateDialog(title = name, onDismiss = { open = false }, buttons = { DialogButton(stringResource(R.string.dialog_cancel)) { open = false } }) {
            Column(Modifier.widthIn(min = 240.dp)) {
                options.forEach { (v, label) ->
                    val selected = v == value
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { pref.set(v); open = false }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(if (selected) "◉" else "○", fontSize = 20.sp)
                        Text(label, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: Int, pref: Pref<Boolean>, hint: Int? = null) {
    val on by pref.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).border(width = 0.5.dp, color = Color(0xFFE0E0E0)).clickable { pref.set(!on) }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), fontSize = 16.sp)
            if (hint != null) Text(stringResource(hint), fontSize = 13.sp, color = Color(0xFF5F5F5F))
        }
        Switch(
            checked = on, onCheckedChange = { pref.set(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Color.Black, checkedBorderColor = Color.Black,
                uncheckedThumbColor = Color.Black, uncheckedTrackColor = Color.White, uncheckedBorderColor = Color.Black,
            ),
        )
    }
}

/** Backup folder, schedule, "Back up now" with its status line, and Restore (04 §9). */
@Composable
private fun BackupRows(settings: Settings, host: SettingsHost) {
    val folder by host.backupFolder.collectAsStateWithLifecycle()
    val status by host.backupStatus.collectAsStateWithLifecycle()
    Row2(stringResource(R.string.settings_backup_folder), folder ?: stringResource(R.string.settings_backup_not_set)) { host.pickBackupFolder() }
    Choice(R.string.settings_backup_auto, settings.backupFreq, listOf(
        BackupFreq.OFF to stringResource(R.string.settings_off), BackupFreq.DAILY to stringResource(R.string.settings_backup_daily),
        BackupFreq.WEEKLY to stringResource(R.string.settings_backup_weekly),
    ))
    val line = when {
        status.running -> stringResource(R.string.settings_backup_running, status.done, status.total)
        status.lastError != null -> stringResource(R.string.settings_backup_failed, status.lastError!!)
        status.lastAt != null -> stringResource(
            R.string.settings_backup_last, DateUtils.getRelativeTimeSpanString(status.lastAt!!, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
        )
        else -> stringResource(R.string.settings_backup_never)
    }
    Row2(stringResource(R.string.settings_backup_now), line, onClick = if (folder != null && !status.running) ({ host.backUpNow() }) else null)
    Row2(stringResource(R.string.settings_backup_restore), null) { host.restoreFromBackup() }
}
