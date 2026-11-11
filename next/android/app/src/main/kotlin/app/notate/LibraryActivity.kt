package app.notate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.notate.core.model.DocId
import app.notate.editor.EditorActivity
import app.notate.library.LibraryViewModel
import app.notate.library.NotateTheme
import app.notate.library.ui.CoverLoader
import app.notate.library.ui.LibraryScreen
import app.notate.library.ui.DialogButton
import app.notate.library.ui.NotateDialog
import app.notate.platform.log.CrashHandler
import app.notate.platform.log.ShareLogs
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bookshelf (R2-1). Opens `library.db` off the main thread, then shows the Compose [LibraryScreen]. Long-pressing the
 * sidebar button toggles the debug HUD; with the HUD on the ＋ menu offers a 50-page test notebook.
 */
class LibraryActivity : ComponentActivity() {
    private val graph get() = NotateApp.graph
    private val notateExport = NotateFileExport(this)

    /** Title of the tab that had the focus when the editor last ran; null when no tabs are stored. */
    private val continueTitle = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            withContext(graph.dispatchers.io) { graph.libraryStore }
            val vm = ViewModelProvider(this@LibraryActivity, LibraryViewModel.Factory(graph.libraryDeps()))[LibraryViewModel::class.java]
            setContent {
                NotateTheme {
                    val covers = remember { CoverLoader(graph.paths::coverFile) }
                    val hud by graph.settings.debugHud.collectAsStateWithLifecycle()
                    CrashPrompt()
                    LibraryScreen(
                        vm = vm,
                        covers = covers,
                        onOpenDocument = { id -> open(vm, id) },
                        onSettings = { startActivity(SettingsActivity.intent(this@LibraryActivity)) },
                        onExportNotate = notateExport::start,
                        onSidebarLongClick = { graph.settings.debugHud.let { it.set(!it.value) } },
                        extraAdd = listOfNotNull(
                            continueTitle.value?.let { t -> getString(R.string.continue_with, t) to { startActivity(EditorActivity.continueIntent(this@LibraryActivity)) } },
                        ) + listOf(
                            getString(R.string.import_pdf) to { startActivity(ImportActivity.pickPdfIntent(this@LibraryActivity)) },
                            getString(R.string.import_book) to { startActivity(ImportActivity.pickBookIntent(this@LibraryActivity)) },
                            getString(R.string.import_notate_file) to { startActivity(ImportActivity.pickPackageIntent(this@LibraryActivity)) },
                            getString(R.string.import_folder) to { startActivity(ImportActivity.pickFolderIntent(this@LibraryActivity)) },
                        ) + if (hud) listOf(getString(R.string.create_test_notebook) to { createTestNotebook(vm) }) else emptyList(),
                    )
                }
            }
        }
    }

    /** After a crash: offers the reports once; they are filed under `crash/sent/` whatever the answer. */
    @Composable
    private fun CrashPrompt() {
        var reports by remember { mutableStateOf(CrashHandler.pendingCrashReports(graph.paths)) }
        if (reports.isEmpty()) return
        val done = { CrashHandler.markSent(graph.paths, reports); reports = emptyList() }
        NotateDialog(
            title = stringResource(R.string.crash_dialog_title), onDismiss = done,
            buttons = {
                DialogButton(stringResource(R.string.crash_not_now), onClick = done)
                DialogButton(stringResource(R.string.crash_share), filled = true) {
                    val intent = ShareLogs.crashIntent(this, graph.paths, graph.logger, reports, getString(R.string.share_crash_subject))
                    done()
                    startActivity(intent)
                }
            },
        ) { Text(stringResource(R.string.crash_dialog_message), fontSize = 16.sp) }
    }

    override fun onResume() {
        super.onResume()
        // Offers to go back to the tabs the editor had open (04 §6), labelled with the tab that had the focus.
        lifecycleScope.launch {
            continueTitle.value = withContext(graph.dispatchers.io) {
                try {
                    graph.workspaceStore.load().focusedTab?.title
                } catch (e: Exception) {
                    graph.logger.e("LibraryActivity", "Reading the workspace failed", e)
                    null
                }
            }
        }
    }

    private fun open(vm: LibraryViewModel, id: DocId) {
        vm.markOpened(id)
        startActivity(EditorActivity.intent(this, id))
    }

    private fun createTestNotebook(vm: LibraryViewModel) {
        lifecycleScope.launch {
            try {
                val id = graph.documents.createNotebook("Test notebook ($TEST_PAGES pages)", pageCount = TEST_PAGES)
                open(vm, id)
            } catch (e: Exception) {
                graph.logger.e("LibraryActivity", "Creating the test notebook failed", e)
            }
        }
    }

    private companion object { const val TEST_PAGES = 50 }
}
