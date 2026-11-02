package app.notate

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import app.notate.core.model.DocId
import app.notate.export.pkg.BackupItem
import app.notate.export.pkg.ConflictMode
import app.notate.platform.log.ShareLogs
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 04 §8: "This notebook couldn't be opened." with Restore from backup (only when the backup folder holds a copy of that
 * notebook) and Share logs. Restoring replaces the notebook; the damaged files are kept in the Trash.
 */
object OpenFailedDialog {
    private const val TAG = "OpenFailedDialog"

    /** Shows the dialog over [context] (an activity); returns false when it cannot be shown. */
    fun show(context: Context, id: DocId): Boolean {
        val activity = context as? Activity ?: return false
        val graph = NotateApp.graph
        graph.appScope.launch {
            val backup: BackupItem? = try {
                graph.backup.folder()?.let { graph.backupEngine.find(it, id) }
            } catch (e: Exception) {
                graph.logger.w(TAG, "Looking for a backup failed", e)
                null
            }
            withContext(graph.dispatchers.main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                val b = AlertDialog.Builder(activity).setTitle(R.string.open_failed_title)
                    .setNeutralButton(R.string.open_failed_share_logs) { _, _ ->
                        activity.startActivity(ShareLogs.logsIntent(activity, graph.paths, graph.logger, activity.getString(R.string.share_logs_subject)))
                    }
                    .setNegativeButton(R.string.open_failed_close, null)
                if (backup != null) b.setPositiveButton(R.string.open_failed_restore) { _, _ -> restore(activity, backup) }
                b.show()
            }
        }
        return true
    }

    private fun restore(context: Context, item: BackupItem) {
        val graph = NotateApp.graph
        val app = context.applicationContext
        graph.appScope.launch {
            val ok = try {
                graph.backupEngine.restore(listOf(item), layout = null, mode = ConflictMode.REPLACE).isNotEmpty()
            } catch (e: Exception) {
                graph.logger.e(TAG, "Restoring from backup failed", e)
                false
            }
            withContext(graph.dispatchers.main) {
                Toast.makeText(app, if (ok) R.string.open_failed_restored else R.string.import_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
