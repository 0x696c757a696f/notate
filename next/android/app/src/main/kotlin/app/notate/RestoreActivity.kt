package app.notate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import app.notate.export.pkg.BackupItem
import app.notate.export.pkg.BackupLayout
import app.notate.library.NotateTheme
import app.notate.library.ui.DialogButton
import app.notate.library.ui.NotateDialog
import kotlinx.coroutines.launch

/**
 * Settings → Backup → "Restore from backup…" (04 §9): pick the backup folder, tick the notebooks, restore them. Conflicts
 * default to Keep both. When the folder has a `library.json` it asks whether folders and tags come back too.
 * Main thread only.
 */
class RestoreActivity : ComponentActivity() {
    private sealed interface Phase {
        data object Loading : Phase
        data class Choose(val items: List<BackupItem>, val layout: BackupLayout?) : Phase
        data class Working(val done: Int, val total: Int) : Phase
        data class Done(val count: Int) : Phase
        data object Empty : Phase
    }

    private var phase by mutableStateOf<Phase>(Phase.Loading)
    private var unchecked by mutableStateOf(emptySet<String>())
    private var askLayout by mutableStateOf(false)
    private val graph get() = NotateApp.graph

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) finish() else load(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NotateTheme { Screen() } }
        if (savedInstanceState == null) pickFolder.launch(graph.settings.backupUri.value?.let(Uri::parse))
    }

    private fun load(uri: Uri) {
        lifecycleScope.launch {
            val root = DocumentFile.fromTreeUri(this@RestoreActivity, uri)
            val items = root?.let { graph.backupEngine.list(it) }.orEmpty()
            phase = if (root == null || items.isEmpty()) Phase.Empty else Phase.Choose(items, graph.backupEngine.readLayout(root))
        }
    }

    private fun restore(c: Phase.Choose, withLayout: Boolean) {
        val chosen = c.items.filter { it.info.docId !in unchecked }
        phase = Phase.Working(0, chosen.size)
        graph.appScope.launch {
            val ids = graph.backupEngine.restore(chosen, if (withLayout) c.layout else null) { done, total -> phase = Phase.Working(done, total) }
            phase = Phase.Done(ids.size)
        }
    }

    @Composable
    private fun Screen() {
        Column(Modifier.fillMaxSize().background(Color.White).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.restore_title), fontSize = 22.sp)
            when (val p = phase) {
                Phase.Loading -> Text(stringResource(R.string.restore_loading), fontSize = 16.sp)
                Phase.Empty -> {
                    Text(stringResource(R.string.restore_empty), fontSize = 16.sp)
                    DialogButton(stringResource(R.string.import_ok)) { finish() }
                }
                is Phase.Working -> Text(stringResource(R.string.restore_working, p.done, p.total), fontSize = 16.sp)
                is Phase.Done -> {
                    Text(stringResource(R.string.restore_done, p.count), fontSize = 16.sp)
                    DialogButton(stringResource(R.string.import_ok), filled = true) {
                        startActivity(Intent(this@RestoreActivity, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }
                }
                is Phase.Choose -> Choose(p)
            }
        }
    }

    @Composable
    private fun ColumnScope.Choose(c: Phase.Choose) {
        val dateFormat = remember { android.text.format.DateFormat.getMediumDateFormat(this@RestoreActivity) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(c.items, key = { it.info.docId + it.file.uri }) { item ->
                val on = item.info.docId !in unchecked
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                        unchecked = if (on) unchecked + item.info.docId else unchecked - item.info.docId
                    },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(if (on) "☑" else "☐", fontSize = 24.sp)
                    Column {
                        Text(item.info.title, fontSize = 16.sp)
                        Text(dateFormat.format(java.util.Date(item.info.modifiedAt)), fontSize = 13.sp, color = Color(0xFF5F5F5F))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            DialogButton(stringResource(R.string.restore_cancel)) { finish() }
            DialogButton(stringResource(R.string.restore_action), enabled = c.items.any { it.info.docId !in unchecked }, filled = true) {
                if (c.layout != null) askLayout = true else restore(c, withLayout = false)
            }
        }
        if (askLayout && c.layout != null) {
            NotateDialog(
                title = stringResource(R.string.restore_layout_question), onDismiss = { askLayout = false },
                buttons = {
                    DialogButton(stringResource(R.string.restore_layout_no)) { askLayout = false; restore(c, withLayout = false) }
                    DialogButton(stringResource(R.string.restore_layout_yes), filled = true) { askLayout = false; restore(c, withLayout = true) }
                },
                content = {},
            )
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, RestoreActivity::class.java)
    }
}
