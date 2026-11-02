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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bookshelf (R2-1). Opens `library.db` off the main thread, then shows the Compose [LibraryScreen]. Long-pressing the
 * sidebar button toggles the debug HUD; with the HUD on the ＋ menu offers a 50-page test notebook.
 */
class LibraryActivity : ComponentActivity() {
    private val graph get() = NotateApp.graph

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
                    LibraryScreen(
                        vm = vm,
                        covers = covers,
                        onOpenDocument = { id -> open(vm, id) },
                        onSidebarLongClick = { graph.settings.debugHud.let { it.set(!it.value) } },
                        extraAdd = listOfNotNull(
                            continueTitle.value?.let { t -> getString(R.string.continue_with, t) to { startActivity(EditorActivity.continueIntent(this@LibraryActivity)) } },
                        ) + listOf(
                            getString(R.string.import_book) to { startActivity(ImportActivity.pickBookIntent(this@LibraryActivity)) },
                            getString(R.string.import_folder) to { startActivity(ImportActivity.pickFolderIntent(this@LibraryActivity)) },
                        ) + if (hud) listOf(getString(R.string.create_test_notebook) to { createTestNotebook(vm) }) else emptyList(),
                    )
                }
            }
        }
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
