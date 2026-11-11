package app.notate

import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import app.notate.core.model.DocId
import app.notate.export.pkg.PackageExporter
import app.notate.platform.log.ShareLogs
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Export as Notate file…" (R6-3): asks Share… or Save to…, then writes `<title>.notate`. Create it as a property of the
 * activity so its result launcher is registered before the activity starts. Main thread only.
 */
class NotateFileExport(private val activity: ComponentActivity) {
    private var pending: Pair<DocId, String>? = null

    private val saveTo = activity.registerForActivityResult(ActivityResultContracts.CreateDocument(PackageExporter.MIME)) { uri ->
        val (id, _) = pending ?: return@registerForActivityResult
        pending = null
        if (uri == null) return@registerForActivityResult
        val graph = NotateApp.graph
        graph.appScope.launch {
            val ok = try {
                val out = activity.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write the chosen file")
                out.use { graph.packageExporter.export(id, it) }
                true
            } catch (e: Exception) {
                graph.logger.e(TAG, "Saving the package failed", e)
                false
            }
            toast(if (ok) R.string.export_done else R.string.export_failed)
        }
    }

    /** Shows the Share… / Save to… choice for the notebook [id]. */
    fun start(id: DocId, title: String) {
        AlertDialog.Builder(activity).setTitle(activity.getString(R.string.export_title, title))
            .setPositiveButton(R.string.export_share) { _, _ -> share(id, title) }
            .setNeutralButton(R.string.export_save_to) { _, _ ->
                pending = id to title
                saveTo.launch(PackageExporter.fileName(title))
            }
            .setNegativeButton(R.string.import_cancel, null)
            .show()
    }

    private fun share(id: DocId, title: String) {
        val graph = NotateApp.graph
        graph.appScope.launch {
            try {
                val file = File(graph.paths.exportsDir, PackageExporter.fileName(title))
                graph.packageExporter.exportToFile(id, file)
                val uri = FileProvider.getUriForFile(activity, ShareLogs.authority(activity), file)
                val send = Intent(Intent.ACTION_SEND).setType(PackageExporter.MIME).putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                withContext(graph.dispatchers.main) {
                    activity.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            } catch (e: Exception) {
                graph.logger.e(TAG, "Sharing the package failed", e)
                toast(R.string.export_failed)
            }
        }
    }

    private suspend fun toast(text: Int) {
        withContext(NotateApp.graph.dispatchers.main) { Toast.makeText(activity.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    private companion object { const val TAG = "NotateFileExport" }
}
