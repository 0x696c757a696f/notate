package app.notate

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.notate.core.store.pkg.PackageException
import app.notate.core.web.WebImportException
import app.notate.export.pkg.ConflictMode
import app.notate.export.pkg.PackageBusyException
import app.notate.web.WebImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

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

    private val pickPackage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) finish() else runPackageImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // an import or picker is already running in the recreated task
        val data = intent.data
        when {
            intent.action == Intent.ACTION_VIEW && data != null && isNotateFile(data) -> runPackageImport(data)
            intent.getStringExtra(EXTRA_PICK) == PICK_PACKAGE -> pickPackage.launch(arrayOf("*/*"))
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

    private fun isNotateFile(uri: Uri): Boolean = uri.lastPathSegment?.endsWith(".notate", ignoreCase = true) == true

    /** Imports a `.notate` package (R6-3): asks Replace / Keep both / Cancel when the notebook is already in the library. */
    private fun runPackageImport(uri: Uri) {
        val graph = NotateApp.graph
        val importer = graph.packageImporter
        val showProgress: Job = scope.launch {
            delay(PROGRESS_DELAY_MS)
            progress = AlertDialog.Builder(this@ImportActivity).setMessage(R.string.import_notate_progress).setCancelable(false).show()
        }
        scope.launch {
            var error: Int? = null
            var done = false
            try {
                val staged = importer.stage { contentResolver.openInputStream(uri) }
                val mode = if (importer.existing(staged) == null) ConflictMode.KEEP_BOTH else {
                    showProgress.cancel(); progress?.dismiss()
                    askConflict()
                }
                if (mode == null) importer.discard(staged) else { importer.import(staged, mode); done = true }
            } catch (e: PackageException) {
                error = when (e.reason) {
                    PackageException.Reason.NOT_A_PACKAGE -> R.string.import_not_notate
                    PackageException.Reason.UNSUPPORTED_VERSION -> R.string.import_newer
                    PackageException.Reason.TOO_LARGE -> R.string.import_too_large
                    PackageException.Reason.UNSAFE -> R.string.import_failed
                }
            } catch (e: PackageBusyException) {
                error = R.string.import_busy
            } catch (e: Exception) {
                graph.logger.e(TAG, "Package import failed", e)
                error = R.string.import_failed
            }
            showProgress.cancel()
            progress?.dismiss()
            when {
                error != null -> failure = AlertDialog.Builder(this@ImportActivity).setMessage(error).setPositiveButton(R.string.import_ok) { _, _ -> finish() }
                    .setOnCancelListener { finish() }.show()
                done -> {
                    startActivity(Intent(this@ImportActivity, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
                else -> finish()
            }
        }
    }

    /** "This notebook is already in your library." Returns the choice, or null for Cancel. */
    private suspend fun askConflict(): ConflictMode? = suspendCancellableCoroutine { cont ->
        val dialog = AlertDialog.Builder(this).setMessage(R.string.import_exists)
            .setPositiveButton(R.string.import_replace) { _, _ -> if (cont.isActive) cont.resumeWith(Result.success(ConflictMode.REPLACE)) }
            .setNeutralButton(R.string.import_keep_both) { _, _ -> if (cont.isActive) cont.resumeWith(Result.success(ConflictMode.KEEP_BOTH)) }
            .setNegativeButton(R.string.import_cancel) { _, _ -> if (cont.isActive) cont.resumeWith(Result.success(null)) }
            .setOnCancelListener { if (cont.isActive) cont.resumeWith(Result.success(null)) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
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
        private const val PICK_PACKAGE = "package"
        private const val PROGRESS_DELAY_MS = 2000L

        /** "＋ → Import EPUB or HTML": system file picker, then import. */
        fun pickBookIntent(context: Context): Intent = Intent(context, ImportActivity::class.java)

        /** "＋ → Import Notate file": system file picker, then import the `.notate` package. */
        fun pickPackageIntent(context: Context): Intent = Intent(context, ImportActivity::class.java).putExtra(EXTRA_PICK, PICK_PACKAGE)

        /** "＋ → Import HTML folder": system folder picker, then import. */
        fun pickFolderIntent(context: Context): Intent = Intent(context, ImportActivity::class.java).putExtra(EXTRA_PICK, PICK_FOLDER)

    }
}
