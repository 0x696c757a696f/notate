package app.notate

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.notate.core.model.DocId
import app.notate.core.web.WebImportException
import app.notate.editor.EditorActivity
import app.notate.pdf.PdfImportException
import app.notate.web.WebImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Imports a book, web page (R4-1) or PDF (R3-1). Started with `ACTION_VIEW` / `ACTION_SEND` by a file manager or another app, or
 * by the bookshelf "＋" menu through [pickBookIntent] / [pickFolderIntent] / [pickPdfIntent], which first show the system picker.
 * Shows "Importing book…" if a book takes over 2 s; a PDF over 50 pages shows "Importing… n / total" with Cancel. Main thread only.
 */
class ImportActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progress: AlertDialog? = null
    private var failure: AlertDialog? = null

    private val pickBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) finish() else runImport { NotateApp.graph.webImporter.importUri(uri) }
    }
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) finish() else runPdfImport(uri, open = false)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) finish() else runImport { NotateApp.graph.webImporter.importTree(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // an import or picker is already running in the recreated task
        val data = intent.data
        val shared = sharedPdf()
        when {
            shared != null -> runPdfImport(shared, open = true)
            intent.action == Intent.ACTION_VIEW && data != null && isPdf(data) -> runPdfImport(data, open = true)
            intent.getStringExtra(EXTRA_PICK) == PICK_PDF -> pickPdf.launch(arrayOf(PDF_MIME))
            intent.action == Intent.ACTION_VIEW && data != null -> runImport { NotateApp.graph.webImporter.importUri(data) }
            intent.getStringExtra(EXTRA_PICK) == PICK_FOLDER -> pickFolder.launch(null)
            else -> pickBook.launch(WebImporter.PICKER_MIME_TYPES)
        }
    }

    /** The PDF an `ACTION_SEND` carries, or null. */
    @Suppress("DEPRECATION")
    private fun sharedPdf(): Uri? {
        if (intent.action != Intent.ACTION_SEND) return null
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return null
        return uri.takeIf { intent.type == PDF_MIME || isPdf(it) }
    }

    private fun isPdf(uri: Uri): Boolean =
        intent.type == PDF_MIME || contentResolver.getType(uri) == PDF_MIME || uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true

    private var pdfJob: Job? = null

    private fun runPdfImport(uri: Uri, open: Boolean) {
        pdfJob = scope.launch {
            var id: DocId? = null
            val error = try {
                id = NotateApp.graph.pdfImporter.importUri(uri) { done, total ->
                    if (total > PROGRESS_PAGES) runOnUiThread { showPdfProgress(done, total) }
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfImportException) {
                if (e.reason == PdfImportException.Reason.PASSWORD_PROTECTED) R.string.import_pdf_password else R.string.import_pdf_unreadable
            } catch (e: Exception) {
                NotateApp.graph.logger.e(TAG, "PDF import failed", e)
                R.string.import_pdf_unreadable
            }
            progress?.dismiss()
            if (error == null && id != null) {
                startActivity(Intent(this@ImportActivity, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                if (open) startActivity(EditorActivity.intent(this@ImportActivity, id))
                finish()
            } else {
                failure = AlertDialog.Builder(this@ImportActivity).setMessage(error ?: R.string.import_pdf_unreadable)
                    .setPositiveButton(R.string.import_ok) { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            }
        }
    }

    private fun showPdfProgress(done: Int, total: Int) {
        val text = getString(R.string.import_pdf_progress, done, total)
        val dialog = progress ?: AlertDialog.Builder(this).setMessage(text).setCancelable(false)
            .setNegativeButton(R.string.import_cancel) { _, _ -> pdfJob?.cancel(); finish() }.show().also { progress = it }
        dialog.setMessage(text)
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
        private const val PICK_PDF = "pdf"
        private const val PDF_MIME = "application/pdf"
        private const val PROGRESS_PAGES = 50
        private const val PROGRESS_DELAY_MS = 2000L

        /** "＋ → Import EPUB or HTML": system file picker, then import. */
        fun pickBookIntent(context: Context): Intent = Intent(context, ImportActivity::class.java)

        /** "＋ → Import PDF": system file picker, then import. */
        fun pickPdfIntent(context: Context): Intent = Intent(context, ImportActivity::class.java).putExtra(EXTRA_PICK, PICK_PDF)

        /** "＋ → Import HTML folder": system folder picker, then import. */
        fun pickFolderIntent(context: Context): Intent = Intent(context, ImportActivity::class.java).putExtra(EXTRA_PICK, PICK_FOLDER)

    }
}
