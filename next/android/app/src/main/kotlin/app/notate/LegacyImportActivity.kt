package app.notate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.notate.export.legacy.ImportReport
import app.notate.export.legacy.LegacyEntry
import app.notate.export.legacy.LegacyImportSession
import app.notate.export.legacy.LegacyImportState
import app.notate.export.legacy.LegacyScanner
import app.notate.export.legacy.ScanResult
import app.notate.library.NotateTheme
import app.notate.library.ui.DialogButton
import app.notate.library.ui.NotateDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Settings → "Import from old Notate…" (R6-2): explains, lets the user pick the folder of old notes, previews what was found
 * and runs the import in [LegacyImportService]. Originals are never changed.
 */
class LegacyImportActivity : ComponentActivity() {
    private sealed interface Stage {
        data object Intro : Stage
        data object Scanning : Stage
        class Found(val scan: ScanResult, val imported: Set<LegacyEntry>) : Stage
        class Nothing(val scan: ScanResult) : Stage
        data object Failed : Stage
    }

    private var stage by mutableStateOf<Stage>(Stage.Intro)

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) finish() else scan(uri)
    }
    private var startAfterPermission: List<LegacyEntry>? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notifications are optional: the import runs either way.
        startAfterPermission?.let(::startImport)
        startAfterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NotateTheme { Content() } }
    }

    private fun scan(uri: Uri) {
        stage = Stage.Scanning
        val graph = NotateApp.graph
        lifecycleScope.launch {
            try {
                val (result, done) = withContext(graph.dispatchers.io) {
                    val r = LegacyScanner.scanTree(this@LegacyImportActivity, uri)
                    r to r.notes.filter { graph.legacyImporter.existing(it.uuid) != null }.toSet()
                }
                stage = if (result.notes.isEmpty()) Stage.Nothing(result) else Stage.Found(result, done)
            } catch (e: Exception) {
                graph.logger.w("LegacyImport", "Scan failed", e)
                stage = Stage.Failed
            }
        }
    }

    private fun askThenStart(chosen: List<LegacyEntry>) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = chosen
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startImport(chosen)
        }
    }

    private fun startImport(chosen: List<LegacyEntry>) {
        LegacyImportSession.pending = chosen
        LegacyImportSession.running(0, chosen.size, "")
        startForegroundService(LegacyImportService.startIntent(this))
    }

    @Composable
    private fun Content() {
        val state by LegacyImportSession.state.collectAsStateWithLifecycle()
        when (val s = state) {
            is LegacyImportState.Running -> Running(s)
            is LegacyImportState.Finished -> Result(s.report)
            LegacyImportState.Idle -> when (val st = stage) {
                Stage.Intro -> IntroDialog()
                Stage.Scanning -> Centered(stringResource(R.string.legacy_scanning))
                is Stage.Found -> Preview(st)
                is Stage.Nothing -> NothingDialog(st.scan)
                Stage.Failed -> MessageDialog(stringResource(R.string.legacy_scan_failed))
            }
        }
    }

    @Composable
    private fun IntroDialog() {
        NotateDialog(
            title = stringResource(R.string.legacy_title), onDismiss = { finish() },
            buttons = {
                DialogButton(stringResource(R.string.legacy_cancel)) { finish() }
                DialogButton(stringResource(R.string.legacy_choose_folder), filled = true) { pickFolder.launch(null) }
            },
        ) {
            Text(stringResource(R.string.legacy_intro), fontSize = 16.sp)
            Text(stringResource(R.string.legacy_intro_books), fontSize = 14.sp, color = Color(0xFF5F5F5F))
        }
    }

    @Composable
    private fun MessageDialog(message: String) {
        NotateDialog(
            title = stringResource(R.string.legacy_title), onDismiss = { finish() },
            buttons = { DialogButton(stringResource(R.string.legacy_ok), filled = true) { finish() } },
        ) { Text(message, fontSize = 16.sp) }
    }

    @Composable
    private fun NothingDialog(scan: ScanResult) {
        val extra = if (scan.packages > 0) "\n" + stringResource(R.string.legacy_packages_hint, scan.packages) else ""
        MessageDialog(stringResource(R.string.legacy_nothing_found) + extra)
    }

    @Composable
    private fun Centered(text: String) {
        Column(Modifier.fillMaxSize().background(Color.White), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, fontSize = 18.sp)
        }
    }

    @Composable
    private fun Preview(found: Stage.Found) {
        val entries = found.scan.notes
        val selectable = remember(found) { entries.filter { it !in found.imported } }
        val selected: SnapshotStateMap<LegacyEntry, Boolean> = remember(found) { mutableStateMapOf<LegacyEntry, Boolean>().also { m -> selectable.forEach { m[it] = true } } }
        val count = selectable.count { selected[it] == true }
        val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
        Column(Modifier.fillMaxSize().background(Color.White)) {
            Text(stringResource(R.string.legacy_found_title, entries.size), fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(24.dp, 20.dp, 24.dp, 8.dp))
            Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.legacy_select_all), enabled = selectable.isNotEmpty()) { selectable.forEach { selected[it] = true } }
                DialogButton(stringResource(R.string.legacy_select_none), enabled = selectable.isNotEmpty()) { selectable.forEach { selected[it] = false } }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(entries) { _, e ->
                    val done = e in found.imported
                    val on = selected[e] == true
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !done) { selected[e] = !on }.padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CheckSquare(on, done)
                        Column(Modifier.weight(1f)) {
                            Text(e.title, fontSize = 17.sp, color = if (done) Color(0xFF8A8A8A) else Color.Black)
                            val where = if (e.folders.isEmpty()) "" else e.folders.joinToString(" / ") + "  ·  "
                            val status = if (done) stringResource(R.string.legacy_already_imported) else dateFormat.format(Date(e.modified))
                            Text(where + status, fontSize = 13.sp, color = Color(0xFF5F5F5F))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DialogButton(stringResource(R.string.legacy_cancel)) { finish() }
                DialogButton(stringResource(R.string.legacy_import_n, count), enabled = count > 0, filled = true) {
                    askThenStart(selectable.filter { selected[it] == true })
                }
            }
        }
    }

    @Composable
    private fun CheckSquare(checked: Boolean, disabled: Boolean) {
        val color = if (disabled) Color(0xFF9E9E9E) else Color.Black
        Column(
            Modifier.size(24.dp).border(2.dp, color).background(if (checked) Color.Black else Color.White),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (checked) Text("✓", color = Color.White, fontSize = 16.sp)
        }
    }

    @Composable
    private fun Running(s: LegacyImportState.Running) {
        NotateDialog(
            title = stringResource(R.string.legacy_title), onDismiss = {},
            buttons = {
                DialogButton(stringResource(R.string.legacy_run_background)) { finish() }
                DialogButton(stringResource(R.string.legacy_cancel_import)) { LegacyImportSession.cancel() }
            },
        ) {
            Text(stringResource(R.string.legacy_progress, s.done, s.total), fontSize = 18.sp)
            if (s.current.isNotEmpty()) Text(s.current, fontSize = 14.sp, color = Color(0xFF5F5F5F), maxLines = 1)
        }
    }

    @Composable
    private fun Result(report: ImportReport) {
        var details by remember { mutableStateOf(false) }
        val skipped = report.alreadyImported
        val title = stringResource(R.string.legacy_result, report.imported, report.total - skipped, report.failures.size)
        NotateDialog(
            title = stringResource(R.string.legacy_title), onDismiss = { LegacyImportSession.reset(); finish() },
            buttons = {
                if (report.failures.isNotEmpty() && !details) DialogButton(stringResource(R.string.legacy_show_details)) { details = true }
                DialogButton(stringResource(R.string.legacy_ok), filled = true) { LegacyImportSession.reset(); finish() }
            },
        ) {
            Text(if (report.cancelled) stringResource(R.string.legacy_cancelled) + " " + title else title, fontSize = 16.sp)
            if (skipped > 0) Text(stringResource(R.string.legacy_skipped_n, skipped), fontSize = 14.sp, color = Color(0xFF5F5F5F))
            if (report.warnings > 0) Text(stringResource(R.string.legacy_warnings_n, report.warnings), fontSize = 14.sp, color = Color(0xFF5F5F5F))
            if (details) {
                Column(Modifier.fillMaxWidth()) {
                    report.failures.forEach { Text("${it.name}: ${it.reason}", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, LegacyImportActivity::class.java)
    }
}
