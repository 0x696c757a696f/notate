package app.notate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
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
                        extraAdd = if (hud) listOf(getString(R.string.create_test_notebook) to { createTestNotebook(vm) }) else emptyList(),
                    )
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
