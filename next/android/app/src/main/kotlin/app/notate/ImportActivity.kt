package app.notate

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.notate.core.web.WebImportException
import app.notate.web.WebImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Imports a book or web page (R4-1). Started with `ACTION_VIEW` by a file manager, or by the bookshelf "＋" menu through
 * [pickBookIntent] / [pickFolderIntent], which first show the system picker. Shows "Importing book…" if it takes over 2 s.
 * Main thread only.
 */
class ImportActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progress: AlertDialog? = null
    private var failure: AlertDialog? = null

    private val pickBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) finish() else runImport { NotateApp.graph.webImporter.importUri(uri) }
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) finish() else runImport { NotateApp.graph.webImporter.importTree(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // an import or picker is already running in the recreated task
        val data = intent.data
        when {
            intent.action == Intent.ACTION_VIEW && data != null -> runImport { NotateApp.graph.webImporter.importUri(data) }
            intent.getStringExtra(EXTRA_PICK) == PICK_FOLDER -> pickFolder.launch(null)
            else -> pickBook.launch(WebImporter.PICKER_MIME_TYPES)
        }
    }

    private fun runImport(block: suspend () -> Any) {
        val showProgress: Job = scope.launch {
            delay(PROGRESS_DELAY_MS)
            progress = AlertDialog.Builder(this@ImportActivity).setMessage(R.string.import_progress).setCancelable(false).show()
        }
        scope.launch {
            val error = try {
                block(); null
            } catch (e: WebImportException) {
                if (e.reason == WebImportException.Reason.TOO_LARGE) R.string.import_too_large else R.string.import_unsupported
            } catch (e: Exception) {
                NotateApp.graph.logger.e(TAG, "Import failed", e)
                R.string.import_unsupported
            }
            showProgress.cancel()
            progress?.dismiss()
            if (error == null) {
                startActivity(Intent(this@ImportActivity, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            } else {
                failure = AlertDialog.Builder(this@ImportActivity).setMessage(error).setPositiveButton(R.string.import_ok) { _, _ -> finish() }
                    .setOnCancelListener { finish() }.show()
            }
        }
    }

    override fun onDestroy() {
        progress?.dismiss(); failure?.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ImportActivity"
        private const val EXTRA_PICK = "pick"
        private const val PICK_FOLDER = "folder"
        private const val PROGRESS_DELAY_MS = 2000L

        /** "＋ → Import EPUB or HTML": system file picker, then import. */
        fun pickBookIntent(context: Context): Intent = Intent(context, ImportActivity::class.java)

        /** "＋ → Import HTML folder": system folder picker, then import. */
        fun pickFolderIntent(context: Context): Intent = Intent(context, ImportActivity::class.java).putExtra(EXTRA_PICK, PICK_FOLDER)

    }
}
